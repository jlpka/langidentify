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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Supported languages for detection. Each language has ISO 639-1 and 639-3 codes, optional aliases,
 * and an associated set of {@link Alphabet}s. Languages are grouped by alphabet and alphabetical
 * within each group.
 *
 * <p>Use {@link #fromString(String)} to parse a single language code, or {@link
 * #fromCommaSeparated(String)} to parse a comma-separated list (which also supports group aliases
 * like "cjk" and "europe_west_common").
 */
public enum Language {
  UNKNOWN("", "", "", Collections.emptySet()),

  // LATIN alphabet
  AFRIKAANS("af", "afr", "", Alphabet.LATIN_SET),
  ALBANIAN("sq", "sqi", "", Alphabet.LATIN_SET),
  AZERBAIJANI("az", "aze", "", Alphabet.LATIN_SET),
  BASQUE("eu", "eus", "", Alphabet.LATIN_SET),
  CATALAN("ca", "cat", "", Alphabet.LATIN_SET),
  CROATIAN("hr", "hrv", "", Alphabet.LATIN_SET),
  CZECH("cs", "ces", "", Alphabet.LATIN_SET),
  DANISH("da", "dan", "", Alphabet.LATIN_SET),
  DUTCH("nl", "nld", "", Alphabet.LATIN_SET),
  ENGLISH("en", "eng", "", Alphabet.LATIN_SET),
  ESPERANTO("eo", "epo", "", Alphabet.LATIN_SET),
  ESTONIAN("et", "est", "", Alphabet.LATIN_SET),
  FINNISH("fi", "fin", "", Alphabet.LATIN_SET),
  FRENCH("fr", "fra", "", Alphabet.LATIN_SET),
  GANDA("lg", "lug", "", Alphabet.LATIN_SET),
  GERMAN("de", "deu", "", Alphabet.LATIN_SET),
  HUNGARIAN("hu", "hun", "", Alphabet.LATIN_SET),
  ICELANDIC("is", "isl", "", Alphabet.LATIN_SET),
  INDONESIAN("id", "ind", "", Alphabet.LATIN_SET),
  ITALIAN("it", "ita", "", Alphabet.LATIN_SET),
  IRISH("ga", "gle", "gaelic", Alphabet.LATIN_SET),
  LATIN("la", "lat", "", Alphabet.LATIN_SET),
  LATVIAN("lv", "lav", "", Alphabet.LATIN_SET),
  LITHUANIAN("lt", "lit", "", Alphabet.LATIN_SET),
  LUXEMBOURGISH("lb", "ltz", "", Alphabet.LATIN_SET),
  MALAY("ms", "msa", "malaysian", Alphabet.LATIN_SET),
  MAORI("mi", "mri", "", Alphabet.LATIN_SET),
  // Norwegian: We have both no/nb for Norwegian Bokmal dialect, nn for Norwegian Nynorsk dialect.
  // If you just want a single NORWEGIAN cluster, use "no", which is 4x the corpus size.
  // They're close enough that they sometimes cross-detect.
  NORWEGIAN("no", "nor", "bokmal,nb", Alphabet.LATIN_SET),
  NYNORSK("nn", "nno", "", Alphabet.LATIN_SET),
  OROMO("om", "orm", "", Alphabet.LATIN_SET),
  POLISH("pl", "pol", "", Alphabet.LATIN_SET),
  PORTUGUESE("pt", "por", "", Alphabet.LATIN_SET),
  ROMANIAN("ro", "ron", "", Alphabet.LATIN_SET),
  SHONA("sn", "sna", "", Alphabet.LATIN_SET),
  SLOVAK("sk", "slk", "", Alphabet.LATIN_SET),
  SLOVENIAN("sl", "slv", "slovene", Alphabet.LATIN_SET),
  SOMALI("so", "som", "", Alphabet.LATIN_SET),
  SOTHO("st", "sot", "", Alphabet.LATIN_SET),
  SPANISH("es", "spa", "", Alphabet.LATIN_SET),
  SWAHILI("sw", "swa", "", Alphabet.LATIN_SET),
  SWEDISH("sv", "swe", "", Alphabet.LATIN_SET),
  TAGALOG("tl", "tgl", "", Alphabet.LATIN_SET),
  TSONGA("ts", "tso", "", Alphabet.LATIN_SET),
  TSWANA("tn", "tsn", "", Alphabet.LATIN_SET),
  TURKISH("tr", "tur", "", Alphabet.LATIN_SET),
  VIETNAMESE("vi", "vie", "", Alphabet.LATIN_SET),
  WELSH("cy", "cym", "", Alphabet.LATIN_SET),
  XHOSA("xh", "xho", "", Alphabet.LATIN_SET),
  YORUBA("yo", "yor", "", Alphabet.LATIN_SET),
  ZULU("zu", "zul", "", Alphabet.LATIN_SET),

