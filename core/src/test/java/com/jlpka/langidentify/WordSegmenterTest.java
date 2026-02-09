package com.jlpka.langidentify;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for WordSegmenter: word splitting, lowercasing, apostrophe handling, digit filtering,
 * alphabet boundary detection, and CJ run routing.
 */
class WordSegmenterTest {

  // A model with English + French (both LATIN) — no CJ languages, no CJClassifier.
  static Model latinModel;

  // A model with English + Japanese (LATIN + HAN + JA_KANA).
  // Only one CJ language, so no CJClassifier instantiation.
  static Model cjModel;

  /** Captured word emission from WordConsumer. */
  static class Word {
    final String text;
    final int alphaIdx;

    Word(String text, int alphaIdx) {
      this.text = text;
      this.alphaIdx = alphaIdx;
    }
  }

  /** Collects words and CJ runs into lists. */
  static class Collector {
    final List<Word> words = new ArrayList<>();
    final List<Word> cjRuns = new ArrayList<>();
  }

  @BeforeAll
  static void setup() throws IOException {
    // Latin-only model: en + fr share LATIN alphabet
    latinModel =
        new Model(
            new NgramTable[] {new NgramTable.Builder().compact()},
            new Language[] {Language.ENGLISH, Language.FRENCH},
            -10.0,
            -10.0,
            1,
            null,
            null);

    // CJ model: en (LATIN) + ja (HAN, JA_KANA)
    // Only one CJ language, so needsCJ = false, no CJClassifier file needed
    cjModel =
        new Model(
            new NgramTable[] {new NgramTable.Builder().compact()},
            new Language[] {Language.ENGLISH, Language.JAPANESE},
            -10.0,
            -10.0,
            1,
            null,
            null);
  }

  private WordSegmenter makeSegmenter(Model model, boolean withCJ, Collector col) {
    return new WordSegmenter(
        model,
        (wordBuf, wordLen, alphaIdx) ->
            col.words.add(new Word(new String(wordBuf, 0, wordLen), alphaIdx)),
        withCJ
            ? (wordBuf, wordLen, alphaIdx) ->
                col.cjRuns.add(new Word(new String(wordBuf, 0, wordLen), alphaIdx))
            : null);
  }

  private List<Word> segmentWords(String text) {
    var col = new Collector();
    var seg = makeSegmenter(latinModel, false, col);
    seg.segment(text);
    return col.words;
  }

  // ========================================================================
  // Basic word splitting
  // ========================================================================

  @Test
  void emptyString() {
    assertEquals(List.of(), segmentWords(""));
  }

  @Test
  void singleWord() {
    var words = segmentWords("hello");
    assertEquals(1, words.size());
    assertEquals("hello", words.get(0).text);
  }

  @Test
  void multipleWords() {
    var words = segmentWords("hello world foo");
    assertEquals(3, words.size());
    assertEquals("hello", words.get(0).text);
    assertEquals("world", words.get(1).text);
    assertEquals("foo", words.get(2).text);
  }

  @Test
  void leadingAndTrailingSpaces() {
    var words = segmentWords("  hello  ");
    assertEquals(1, words.size());
    assertEquals("hello", words.get(0).text);
  }

  @Test
  void multipleSeparators() {
    var words = segmentWords("hello---world");
    assertEquals(2, words.size());
    assertEquals("hello", words.get(0).text);
    assertEquals("world", words.get(1).text);
  }

  // ========================================================================
  // Lowercasing
  // ========================================================================

  @Test
  void lowercasesWords() {
    var words = segmentWords("Hello WORLD FoO");
    assertEquals("hello", words.get(0).text);
    assertEquals("world", words.get(1).text);
    assertEquals("foo", words.get(2).text);
  }

  // ========================================================================
  // Apostrophe handling
  // ========================================================================

  @Test
  void apostropheSplitsWord() {
    var words = segmentWords("don't");
    assertEquals(1, words.size());
    assertEquals("don't", words.get(0).text);
  }

  @Test
  void smartApostrophe() {
    var words = segmentWords("don\u2019t");
    assertEquals(1, words.size());
    assertEquals("don't", words.get(0).text);
    words = segmentWords("don\u0092t");
    assertEquals(1, words.size());
    assertEquals("don't", words.get(0).text);
  }

