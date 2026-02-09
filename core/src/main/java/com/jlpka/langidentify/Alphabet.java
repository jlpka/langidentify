package com.jlpka.langidentify;

import java.util.Collections;
import java.util.Set;

/**
 * Writing systems used to classify characters during language detection. Each {@link Language} is
 * associated with one or more alphabets, allowing the detector to narrow candidates by script
 * before scoring ngrams.
 */
public enum Alphabet {
  UNKNOWN,
  // Latin + CJK.
  LATIN,
  HAN, // Chinese chars + Japanese Kanji share a char range
  JA_KANA, // Japanese Hiragana + Katakana
  HANGUL,
  // Others.
  ARABIC,
  ARMENIAN,
  BENGALI,
  CYRILLIC,
  DEVANAGARI,
  ETHIOPIC,
  GEORGIAN,
  GREEK,
  GUJARATI,
  GURMUKHI,
  HEBREW,
  KANNADA,
  KHMER,
  LAO,
  MALAYALAM,
  MYANMAR,
  SINHALA,
  TAMIL,
  TELUGU,
  THAI;

  public static final Set<Alphabet> LATIN_SET = Collections.singleton(LATIN);
  public static final Set<Alphabet> CYRILLIC_SET = Collections.singleton(CYRILLIC);
  public static final Set<Alphabet> ARABIC_SET = Collections.singleton(ARABIC);

  // Precomputed bit set for ASCII letters (0..127). Two longs cover the range.
  // LATIN_LO covers chars 0..63, LATIN_HI covers chars 64..127.
  private static final long LATIN_LO;
  private static final long LATIN_HI;

  static {
    long lo = 0, hi = 0;
    for (int c = 0; c < 128; c++) {
      if (Character.isLetter(c)) {
        if (c < 64) {
          lo |= 1L << c;
        } else {
          hi |= 1L << (c - 64);
        }
      }
    }
    LATIN_LO = lo;
    LATIN_HI = hi;
  }

  /**
   * Returns {@code LATIN} if the character is an ASCII letter (0..127), otherwise {@code UNKNOWN}.
   */
  public static Alphabet getAlphabet7Bit(char ch) {
    long mask = ch < 64 ? LATIN_LO : LATIN_HI;
    return (mask & (1L << (ch & 63))) != 0 ? LATIN : UNKNOWN;
  }

  /** Returns the alphabet for the given character, or {@code UNKNOWN} if not recognized. */
  public static Alphabet getAlphabet(char ch) {
    if (ch < 0x80) {
      // Bit test against precomputed ASCII letter set — no method call
      long mask = ch < 64 ? LATIN_LO : LATIN_HI;
      return (mask & (1L << (ch & 63))) != 0 ? LATIN : UNKNOWN;
    }
    switch (Character.UnicodeScript.of(ch)) {
      case LATIN:
        return LATIN;
      case HAN:
        return HAN;
      case HIRAGANA: // fall through
      case KATAKANA:
        return JA_KANA;
      case HANGUL:
        return HANGUL;
      case CYRILLIC:
        return CYRILLIC;
      case ARABIC:
        return ARABIC;
      case GREEK:
        return GREEK;
      case ARMENIAN:
        return ARMENIAN;
      case BENGALI:
        return BENGALI;
      case DEVANAGARI:
        return DEVANAGARI;
      case ETHIOPIC:
        return ETHIOPIC;
      case GEORGIAN:
        return GEORGIAN;
      case GUJARATI:
        return GUJARATI;
      case GURMUKHI:
        return GURMUKHI;
      case HEBREW:
        return HEBREW;
      case KANNADA:
        return KANNADA;
      case KHMER:
        return KHMER;
      case LAO:
        return LAO;
      case MALAYALAM:
        return MALAYALAM;
      case MYANMAR:
        return MYANMAR;
      case SINHALA:
        return SINHALA;
      case TAMIL:
        return TAMIL;
      case TELUGU:
        return TELUGU;
      case THAI:
        return THAI;
      default:
        return UNKNOWN;
    }
  }

  /**
   * Returns the detection weight for this alphabet. CJK characters carry more linguistic signal per
   * character than Latin letters, so they are weighted higher when determining the predominant
   * alphabet.
   *
   * <p>As an example, the average English word length is 4.7 chars, while the average Chinese word
   * is 1.6 chars. 4.7/1.6 -> 2.93 (call it 3x density)
   *
   * <p>Japanese Kana typically represent a consonant + a vowel.
   */
  public double weight() {
    switch (this) {
      case HAN:
        return 3.0;
      case HANGUL: // fall through
      case JA_KANA:
        return 2.0;
      default:
        return 1.0;
    }
  }

  /** Parses an alphabet name (case-insensitive). Returns {@code UNKNOWN} if unrecognized. */
  public static Alphabet fromString(String s) {
    if (s == null) return UNKNOWN;
    s = s.toLowerCase();
    for (Alphabet alpha : values()) {
      if (alpha.name().equalsIgnoreCase(s)) {
        return alpha;
      }
    }
    return UNKNOWN;
  }
}