  // CJK
  CHINESE_SIMPLIFIED(
      "zh-hans",
      "zho-hans",
      "chinese,zh,zh-cn,zh-hans-cn,zh-hans-sg",
      Collections.singleton(Alphabet.HAN)),
  CHINESE_TRADITIONAL(
      "zh-hant",
      "zho-hant",
      "zh-hant-hk,zh-hk,zh-hant-tw,zh-tw",
      Collections.singleton(Alphabet.HAN)),
  JAPANESE("ja", "jpn", "jp", EnumSet.of(Alphabet.HAN, Alphabet.JA_KANA)),
  KOREAN("ko", "kor", "", Collections.singleton(Alphabet.HANGUL)),

  // ARABIC
  ARABIC("ar", "ara", "", Alphabet.ARABIC_SET),
  PASHTO("ps", "pus", "", Alphabet.ARABIC_SET),
  PERSIAN("fa", "fas", "farsi", Alphabet.ARABIC_SET),
  URDU("ur", "urd", "", Alphabet.ARABIC_SET),

  // CYRILLIC
  BELARUSIAN("be", "bel", "", Alphabet.CYRILLIC_SET),
  BULGARIAN("bg", "bul", "", Alphabet.CYRILLIC_SET),
  MACEDONIAN("mk", "mkd", "", Alphabet.CYRILLIC_SET),
  MONGOLIAN("mn", "mon", "", Alphabet.CYRILLIC_SET),
  RUSSIAN("ru", "rus", "", Alphabet.CYRILLIC_SET),
  SERBIAN("sr", "srp", "", Alphabet.CYRILLIC_SET),
  UKRAINIAN("uk", "ukr", "", Alphabet.CYRILLIC_SET),

  // ETHIOPIC
  AMHARIC("am", "amh", "", Collections.singleton(Alphabet.ETHIOPIC)),
  TIGRINYA("ti", "tig", "", Collections.singleton(Alphabet.ETHIOPIC)),

  // Singleton alphabets
  ARMENIAN("hy", "hye", "", Collections.singleton(Alphabet.ARMENIAN)),
  BENGALI("bn", "ben", "", Collections.singleton(Alphabet.BENGALI)),
  BURMESE("my", "mya", "myanmar", Collections.singleton(Alphabet.MYANMAR)),
  GEORGIAN("ka", "kat", "", Collections.singleton(Alphabet.GEORGIAN)),
  GREEK("el", "ell", "", Collections.singleton(Alphabet.GREEK)),
  GUJARATI("gu", "guj", "", Collections.singleton(Alphabet.GUJARATI)),
  HEBREW("he", "heb", "", Collections.singleton(Alphabet.HEBREW)),
  HINDI("hi", "hin", "", Collections.singleton(Alphabet.DEVANAGARI)),
  KANNADA("kn", "kan", "", Collections.singleton(Alphabet.KANNADA)),
  KHMER("km", "khm", "", Collections.singleton(Alphabet.KHMER)),
  LAO("lo", "lao", "", Collections.singleton(Alphabet.LAO)),
  MALAYALAM("ml", "mal", "", Collections.singleton(Alphabet.MALAYALAM)),
  PUNJABI("pa", "pan", "", Collections.singleton(Alphabet.GURMUKHI)),
  SINHALA("si", "sin", "", Collections.singleton(Alphabet.SINHALA)),
  TAMIL("ta", "tam", "", Collections.singleton(Alphabet.TAMIL)),
  TELUGU("te", "tel", "", Collections.singleton(Alphabet.TELUGU)),
  THAI("th", "tha", "", Collections.singleton(Alphabet.THAI));