  @Test
  void trailingApostropheStripped() {
    // "dogs'" -> apostrophe at end is stripped
    var words = segmentWords("dogs' ");
    assertEquals(1, words.size());
    assertEquals("dogs", words.get(0).text);
  }

  @Test
  void onlyApostropheAfterLetterDropped() {
    // Single letter followed by apostrophe at word boundary: "x'" -> trailing ' stripped -> "x"
    var words = segmentWords("x' y");
    assertEquals(2, words.size());
    assertEquals("x", words.get(0).text);
    assertEquals("y", words.get(1).text);
  }

  @Test
  void doubleApostropheNotAdded() {
    // "don''t" — second apostrophe should not be added (consecutive apostrophe check)
    var words = segmentWords("don''t");
    // The second ' is not a letter and not added, so it acts as a separator
    assertEquals(2, words.size());
    assertEquals("don", words.get(0).text);
    assertEquals("t", words.get(1).text);
  }

  // ========================================================================
  // Digit filtering (Latin only)
  // ========================================================================

  @Test
  void digitPoisonsLatinWord() {
    var words = segmentWords("abc123def");
    // The whole run is one Latin word with digits — should be filtered
    assertEquals(0, words.size());
  }

  @Test
  void digitBetweenLatinWords() {
    // Digits between words: "hello 42world" -> "hello" emitted, "42world" poisoned
    // Actually digits don't break the word since they're not alphabet chars,
    // but they set the HAS_DIGIT flag. "hello" is clean, then "42" sets digit flag,
    // then "world" is Latin with digit flag -> poisoned
    var words = segmentWords("hello 42world");
    assertEquals(1, words.size());
    assertEquals("hello", words.get(0).text);
  }

  @Test
  void pureDigitsNoEmission() {
    // Just digits, no alphabet chars — nothing emitted
    var words = segmentWords("12345");
    assertEquals(0, words.size());
  }

  // ========================================================================
  // Alphabet boundaries
  // ========================================================================

  @Test
  void alphabetBoundarySplitsWord() {
    // Latin followed immediately by Cyrillic (not in latin model, so those chars are ignored)
    // But with cjModel: Latin followed by HAN should split
    var col = new Collector();
    var seg = makeSegmenter(cjModel, false, col);
    // 'hello' (LATIN) + '\u4e00' (HAN)
    seg.segment("hello\u4e00");
    assertEquals(2, col.words.size());
    assertEquals("hello", col.words.get(0).text);
    assertEquals("\u4e00", col.words.get(1).text);
  }

  @Test
  void unknownAlphabetCharsIgnored() {
    // Characters not in the model's alphabet set are skipped
    var words = segmentWords("hello\u0410world"); // \u0410 = Cyrillic A, not in latinModel
    assertEquals(2, words.size());
    assertEquals("hello", words.get(0).text);
    assertEquals("world", words.get(1).text);
  }

  // ========================================================================
  // CJ consumer routing
  // ========================================================================

  @Test
  void cjUnifiedRoutedToCJConsumer() {
    var col = new Collector();
    var seg = makeSegmenter(cjModel, true, col);
    seg.segment("hello \u4e00\u4e8c\u4e09");

    // "hello" goes to wordConsumer
    assertEquals(1, col.words.size());
    assertEquals("hello", col.words.get(0).text);

    // HAN run goes to cjConsumer
    assertEquals(1, col.cjRuns.size());
    var run = col.cjRuns.get(0);
    assertEquals("\u4e00\u4e8c\u4e09", col.cjRuns.get(0).text);
  }

  @Test
  void jaKanaRoutedToCJConsumer() {
    var col = new Collector();
    var seg = makeSegmenter(cjModel, true, col);
    // Hiragana chars: \u3042 = あ, \u3044 = い, \u3046 = う
    seg.segment("hello \u3042\u3044\u3046");

    assertEquals(1, col.words.size());
    assertEquals("hello", col.words.get(0).text);

    // JA_KANA run goes to cjConsumer
    assertEquals(1, col.cjRuns.size());
    assertEquals("\u3042\u3044\u3046", col.cjRuns.get(0).text);
  }

  @Test
  void mixedKanaAndCJUnifiedSplitSeparately() {
    var col = new Collector();
    var seg = makeSegmenter(cjModel, true, col);
    // Kana then HAN: different alphabets, so two separate runs
    seg.segment("\u3042\u3044\u4e00\u4e8c");

    assertEquals(0, col.words.size());
    assertEquals(2, col.cjRuns.size());

    // First: JA_KANA
    var kana = col.cjRuns.get(0);
    assertEquals("\u3042\u3044", kana.text);
    // Second: HAN
    var cj = col.cjRuns.get(1);
    assertEquals("\u4e00\u4e8c", cj.text);
  }

