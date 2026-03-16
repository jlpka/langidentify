/*
 * Copyright 2026 Jeremy Lilley (jeremy@jlilley.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jlpka.langidentify;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NgramTableTest {

  /** Helper: create an Ngram from a string. */
  private static NgramTable.Ngram ngram(String s) {
    return new NgramTable.Ngram(s.toCharArray(), 0, s.length());
  }

  /** Helper: add a single-language entry to a builder. */
  private static void addEntry(NgramTable.Builder b, String ngram, int langIdx, float prob) {
    NgramTable.Ngram key = ngram(ngram);
    NgramTable.LangProbListBuilder lpb = b.get(key);
    if (lpb != null) {
      lpb.add(langIdx, prob);
    } else {
      lpb = new NgramTable.LangProbListBuilder();
      lpb.add(langIdx, prob);
      b.put(key, lpb);
    }
  }

  // ========================================================================
  // Empty table
  // ========================================================================

  @Test
  void emptyTable_lookupReturnsNull() {
    NgramTable table = new NgramTable.Builder().compact();
    assertNull(table.lookup(ngram("th")));
  }

  @Test
  void emptyTable_sizeIsZero() {
    NgramTable table = new NgramTable.Builder().compact();
    assertEquals(0, table.size());
  }

  @Test
  void emptyTable_probDataIsEmpty() {
    NgramTable table = new NgramTable.Builder().compact();
    assertEquals(0, table.probData().length);
  }

  // ========================================================================
  // Single entry
  // ========================================================================

  @Test
  void singleEntry_lookupFindsIt() {
    NgramTable.Builder b = new NgramTable.Builder();
    addEntry(b, "th", 0, -2.5f);
    NgramTable table = b.compact();

    NgramTable.NgramEntry entry = table.lookup(ngram("th"));
    assertNotNull(entry);
    assertArrayEquals(new int[] {0}, entry.langIndices);
    assertEquals(-2.5f, table.probData()[entry.probOffset], 1e-6f);
  }

  @Test
  void singleEntry_sizeIsOne() {
    NgramTable.Builder b = new NgramTable.Builder();
    addEntry(b, "th", 0, -2.5f);
    NgramTable table = b.compact();
    assertEquals(1, table.size());
  }

  @Test
  void singleEntry_lookupMissReturnsNull() {
    NgramTable.Builder b = new NgramTable.Builder();
    addEntry(b, "th", 0, -2.5f);
    NgramTable table = b.compact();
    assertNull(table.lookup(ngram("he")));
  }

  // ========================================================================
  // Multiple entries
  // ========================================================================

  @Test
  void multipleEntries_allFound() {
    NgramTable.Builder b = new NgramTable.Builder();
    addEntry(b, "th", 0, -2.0f);
    addEntry(b, "he", 1, -3.0f);
    addEntry(b, "in", 2, -4.0f);
    NgramTable table = b.compact();

    assertEquals(3, table.size());

    NgramTable.NgramEntry e1 = table.lookup(ngram("th"));
    assertNotNull(e1);
    assertEquals(-2.0f, table.probData()[e1.probOffset], 1e-6f);

    NgramTable.NgramEntry e2 = table.lookup(ngram("he"));
    assertNotNull(e2);
    assertEquals(-3.0f, table.probData()[e2.probOffset], 1e-6f);

    NgramTable.NgramEntry e3 = table.lookup(ngram("in"));
    assertNotNull(e3);
    assertEquals(-4.0f, table.probData()[e3.probOffset], 1e-6f);
  }

  @Test
  void multipleEntries_missReturnsNull() {
    NgramTable.Builder b = new NgramTable.Builder();
    addEntry(b, "th", 0, -2.0f);
    addEntry(b, "he", 1, -3.0f);
    NgramTable table = b.compact();
    assertNull(table.lookup(ngram("zz")));
  }

  // ========================================================================
  // Multi-language entries
  // ========================================================================

  @Test
  void multiLangEntry_allLanguagesPresent() {
    NgramTable.Builder b = new NgramTable.Builder();
    addEntry(b, "the", 0, -1.5f); // lang 0: English
    addEntry(b, "the", 1, -3.0f); // lang 1: French
    addEntry(b, "the", 2, -5.0f); // lang 2: German
    NgramTable table = b.compact();

    assertEquals(1, table.size());

    NgramTable.NgramEntry entry = table.lookup(ngram("the"));
    assertNotNull(entry);
    assertEquals(3, entry.langIndices.length);

    float[] pd = table.probData();
    // Verify all three (langIdx, prob) pairs are present
    boolean[] found = new boolean[3];
    for (int j = 0; j < entry.langIndices.length; j++) {
      int li = entry.langIndices[j];
      float prob = pd[entry.probOffset + j];
      if (li == 0) {
        assertEquals(-1.5f, prob, 1e-6f);
        found[0] = true;
      } else if (li == 1) {
        assertEquals(-3.0f, prob, 1e-6f);
        found[1] = true;
      } else if (li == 2) {
        assertEquals(-5.0f, prob, 1e-6f);
        found[2] = true;
      }
    }
    assertTrue(found[0] && found[1] && found[2]);
  }

  // ========================================================================
  // Reusable lookup key
  // ========================================================================

  @Test
  void reusableLookupKey_findsCorrectEntries() {
    NgramTable.Builder b = new NgramTable.Builder();
    addEntry(b, "ab", 0, -1.0f);
    addEntry(b, "bc", 0, -2.0f);
    addEntry(b, "cd", 0, -3.0f);
    NgramTable table = b.compact();

    // Simulate Detector's pattern: reuse one Ngram, change offset
    char[] buf = "abcd".toCharArray();
    NgramTable.Ngram key = new NgramTable.Ngram(buf, 0, 2);

    key.offset = 0; // "ab"
    assertNotNull(table.lookup(key));

    key.offset = 1; // "bc"
    assertNotNull(table.lookup(key));

    key.offset = 2; // "cd"
    assertNotNull(table.lookup(key));
  }

  // ========================================================================
  // probData flat array
  // ========================================================================

  @Test
  void probData_containsAllValues() {
    NgramTable.Builder b = new NgramTable.Builder();
    addEntry(b, "aa", 0, -1.0f);
    addEntry(b, "aa", 1, -2.0f);
    addEntry(b, "bb", 0, -3.0f);
    NgramTable table = b.compact();

    // Total probs = 2 (for "aa") + 1 (for "bb") = 3
    assertEquals(3, table.probData().length);
  }

  // ========================================================================
  // langIndices interning
  // ========================================================================

  @Test
  void langIndicesInterning_samePatternsShareArray() {
    NgramTable.Builder b = new NgramTable.Builder();
    // Two different ngrams, both mapping to languages {0, 1}
    addEntry(b, "ab", 0, -1.0f);
    addEntry(b, "ab", 1, -2.0f);
    addEntry(b, "cd", 0, -3.0f);
    addEntry(b, "cd", 1, -4.0f);
    NgramTable table = b.compact();

    NgramTable.NgramEntry e1 = table.lookup(ngram("ab"));
    NgramTable.NgramEntry e2 = table.lookup(ngram("cd"));
    assertNotNull(e1);
    assertNotNull(e2);
    // Interned: same int[] instance
    assertSame(e1.langIndices, e2.langIndices);
  }

  @Test
  void langIndicesInterning_differentPatternsNotShared() {
    NgramTable.Builder b = new NgramTable.Builder();
    addEntry(b, "ab", 0, -1.0f); // {0}
    addEntry(b, "cd", 1, -2.0f); // {1}
    NgramTable table = b.compact();

    NgramTable.NgramEntry e1 = table.lookup(ngram("ab"));
    NgramTable.NgramEntry e2 = table.lookup(ngram("cd"));
    assertNotNull(e1);
    assertNotNull(e2);
    assertNotSame(e1.langIndices, e2.langIndices);
  }

  // ========================================================================
  // Different ngram lengths
  // ========================================================================

  @Test
  void differentLengths_doNotCollide() {
    NgramTable.Builder b = new NgramTable.Builder();
    addEntry(b, "a", 0, -1.0f);
    addEntry(b, "ab", 0, -2.0f);
    addEntry(b, "abc", 0, -3.0f);
    NgramTable table = b.compact();

    assertEquals(3, table.size());

    NgramTable.NgramEntry e1 = table.lookup(ngram("a"));
    NgramTable.NgramEntry e2 = table.lookup(ngram("ab"));
    NgramTable.NgramEntry e3 = table.lookup(ngram("abc"));
    assertNotNull(e1);
    assertNotNull(e2);
    assertNotNull(e3);
    assertEquals(-1.0f, table.probData()[e1.probOffset], 1e-6f);
    assertEquals(-2.0f, table.probData()[e2.probOffset], 1e-6f);
    assertEquals(-3.0f, table.probData()[e3.probOffset], 1e-6f);
  }

  // ========================================================================
  // Many entries (stress open-addressing)
  // ========================================================================

  @Test
  void manyEntries_allRetrievable() {
    NgramTable.Builder b = new NgramTable.Builder();
    int count = 1000;
    for (int i = 0; i < count; i++) {
      String s = String.format("%04d", i);
      addEntry(b, s, 0, -(float) i);
    }
    NgramTable table = b.compact();

    assertEquals(count, table.size());

    for (int i = 0; i < count; i++) {
      String s = String.format("%04d", i);
      NgramTable.NgramEntry entry = table.lookup(ngram(s));
      assertNotNull(entry, "Missing entry for: " + s);
      assertEquals(-(float) i, table.probData()[entry.probOffset], 1e-6f);
    }

    // Verify misses
    assertNull(table.lookup(ngram("9999")));
    assertNull(table.lookup(ngram("abcd")));
  }

  // ========================================================================
  // Builder: get during loading
  // ========================================================================

  @Test
  void builder_lookupBuilderReturnsExisting() {
    NgramTable.Builder b = new NgramTable.Builder();
    NgramTable.LangProbListBuilder lpb = new NgramTable.LangProbListBuilder();
    lpb.add(0, -1.0f);
    b.put(ngram("th"), lpb);

    // Same key, different Ngram instance
    NgramTable.LangProbListBuilder found = b.get(ngram("th"));
    assertSame(lpb, found);
  }

  @Test
  void builder_lookupBuilderReturnsNullForMissing() {
    NgramTable.Builder b = new NgramTable.Builder();
    assertNull(b.get(ngram("th")));
  }

  // ========================================================================
  // Ngram equals / hashCode / copy / compareTo
  // ========================================================================

  @Test
  void ngram_equalsWithDifferentArraysSameContent() {
    NgramTable.Ngram a = ngram("the");
    NgramTable.Ngram b = ngram("the");
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void ngram_equalsWithOffset() {
    char[] buf = "xxthexx".toCharArray();
    NgramTable.Ngram a = new NgramTable.Ngram(buf, 2, 3); // "the"
    NgramTable.Ngram b = ngram("the");
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void ngram_notEqualDifferentContent() {
    assertNotEquals(ngram("th"), ngram("he"));
  }

  @Test
  void ngram_notEqualDifferentLength() {
    assertNotEquals(ngram("th"), ngram("the"));
  }

  @Test
  void ngram_copy() {
    char[] buf = "xxthexx".toCharArray();
    NgramTable.Ngram orig = new NgramTable.Ngram(buf, 2, 3);
    NgramTable.Ngram copy = orig.copy();
    assertEquals(orig, copy);
    assertEquals(0, copy.offset);
    assertEquals(3, copy.length);
    // Verify it's an independent array
    assertNotSame(orig.chars, copy.chars);
  }

  @Test
  void ngram_compareTo() {
    NgramTable.Ngram a = ngram("ab");
    NgramTable.Ngram b = ngram("ac");
    NgramTable.Ngram c = ngram("abc");
    assertTrue(a.compareTo(b) < 0);
    assertTrue(b.compareTo(a) > 0);
    assertTrue(a.compareTo(c) < 0); // shorter length comes first
    assertEquals(0, a.compareTo(ngram("ab")));
  }

  @Test
  void ngram_toString() {
    char[] buf = "xxthexx".toCharArray();
    NgramTable.Ngram ng = new NgramTable.Ngram(buf, 2, 3);
    assertEquals("the", ng.toString());
  }

  // ========================================================================
  // Unicode ngrams
  // ========================================================================

  @Test
  void unicodeNgrams_lookupWorks() {
    NgramTable.Builder b = new NgramTable.Builder();
    addEntry(b, "日本", 0, -1.0f);
    addEntry(b, "中国", 1, -2.0f);
    addEntry(b, "café", 2, -3.0f);
    NgramTable table = b.compact();

    assertEquals(3, table.size());
    assertNotNull(table.lookup(ngram("日本")));
    assertNotNull(table.lookup(ngram("中国")));
    assertNotNull(table.lookup(ngram("café")));
    assertNull(table.lookup(ngram("한국")));
  }
}
