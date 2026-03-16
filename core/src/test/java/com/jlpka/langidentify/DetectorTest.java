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

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DetectorTest {

  private static Model model;
  private static Detector detector;

  @BeforeAll
  static void loadModel() throws IOException {
    List<Language> languages = Language.fromCommaSeparated("en,fr,de,es,el,ru,cjk");
    model = Model.loadLite(languages);
    assertEquals(5, model.getMaxNgram());
    assertEquals(-12.0, model.getMinLogProb(), 0.1);
    assertEquals(-12.0, model.getTwMinLogProb(), 0.1);
    detector = new Detector(model);
  }

  @Test
  void detectBasicLatin() {
    assertEquals(Language.ENGLISH, detector.detect("The quick brown fox jumps over the lazy dog"));
    assertEquals(
        Language.ENGLISH,
        detector.detect(
            "Language detection is an interesting problem in natural language processing "
                + "that involves determining which language a given piece of text is written in"));
    assertEquals(
        Language.FRENCH, detector.detect("Le petit chat est assis sur le tapis dans la cuisine"));
    assertEquals(
        Language.FRENCH,
        detector.detect(
            "La langue francaise est une langue romane qui a evolue au fil des siecles "
                + "et qui est maintenant parlee dans de nombreux pays du monde"));
    assertEquals(
        Language.GERMAN,
        detector.detect("Der schnelle braune Fuchs springt ueber den faulen Hund"));
    assertEquals(
        Language.GERMAN,
        detector.detect(
            "Die deutsche Sprache ist eine westgermanische Sprache die vor allem in Deutschland "
                + "und in der Schweiz gesprochen wird"));
    assertEquals(
        Language.SPANISH, detector.detect("El gato esta sentado en la alfombra de la cocina"));
    assertEquals(
        Language.SPANISH,
        detector.detect(
            "El idioma castellano es una lengua romance que se habla en muchos paises del mundo "
                + "y es la lengua oficial de numerosas naciones"));
  }

  @Test
  void detectByAlphabet() {
    assertEquals(Language.GREEK, detector.detect("Γεια σου κόσμε"));
    assertEquals(
        Language.GREEK, detector.detect("Η ελληνική γλώσσα είναι μία από τις αρχαιότερες γλώσσες"));
    assertEquals(Language.RUSSIAN, detector.detect("привет"));
  }

  @Test
  void detectTopwordsInfluence() {
    // One character difference: needs topwords influence to make the decision.
    assertEquals(Language.ENGLISH, detector.detect("Was it Jimmy?"));
    assertEquals(Language.GERMAN, detector.detect("Was ist Jimmy?"));

    // Without topwords, ngrams from foreign word would overwhelm signal.
    assertEquals(Language.ENGLISH, detector.detect("Is it Oberammergau?"));
    assertEquals(Language.GERMAN, detector.detect("wo ist San Francisco"));
    assertEquals(Language.ENGLISH, detector.detect("who is in San Francisco"));
    assertEquals(Language.FRENCH, detector.detect("c'est Jonathan"));
  }

  @Test
  void detectCJK() {
    assertEquals(Language.CHINESE_SIMPLIFIED, detector.detect("今天天气很好，我们去公园散步"));
    assertEquals(Language.CHINESE_TRADITIONAL, detector.detect("今天天氣很好，我們去公園散步"));

    // Kanji only
    assertEquals(Language.JAPANESE, detector.detect("事務所"));
    // All Kana.
    assertEquals(Language.JAPANESE, detector.detect("ひらがなとカタカナと"));
    // Mixed Kanji/kana
    assertEquals(Language.JAPANESE, detector.detect("日本語は日本で使われている言語です。ひらがなとカタカナと漢字を使います"));
    assertEquals(Language.KOREAN, detector.detect("안녕하세요"));
  }

  @Test
  void detectByMixedAlphabet() {
    assertEquals(Language.ENGLISH, detector.detect("He likes to say привет"));
    assertEquals(5, detector.results().scores.numWords);
    assertEquals(18, detector.results().scores.numChars());
    // English is more characters, but Chinese weighted more
    assertEquals(Language.CHINESE_SIMPLIFIED, detector.detect("我的名字是Jonathan"));
    assertEquals(2, detector.results().scores.numWords);
    assertEquals(13, detector.results().scores.numChars());
    // English has higher weight here.
    assertEquals(Language.ENGLISH, detector.detect("I like the characters 羊驼"));
    assertEquals(5, detector.results().scores.numWords);
    assertEquals(20, detector.results().scores.numChars());
  }

  @Test
  void detectSplittingEdgeCases() {
    assertEquals(Language.UNKNOWN, detector.detect(""));
    assertEquals(Language.UNKNOWN, detector.detect("... --- !!!"));
    assertEquals(0, detector.results().scores.numChars());
    assertEquals(0, detector.results().scores.numWords);
    assertEquals(Language.UNKNOWN, detector.detect("http://www/"));
    assertEquals(0, detector.results().scores.numChars());
    assertEquals(0, detector.results().scores.numWords);
    assertEquals(Language.UNKNOWN, detector.detect("12345 67890"));
    assertEquals(0, detector.results().scores.numChars());
    assertEquals(0, detector.results().scores.numWords);
  }

  // ========================================================================
  // Stats populated
  // ========================================================================

  @Test
  void statsPopulatedAfterDetect() {
    detector.detect("This is a simple English sentence for testing purposes");
    Detector.Results stats = detector.results();
    assertNotNull(stats.result);
    assertEquals(Language.ENGLISH, stats.result);
    // Should have some ngram hits for English
    int enIdx = model.langIndex(Language.ENGLISH);
    assertTrue(enIdx >= 0);
    assertTrue(stats.scores.ngramHitsPerLang[enIdx] > 0, "Expected ngram hits for English");
  }

  // ========================================================================
  // Boosts
  // ========================================================================

  @Test
  void buildBoostArray() {
    double[] boosts =
        model.buildBoostArray(java.util.Map.of(Language.ENGLISH, 0.05, Language.FRENCH, 0.03));
    int enIdx = model.langIndex(Language.ENGLISH);
    int frIdx = model.langIndex(Language.FRENCH);
    int deIdx = model.langIndex(Language.GERMAN);
    assertEquals(0.05, boosts[enIdx], 1e-9);
    assertEquals(0.03, boosts[frIdx], 1e-9);
    assertEquals(0.0, boosts[deIdx], 1e-9);
    boosts = model.buildBoostArray(Language.ENGLISH, 0.07);
    assertEquals(0.07, boosts[model.langIndex(Language.ENGLISH)], 1e-9);
  }

  @Test
  void boostCanTipCloseWords() {
    double[] frBoost = model.buildBoostArray(Language.FRENCH, 0.08);
    double[] enBoost = model.buildBoostArray(Language.ENGLISH, 0.13);
    // These words are legitimate in either English or French.
    // Without any other context it is genuinely unclear which language they are.
    for (String word : List.of("message", "table", "menu", "restaurant", "depot", "machine")) {
      assertEquals(Language.FRENCH, detector.detect(word, frBoost));
      assertEquals(Language.ENGLISH, detector.detect(word, enBoost));
    }

    // Chinese that could be either
    double[] zhSimpBoost = model.buildBoostArray(Language.CHINESE_SIMPLIFIED, 0.01);
    double[] zhTradBoost = model.buildBoostArray(Language.CHINESE_TRADITIONAL, 0.01);
    assertEquals(Language.CHINESE_SIMPLIFIED, detector.detect("你是", zhSimpBoost));
    assertEquals(Language.CHINESE_TRADITIONAL, detector.detect("你是", zhTradBoost));

    // As well as a char ("sheep") that is legitimate in all of zh-hans,zh-hant,ja
    detector.detect("羊");
    assertTrue(detector.results().gap < 0.01);
    assertEquals(Language.CHINESE_SIMPLIFIED, detector.detect("羊", zhSimpBoost));
    assertEquals(Language.CHINESE_TRADITIONAL, detector.detect("羊", zhTradBoost));
    double[] jaBoost = model.buildBoostArray(Language.JAPANESE, 0.1);
    assertEquals(Language.JAPANESE, detector.detect("羊", jaBoost));
  }

  @Test
  void testLanguageFromString() {
    // Each language should be lookup-able from its name and iso codes.
    for (Language l : Language.values()) {
      assertEquals(l, Language.fromString(l.name()));
      assertEquals(l, Language.fromString(l.name().toLowerCase()));
      assertEquals(l, Language.fromString(l.name().toUpperCase()));
      assertEquals(l, Language.fromString(l.isoCode()));
      assertEquals(l, Language.fromString(l.isoCode3()));
    }
  }

  @Test
  void apostropheTopwordsSplit() {
    // "l'homme" and "d'histoire" are not in topwords as whole words, but "l'", "homme",
    // "d'", and "histoire" are each French topwords. The apostrophe splitting in scoreWord
    // should produce French topword hits for the split parts.
    detector.detect("l'homme d'histoire");
    Detector.Results stats = detector.results();
    assertEquals(Language.FRENCH, stats.result);
    int frIdx = model.langIndex(Language.FRENCH);
    assertTrue(frIdx >= 0);
    assertTrue(stats.scores.twHitsPerLang[frIdx] > 0);
    assertEquals(4, stats.scores.twNumLookups);
  }

  @Test
  void languageLookupAliases() {
    Pair[] tcases =
        new Pair[] {
          Pair.of(Language.PERSIAN, "persian,farsi,fa"),
          Pair.of(Language.CHINESE_SIMPLIFIED, "chinese,chinese_simplified,zh"),
          Pair.of(Language.JAPANESE, "ja,jp,japanese"),
          Pair.of(Language.MALAY, "malaysian,malay,ms"),
          Pair.of(Language.IRISH, "irish,gaelic,ga,gle"),
          Pair.of(Language.SLOVENIAN, "slovene,slovenian,sl")
        };
    for (Pair<Language, String> tc : tcases) {
      for (String name : tc.second().split(",")) {
        assertEquals(tc.first(), Language.fromString(name));
        assertEquals(tc.first(), Language.fromString(name.toUpperCase()));
      }
    }
  }

  @Test
  void fromCommaSeparatedGroupAliases() {
    // Group aliases should expand to the full set of languages.
    assertEquals(List.copyOf(Language.CJK), Language.fromCommaSeparated("cjk"));
    assertEquals(
        List.copyOf(Language.EUROPE_CYRILLIC), Language.fromCommaSeparated("europe_cyrillic"));
    assertEquals(
        List.copyOf(Language.EUROPE_WEST_COMMON),
        Language.fromCommaSeparated("europe_west_common"));

    // Groups can be mixed with individual languages.
    List<Language> result = Language.fromCommaSeparated("nordic,ja");
    assertTrue(result.containsAll(Language.NORDIC_COMMON));
    assertTrue(result.contains(Language.JAPANESE));
    assertEquals(Language.NORDIC_COMMON.size() + 1, result.size());

    // Duplicate languages from overlapping groups are kept (list, not set).
    List<Language> combo = Language.fromCommaSeparated("efigs,en");
    assertTrue(combo.contains(Language.ENGLISH));
    assertEquals(Language.EFIGS.size() + 1, combo.size());
  }

  @Test
  void chineseAliases() {
    // CHINESE_SIMPLIFIED: primary "zh-hans", iso3 "zho-hans",
    // aliases "chinese,zh,zh-cn,zh-hans-cn,zh-hans-sg"
    for (String alias :
        List.of(
            "zh-hans",
            "zho-hans",
            "chinese",
            "zh",
            "zh-cn",
            "zh-hans-cn",
            "zh-hans-sg",
            "chinese_simplified")) {
      assertEquals(
          Language.CHINESE_SIMPLIFIED,
          Language.fromString(alias),
          "Expected CHINESE_SIMPLIFIED for \"" + alias + "\"");
    }
    // CHINESE_TRADITIONAL: primary "zh-hant", iso3 "zho-hant",
    // aliases "zh-hant-hk,zh-hk,zh-hant-tw"
    for (String alias :
        List.of(
            "zh-hant", "zho-hant", "zh-hant-hk", "zh-hk", "zh-hant-tw", "chinese_traditional")) {
      assertEquals(
          Language.CHINESE_TRADITIONAL,
          Language.fromString(alias),
          "Expected CHINESE_TRADITIONAL for \"" + alias + "\"");
    }
  }

  // ========================================================================
  // CJ totalScores populated
  // ========================================================================

  @Test
  void cjTotalScoresPopulated() {
    // CJ classifier path: simplified Chinese text
    detector.detect("今天天气很好，我们去公园散步");
    Detector.Results r = detector.results();
    assertEquals(Language.CHINESE_SIMPLIFIED, r.result);
    assertTrue(r.gap > 0, "Expected positive gap");
    assertEquals(model.alphabetIndex(Alphabet.HAN), r.predominantAlphaIdx);
    // totalScores should have non-zero entries for CJ languages
    int zhSimpIdx = model.langIndex(Language.CHINESE_SIMPLIFIED);
    int zhTradIdx = model.langIndex(Language.CHINESE_TRADITIONAL);
    int jaIdx = model.langIndex(Language.JAPANESE);
    assertTrue(zhSimpIdx >= 0);
    assertTrue(r.totalScores[zhSimpIdx] != 0, "Expected non-zero totalScore for zh-hans");
    // The winning language should have the highest (least negative) score
    if (zhTradIdx >= 0) {
      assertTrue(r.totalScores[zhSimpIdx] >= r.totalScores[zhTradIdx]);
    }
    if (jaIdx >= 0) {
      assertTrue(r.totalScores[zhSimpIdx] >= r.totalScores[jaIdx]);
    }
    // Non-CJ languages should be zero
    int enIdx = model.langIndex(Language.ENGLISH);
    assertEquals(0.0, r.totalScores[enIdx]);
    int deIdx = model.langIndex(Language.GERMAN);
    assertEquals(0.0, r.totalScores[deIdx]);
  }

  @Test
  void kanaTotalScoresPopulated() {
    // Kana early return: all kana text
    detector.detect("ひらがなとカタカナと");
    Detector.Results r = detector.results();
    assertEquals(Language.JAPANESE, r.result);
    assertEquals(1.0, r.gap, 1e-9);
    assertEquals(model.alphabetIndex(Alphabet.JA_KANA), r.predominantAlphaIdx);
    // totalScores should be 1.0 for Japanese, 0 for everything else
    int jaIdx = model.langIndex(Language.JAPANESE);
    assertTrue(jaIdx >= 0);
    assertEquals(1.0, r.totalScores[jaIdx], 1e-9);
    for (int i = 0; i < r.totalScores.length; i++) {
      if (i != jaIdx) {
        assertEquals(0.0, r.totalScores[i], "Expected 0 for non-Japanese lang idx " + i);
      }
    }
  }

  @Test
  void mixedKanaHanTotalScoresPopulated() {
    // Mixed kanji + kana: kana presence should still give Japanese with totalScores set
    detector.detect("日本語は日本で使われている言語です。ひらがなとカタカナと漢字を使います");
    Detector.Results r = detector.results();
    assertEquals(Language.JAPANESE, r.result);
    assertEquals(1.0, r.gap, 1e-9);
    assertEquals(model.alphabetIndex(Alphabet.JA_KANA), r.predominantAlphaIdx);
    int jaIdx = model.langIndex(Language.JAPANESE);
    assertTrue(jaIdx >= 0);
    assertEquals(1.0, r.totalScores[jaIdx], 1e-9);
    // Non-CJ languages should be zero
    int enIdx = model.langIndex(Language.ENGLISH);
    assertEquals(0.0, r.totalScores[enIdx]);
  }

  // ========================================================================
  // All exit paths: result, gap, totalScores, predominantAlphaIdx
  // ========================================================================

  @Test
  void resultsFullyPopulatedForLatinNgrams() {
    // Normal ngram path (multiple Latin languages compete)
    detector.detect("The quick brown fox jumps over the lazy dog");
    Detector.Results r = detector.results();
    assertEquals(Language.ENGLISH, r.result);
    assertTrue(r.gap > 0, "Expected positive gap");
    assertEquals(model.alphabetIndex(Alphabet.LATIN), r.predominantAlphaIdx);
    int enIdx = model.langIndex(Language.ENGLISH);
    assertTrue(r.totalScores[enIdx] != 0, "Expected non-zero totalScore for English");
  }

  @Test
  void resultsFullyPopulatedForUniqueAlphabet() {
    // Unique-alphabet path (Greek uniquely identifies a language)
    detector.detect("Η ελληνική γλώσσα είναι μία από τις αρχαιότερες γλώσσες");
    Detector.Results r = detector.results();
    assertEquals(Language.GREEK, r.result);
    assertEquals(1.0, r.gap, 1e-9);
    assertEquals(model.alphabetIndex(Alphabet.GREEK), r.predominantAlphaIdx);
    int elIdx = model.langIndex(Language.GREEK);
    assertEquals(1.0, r.totalScores[elIdx], 1e-9);
    // All non-Greek should be zero
    for (int i = 0; i < r.totalScores.length; i++) {
      if (i != elIdx) {
        assertEquals(0.0, r.totalScores[i], "Expected 0 for non-Greek lang idx " + i);
      }
    }
  }

  @Test
  void resultsFullyPopulatedForUnknown() {
    // No alphabet chars path
    detector.detect("... --- !!!");
    Detector.Results r = detector.results();
    assertEquals(Language.UNKNOWN, r.result);
    assertEquals(1.0, r.gap, 1e-9);
    assertEquals(-1, r.predominantAlphaIdx);
    for (int i = 0; i < r.totalScores.length; i++) {
      assertEquals(0.0, r.totalScores[i], "Expected 0 for lang idx " + i);
    }
  }

  @Test
  void moreAlphabetBasedDetection() throws IOException {
    List<Language> languages =
        Language.fromCommaSeparated(
            "en,ja,ar,hy,bn,ka,el,gu,he,hi,pa,si,ta,te,th,am,ml,kn,km,lo,my");
    Model m = Model.loadFromPath(".", languages);
    Detector det = new Detector(m);
    assertEquals(Language.ENGLISH, det.detect("hello"));
    assertEquals(Language.JAPANESE, det.detect("事務所"));
    assertEquals(Language.AMHARIC, det.detect("ሀሎ"));
    assertEquals(Language.ARABIC, det.detect("مرحبًا"));
    assertEquals(Language.ARMENIAN, det.detect("բարև"));
    assertEquals(Language.BENGALI, det.detect("হ্যালো"));
    assertEquals(Language.BURMESE, det.detect("မင်္ဂလာပါ"));
    assertEquals(Language.GEORGIAN, det.detect("გამარჯობა"));
    assertEquals(Language.GREEK, det.detect("Γειά σου"));
    assertEquals(Language.GUJARATI, det.detect("નમસ્તે"));
    assertEquals(Language.HEBREW, det.detect("שלום"));
    assertEquals(Language.HINDI, det.detect("नमस्ते"));
    assertEquals(Language.KANNADA, det.detect("ನಮಸ್ಕಾರ"));
    assertEquals(Language.KHMER, det.detect("សួស្តី"));
    assertEquals(Language.LAO, det.detect("ສະບາຍດີ"));
    assertEquals(Language.MALAYALAM, det.detect("ഹലോ"));
    assertEquals(Language.PUNJABI, det.detect("ਸਤ ਸ੍ਰੀ ਅਕਾਲ"));
    assertEquals(Language.SINHALA, det.detect("ආයුබෝවන්"));
    assertEquals(Language.TAMIL, det.detect("வணக்கம்"));
    assertEquals(Language.TELUGU, det.detect("హలో"));
    assertEquals(Language.THAI, det.detect("สวัสดี"));
  }

  // ========================================================================
  // addText API
  // ========================================================================

  @Test
  void addTextCharSequence() {
    Detector det = new Detector(model);
    det.clearScores();
    det.addText("------ 1232 "); // discarded
    assertEquals(Language.UNKNOWN, det.computeResult());
    det.addText("Le petit chat est assis sur le tapis dans la cuisine");
    assertEquals(Language.FRENCH, det.computeResult());
  }

  @Test
  void addTextCharArray() {
    Detector det = new Detector(model);
    det.clearScores();
    String text = "XXXDer schnelle braune Fuchs springt ueber den faulen HundYYY";
    char[] chars = text.toCharArray();
    det.addText(chars, 3, chars.length - 6);
    assertEquals(Language.GERMAN, det.computeResult(null));
  }

  @Test
  void addTextReader() throws IOException {
    Detector det = new Detector(model);
    det.clearScores();
    det.addText(
        new StringReader(
            "El idioma castellano es una lengua romance que se habla en muchos paises"));
    det.computeResult();
    assertEquals(Language.SPANISH, det.results().result);
  }

  @Test
  void addTextAccumulatesAcrossCalls() {
    Detector det = new Detector(model);
    det.clearScores();
    det.addText("Bonjour");
    det.addText(" le");
    det.addText(" monde");
    det.computeResult();
    assertEquals(Language.FRENCH, det.results().result);
    assertTrue(det.results().scores.numWords > 1);
  }

  @Test
  void addTextMixedOverloads() throws IOException {
    Detector det = new Detector(model);
    det.clearScores();
    det.addText("The quick brown");
    char[] more = " fox jumps over".toCharArray();
    det.addText(more, 0, more.length);
    det.addText(new StringReader(" the lazy dog"));
    det.computeResult();
    assertEquals(Language.ENGLISH, det.results().result);
  }

  @Test
  void addTextClearResets() {
    Detector det = new Detector(model);
    // First: detect French
    det.clearScores();
    det.addText("Le petit chat est assis sur le tapis dans la cuisine");
    det.computeResult();
    assertEquals(Language.FRENCH, det.results().result);
    int firstWords = det.results().scores.numWords;

    // Clear and detect German - should not be contaminated by French
    det.clearScores();
    det.addText("Der schnelle braune Fuchs springt ueber den faulen Hund");
    det.computeResult();
    assertEquals(Language.GERMAN, det.results().result);
    // numWords should reflect only the German text, not accumulated
    assertTrue(det.results().scores.numWords > 0);
    assertTrue(det.results().scores.numWords <= firstWords + 2);
  }

  @Test
  void addTextMatchesDetect() {
    String text = "The quick brown fox jumps over the lazy dog";

    // detect() one-shot
    Detector det1 = new Detector(model);
    Language detectResult = det1.detect(text);

    // addText workflow
    Detector det2 = new Detector(model);
    det2.results().scores.clear();
    det2.addText(text);
    det2.results().computeResult(null);

    assertEquals(detectResult, det2.results().result);
    // Scores should match
    Detector.Results r1 = det1.results();
    Detector.Results r2 = det2.results();
    assertEquals(r1.scores.numWords, r2.scores.numWords);
    assertArrayEquals(r1.totalScores, r2.totalScores, 1e-12);
  }

  @Test
  void addTextWithBoosts() {
    Detector det = new Detector(model);
    double[] frBoost = model.buildBoostArray(Language.FRENCH, 0.08);
    det.clearScores();
    det.addText("message");
    det.results().computeResult(frBoost);
    assertEquals(Language.FRENCH, det.results().result);
  }

  @Test
  void enZhAlphaDetection() throws IOException {
    // Other tests cover English and Japanese, and English and CJK, but not English and Chinese.
    List<Language> languages = Language.fromCommaSeparated("en,zh-hans");
    Model m = Model.loadFromPath(".", languages);
    Detector det = new Detector(m);
    assertEquals(Language.CHINESE_SIMPLIFIED, detector.detect("今天天气很好，我们去公园散步"));
    assertEquals(Language.ENGLISH, detector.detect("Hello everyone"));
  }
}