  @Test
  void noCJConsumerMeansCJGoesToWordConsumer() {
    // When cjConsumer is null, CJ words go through wordConsumer
    var col = new Collector();
    var seg = makeSegmenter(cjModel, false, col);
    seg.segment("\u4e00\u4e8c");

    assertEquals(1, col.words.size());
    assertEquals(0, col.cjRuns.size());
    assertEquals("\u4e00\u4e8c", col.words.get(0).text);
  }

  @Test
  void cjRunOriginalTextOffset() {
    var col = new Collector();
    var seg = makeSegmenter(cjModel, true, col);
    String text = "abc \u4e00\u4e8c\u4e09 xyz";
    seg.segment(text);

    assertEquals(2, col.words.size()); // "abc", "xyz"
    assertEquals(1, col.cjRuns.size());
    assertEquals("\u4e00\u4e8c\u4e09", col.cjRuns.get(0).text);
  }

  // ========================================================================
  // Reuse across calls
  // ========================================================================

  @Test
  void segmenterReusableBetweenCalls() {
    var col = new Collector();
    var seg = makeSegmenter(latinModel, false, col);

    seg.segment("hello");
    assertEquals(1, col.words.size());
    assertEquals("hello", col.words.get(0).text);

    seg.segment("world foo");
    assertEquals(3, col.words.size());
    assertEquals("world", col.words.get(1).text);
    assertEquals("foo", col.words.get(2).text);
  }

  @Test
  void extractCJRuns() {
    var col = new Collector();
    var seg = makeSegmenter(cjModel, true, col);
    String blobLatin = "abcdefghij";
    String blobCJ = "那只敏捷的棕色狐狸跳过了那只懒惰的狗";
    StringBuilder text = new StringBuilder();
    text.append(blobLatin);
    text.append(blobCJ);
    for (int i = 0; i < WordSegmenter.MAX_WORD_LEN + 5; i += blobLatin.length()) {
      text.append(blobLatin);
    }
    for (int i = 0; i < WordSegmenter.MAX_CJ_WORD_LEN + 5; i += blobCJ.length()) {
      text.append(blobCJ);
    }
    seg.segment(text);
    assertEquals(2, col.words.size());
    assertEquals(3, col.cjRuns.size());
    assertEquals(blobLatin, col.words.get(0).text);
    assertEquals(blobCJ, col.cjRuns.get(0).text);
    assertEquals(WordSegmenter.MAX_WORD_LEN, col.words.get(1).text.length());
    assertEquals(WordSegmenter.MAX_CJ_WORD_LEN, col.cjRuns.get(1).text.length());
    String t = col.words.get(1).text;
    for (int i = 0; i < t.length(); i += blobLatin.length()) {
      String s = t.substring(i, Math.min(i + blobLatin.length(), t.length()));
      assertEquals(s, blobLatin.substring(0, s.length()));
    }
    t = col.cjRuns.get(1).text;
    for (int i = 0; i < t.length(); i += blobCJ.length()) {
      String s = t.substring(i, Math.min(i + blobCJ.length(), t.length()));
      assertEquals(s, blobCJ.substring(0, s.length()));
    }
  }

  // ========================================================================
  // Edge cases
  // ========================================================================

  @Test
  void onlySpaces() {
    assertEquals(List.of(), segmentWords("   "));
  }

  @Test
  void onlyPunctuation() {
    assertEquals(List.of(), segmentWords("---...!!!"));
  }

  @Test
  void singleCharWord() {
    var words = segmentWords("a b c");
    assertEquals(3, words.size());
    assertEquals("a", words.get(0).text);
    assertEquals("b", words.get(1).text);
    assertEquals("c", words.get(2).text);
  }

  @Test
  void digitDoesNotPoisonNonLatin() {
    // Digits adjacent to CJ chars should NOT poison the CJ word
    var col = new Collector();
    var seg = makeSegmenter(cjModel, false, col);
    seg.segment("123\u4e00\u4e8c");
    assertEquals(1, col.words.size());
    assertEquals("\u4e00\u4e8c", col.words.get(0).text);
  }
}