  private final String isoCode;
  private final String isoCode3;
  private final List<String> altNames;
  private final Set<Alphabet> alphabets;

  Language(String isoCode, String isoCode3, String altNames, Set<Alphabet> alphabets) {
    this.isoCode = isoCode;
    this.isoCode3 = isoCode3;
    this.altNames =
        altNames.isEmpty() ? Collections.emptyList() : Arrays.asList(altNames.split(","));
    this.alphabets = alphabets;
  }

  /** Returns the ISO 639-1 code (e.g. "en", "fr"), or empty string for {@code UNKNOWN}. */
  public String isoCode() {
    return isoCode;
  }

  /** Returns the ISO 639-3 code (e.g. "eng", "fra"), or empty string for {@code UNKNOWN}. */
  public String isoCode3() {
    return isoCode3;
  }

  /** Returns the set of alphabets (writing systems) used by this language. */
  public Set<Alphabet> getAlphabets() {
    return alphabets;
  }

  /** Returns alternative names and aliases accepted by {@link #fromString}. */
  public List<String> altNames() {
    return altNames;
  }

  /**
   * Parses a language name. Accepts the enum name, ISO 639-1/639-3 codes, and aliases. The lookup
   * is case-insensitive. E.g. "English", "en", or "eng" all return {@code ENGLISH}. Returns {@code
   * UNKNOWN} if the input is unrecognized rather than throwing.
   */
  public static Language fromString(String s) {
    if (s == null) return UNKNOWN;
    return BY_NAME.getOrDefault(s.toLowerCase(), UNKNOWN);
  }

  /**
   * Parses a comma-separated string of language codes into a list. E.g. "en,fr,de" -> [ENGLISH,
   * FRENCH, GERMAN]. Also supports group aliases such as "cjk" or "europe_west_common".
   *
   * @throws IllegalArgumentException if any code is unrecognized
   */
  public static List<Language> fromCommaSeparated(String s) {
    if (s == null || s.isEmpty()) return Collections.emptyList();
    List<Language> result = new ArrayList<>();
    for (String code : s.split(",")) {
      code = code.trim().toLowerCase();
      Language lang = fromString(code);
      if (lang != UNKNOWN) {
        result.add(lang);
      } else {
        Set<Language> group = GROUP_ALIASES.get(code);
        if (group != null) {
          result.addAll(group);
        } else {
          throw new IllegalArgumentException("Could not parse " + code);
        }
      }
    }
    return result;
  }

  private static final Language[] VALUES = values();

  public static Set<Language> EFIGS =
      Collections.unmodifiableSet(EnumSet.of(ENGLISH, FRENCH, ITALIAN, GERMAN, SPANISH));
  public static Set<Language> EFIGSNP =
      Collections.unmodifiableSet(
          EnumSet.of(ENGLISH, FRENCH, ITALIAN, GERMAN, SPANISH, DUTCH, PORTUGUESE));
  public static Set<Language> NORDIC_COMMON =
      Collections.unmodifiableSet(EnumSet.of(DANISH, SWEDISH, NORWEGIAN, FINNISH));
  public static Set<Language> EUROPE_WEST_COMMON = union(EFIGSNP, NORDIC_COMMON);
  public static Set<Language> EUROPE_LATIN =
      Collections.unmodifiableSet(
          EnumSet.of(
              ALBANIAN,
              BASQUE,
              CATALAN,
              CROATIAN,
              CZECH,
              DANISH,
              DUTCH,
              ENGLISH,
              ESTONIAN,
              FINNISH,
              FRENCH,
              GERMAN,
              HUNGARIAN,
              ICELANDIC,
              ITALIAN,
              IRISH,
              LATVIAN,
              LITHUANIAN,
              LUXEMBOURGISH,
              NORWEGIAN,
              NYNORSK,
              POLISH,
              PORTUGUESE,
              ROMANIAN,
              SLOVAK,
              SLOVENIAN,
              SPANISH,
              SWEDISH,
              TURKISH,
              WELSH));
  public static Set<Language> EUROPE_EAST_LATIN =
      Collections.unmodifiableSet(
          EnumSet.of(
              ALBANIAN,
              CROATIAN,
              CZECH,
              ESTONIAN,
              HUNGARIAN,
              LATVIAN,
              LITHUANIAN,
              POLISH,
              ROMANIAN,
              SLOVAK,
              SLOVENIAN));
  public static Set<Language> EUROPE_CYRILLIC =
      Collections.unmodifiableSet(
          EnumSet.of(BELARUSIAN, BULGARIAN, MACEDONIAN, RUSSIAN, SERBIAN, UKRAINIAN));
  public static Set<Language> EUROPE = union(EUROPE_LATIN, EUROPE_CYRILLIC);
  public static Set<Language> EUROPE_COMMON =
      union(EUROPE_WEST_COMMON, union(EUROPE_EAST_LATIN, EUROPE_CYRILLIC));
  public static Set<Language> CHINESE_SCRIPTS =
      Collections.unmodifiableSet(EnumSet.of(CHINESE_SIMPLIFIED, CHINESE_TRADITIONAL));
  public static Set<Language> CJ =
      Collections.unmodifiableSet(EnumSet.of(CHINESE_SIMPLIFIED, CHINESE_TRADITIONAL, JAPANESE));
  public static Set<Language> CJK =
      Collections.unmodifiableSet(
          EnumSet.of(CHINESE_SIMPLIFIED, CHINESE_TRADITIONAL, JAPANESE, KOREAN));

