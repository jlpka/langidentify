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

import java.io.IOException;
import java.io.Reader;

/**
 * Segments text into words by alphabet, handling lowercasing, apostrophe splitting, digit
 * filtering, and CJ run detection. Calls back to consumers for each emitted word and CJ run.
 */
class WordSegmenter {
  /** Maximum length for alphabetic words; longer words are truncated. */
  public static final int MAX_WORD_LEN = 64;

  /** Maximum length for CJ runs; longer runs are split into chunks of this size. */
  public static final int MAX_CJ_WORD_LEN = 256;

  static final int HAS_DIGIT = 1;
  static final int HAS_APOSTROPHE = 2;

  @FunctionalInterface
  interface WordConsumer {
    void accept(char[] wordBuf, int wordLen, int alphaIdx);
  }

  private final Model model;
  final char[] wordBuf = new char[MAX_CJ_WORD_LEN];
  private final WordConsumer wordConsumer;
  private final WordConsumer cjConsumer; // null if no CJ classification needed

  // Mutable state, reset on each segment() call
  private int wordLen;
  private int wordStart; // start index in original text, -1 if no current word
  private int wordAlphaIdx;
  private int specialCases;

  WordSegmenter(Model model, WordConsumer wordConsumer, WordConsumer cjConsumer) {
    this.model = model;
    this.wordConsumer = wordConsumer;
    this.cjConsumer = cjConsumer;
  }

  void segment(CharSequence text) {
    initSegment();
    final int length = text.length();
    for (int i = 0; i < length; i++) {
      handleChar(text.charAt(i), i);
    }
    if (wordStart >= 0) {
      emitWord();
    }
  }

  void segment(char[] text, int ofs, int len) {
    initSegment();
    for (int i = 0; i < len; i++) {
      handleChar(text[ofs + i], i);
    }
    if (wordStart >= 0) {
      emitWord();
    }
  }

  void segment(Reader reader) throws IOException {
    initSegment();
    int pos = 0;
    char[] buf = new char[1024];
    for (; ; ) {
      int nread = reader.read(buf);
      if (nread <= 0) break;
      for (int i = 0; i < nread; i++) {
        handleChar(buf[i], pos++);
      }
    }
    if (wordStart >= 0) {
      emitWord();
    }
  }

  private void initSegment() {
    wordLen = 0;
    wordStart = -1;
    wordAlphaIdx = -1;
    specialCases = 0;
  }

  private void handleChar(char c, int pos) {
    Alphabet alpha = Alphabet.getAlphabet(c);
    int alphaIdx = model.alphabetIndex(alpha);

    if (alphaIdx >= 0) {
      if (alphaIdx != wordAlphaIdx && wordStart >= 0) {
        emitWord();
      }
      if (wordLen < MAX_WORD_LEN) {
        wordBuf[wordLen++] = Character.toLowerCase(c);
      } else { // uncommon
        if (model.isCJAlphabet(
            wordAlphaIdx)) { // CJ special case: higher limit + emit the partial word.
          if (wordLen == MAX_CJ_WORD_LEN) {
            emitWord();
          }
          wordBuf[wordLen++] = c;
        }
      }
      if (wordStart < 0) wordStart = pos;
      wordAlphaIdx = alphaIdx;
    } else if (c >= '0' && c <= '9') { // only care about standard digits here
      specialCases |= HAS_DIGIT;
    } else if (wordStart >= 0
        && (c == '\'' || c == '\u2019' || c == '\u0092')
        && wordBuf[wordLen - 1] != '\'') {
      // \u2019 = right single quotation mark (curly apostrophe).
      // \u0092 = Windows-1252 apostrophe mis-decoded as UTF-8.
      specialCases |= HAS_APOSTROPHE;
      if (wordLen < MAX_WORD_LEN) {
        wordBuf[wordLen++] = '\'';
      }
    } else {
      if (wordStart >= 0) {
        emitWord();
      }
      specialCases = 0;
    }
  }

  // pos = current position in text (boundary char, or text.length() for trailing word)
  private void emitWord() {
    // Special-case filtering
    if (specialCases != 0) {
      if ((specialCases & HAS_DIGIT) != 0 && model.getAlphabets()[wordAlphaIdx] == Alphabet.LATIN) {
        wordLen = 0;
        wordStart = -1;
        specialCases = 0;
        return;
      }
      if ((specialCases & HAS_APOSTROPHE) != 0 && wordBuf[wordLen - 1] == '\'') {
        if (--wordLen == 0) {
          wordStart = -1;
          specialCases = 0;
          return;
        }
      }
    }

    if (cjConsumer != null && model.isCJAlphabet(wordAlphaIdx)) {
      cjConsumer.accept(wordBuf, wordLen, wordAlphaIdx);
    } else {
      wordConsumer.accept(wordBuf, wordLen, wordAlphaIdx);
    }

    wordLen = 0;
    wordStart = -1;
    specialCases = 0;
  }
}