  public static Set<Language> LATIN_ALPHABET = languagesForAlphabet(Alphabet.LATIN);
  public static Set<Language> CYRILLIC_ALPHABET = languagesForAlphabet(Alphabet.CYRILLIC);
  public static Set<Language> ARABIC_ALPHABET = languagesForAlphabet(Alphabet.ARABIC);
  public static Set<Language> ALL = allLanguages();

  private static Set<Language> allLanguages() {
    EnumSet<Language> all = EnumSet.allOf(Language.class);
    all.remove(UNKNOWN);
    return Collections.unmodifiableSet(all);
  }

  private static final Map<String, Set<Language>> GROUP_ALIASES =
      Map.ofEntries(
          Map.entry("all", ALL),
          Map.entry("cj", CJ),
          Map.entry("cjk", CJK),
          Map.entry("efigs", EFIGS),
          Map.entry("efigsnp", EFIGSNP),
          Map.entry("nordic", NORDIC_COMMON),
          Map.entry("europe_west_common", EUROPE_WEST_COMMON),
          Map.entry("europe_common", EUROPE_COMMON),
          Map.entry("europe_east_latin", EUROPE_EAST_LATIN),
          Map.entry("europe_latin", EUROPE_LATIN),
          Map.entry("europe_cyrillic", EUROPE_CYRILLIC),
          Map.entry("europe", EUROPE),
          Map.entry("latin_alphabet", LATIN_ALPHABET),
          Map.entry("cyrillic_alphabet", CYRILLIC_ALPHABET),
          Map.entry("arabic_alphabet", ARABIC_ALPHABET));

  /** Returns {@code true} if this is {@code CHINESE_SIMPLIFIED} or {@code CHINESE_TRADITIONAL}. */
  public boolean isChinese() {
    return this == CHINESE_SIMPLIFIED || this == CHINESE_TRADITIONAL;
  }

  private static final Map<String, Language> BY_NAME;

  static {
    Map<String, Language> map = new HashMap<>();
    for (Language lang : VALUES) {
      map.put(lang.name().toLowerCase(), lang);
      if (!lang.isoCode.isEmpty()) {
        map.put(lang.isoCode, lang);
      }
      if (!lang.isoCode3.isEmpty()) {
        map.put(lang.isoCode3, lang);
      }
      for (String name : lang.altNames()) {
        map.put(name, lang);
      }
    }
    BY_NAME = map;
  }

  private static Set<Language> union(Set<Language> a, Set<Language> b) {
    EnumSet<Language> result = EnumSet.copyOf(a);
    result.addAll(b);
    return Collections.unmodifiableSet(result);
  }

  /** Returns the set of languages whose alphabet set contains the given alphabet. */
  private static Set<Language> languagesForAlphabet(Alphabet alpha) {
    EnumSet<Language> result = EnumSet.noneOf(Language.class);
    for (Language lang : VALUES) {
      if (lang.getAlphabets().contains(alpha)) {
        result.add(lang);
      }
    }
    return Collections.unmodifiableSet(result);
  }
}
