package com.jlpka.langidentify;

import com.jlpka.cjclassifier.CJClassifier;
import java.io.IOException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Immutable model containing ngram tables for a set of languages.
 *
 * <p>Usage:
 *
 * <pre>
 *   // Load from filesystem:
 *   Model model = Model.loadFromPath("/path/to/ngrams", Language.fromCommaSeparated("en,fr,de"));
 *
 *   // Load from classpath (requires langidentify-models-lite or langidentify-models-full dependency):
 *   Model model = Model.load(Language.fromCommaSeparated("en,fr,de"));
 *
 *   Detector detector = new Detector(model);
 *   Language lang = detector.detect("Bonjour le monde");
 * </pre>
 *
 * <p>Model is relatively heavyweight to load the first time, since it needs to load the model from
 * disk or classpath, but the results are cached as a static singleton.
 */
public class Model {
  private final NgramTable[] tables; // index 0..maxNgram-1 for ngram sizes 1..maxNgram
  private final Language[] languages;
  private final EnumMap<Language, Integer> langIndexMap;
  private final double minLogProb;
  private final double twMinLogProb;
  private final int maxNgram;
  private final Alphabet[] alphabets; // local index -> Alphabet
  private final EnumMap<Alphabet, Integer> alphabetIndexMap; // Alphabet -> local index
  private final int[][] alphabetToLangIndices; // alphaIdx -> langIdx[]
  private final boolean[] alphabetImpliesOneLanguage; // alphaIdx -> true if maps to exactly 1 lang
  private final boolean[] cjAlphabet; // alphaIdx -> true if HAN or JA_KANA
  private final CJClassifier cjClassifier; // null if no CJ disambiguation needed
  private final NgramTable topwordsTable; // whole-word table loaded from topwords files

  Model(
      NgramTable[] tables,
      Language[] languages,
      double minLogProb,
      double twMinLogProb,
      int maxNgram,
      CJClassifier cjClassifier,
      NgramTable topwordsTable) {
    this.tables = tables;
    this.languages = languages;
    this.minLogProb = minLogProb;
    this.twMinLogProb = twMinLogProb;
    this.maxNgram = maxNgram;
    this.langIndexMap = new EnumMap<>(Language.class);
    for (int i = 0; i < languages.length; i++) {
      langIndexMap.put(languages[i], i);
    }
    // Collect union of alphabets from all languages (EnumSet gives declaration order)
    EnumSet<Alphabet> alphaSet = EnumSet.noneOf(Alphabet.class);
    for (Language lang : languages) {
      alphaSet.addAll(lang.getAlphabets());
    }
    this.alphabets = alphaSet.toArray(new Alphabet[0]);
    this.alphabetIndexMap = new EnumMap<>(Alphabet.class);
    for (int i = 0; i < alphabets.length; i++) {
      alphabetIndexMap.put(alphabets[i], i);
    }

    // Build alphabet -> language index mapping
    this.alphabetToLangIndices = new int[alphabets.length][];
    this.alphabetImpliesOneLanguage = new boolean[alphabets.length];
    for (int ai = 0; ai < alphabets.length; ai++) {
      Alphabet alpha = alphabets[ai];
      int count = 0;
      for (Language lang : languages) {
        if (lang.getAlphabets().contains(alpha)) count++;
      }
      int[] langIndices = new int[count];
      int idx = 0;
      for (int li = 0; li < languages.length; li++) {
        if (languages[li].getAlphabets().contains(alpha)) {
          langIndices[idx++] = li;
        }
      }
      alphabetToLangIndices[ai] = langIndices;
      alphabetImpliesOneLanguage[ai] = (count == 1);
    }

    // Mark which alphabets belong to the CJ group (for CJClassifier routing)
    this.cjAlphabet = new boolean[alphabets.length];
    for (int ai = 0; ai < alphabets.length; ai++) {
      cjAlphabet[ai] = alphabets[ai] == Alphabet.HAN || alphabets[ai] == Alphabet.JA_KANA;
    }

    this.cjClassifier = cjClassifier;
    this.topwordsTable = topwordsTable;
  }

  // ========================================================================
  // Factory methods (delegate to ModelLoader)
  // ========================================================================

  /**
   * Loads (or returns cached) a Model with default thresholds derived from the model files.
   *
   * @param diskPrefix directory or path prefix containing ngrams and topwords files
   * @param languages the languages to load
   * @return the loaded Model
   * @throws IOException if model files cannot be read
   */
  public static Model loadFromPath(String diskPrefix, List<Language> languages) throws IOException {
    return ModelLoader.loadFromPath(diskPrefix, languages);
  }

  /**
   * Loads (or returns cached) a Model for the given prefix (directory) and language set.
   *
   * @param prefix prefix containing ngrams-XX.txt.gz and topwords-XX.txt.gz files
   * @param languages the languages to load
   * @param minLogProb minimum log-probability; ngrams below this threshold are discarded
   * @param twMinLogProb minimum log-probability for topwords; words below are discarded. Use {@code
   *     Double.NaN} to skip loading topwords entirely.
   * @return the loaded Model
   * @throws IOException if model files cannot be read
   */
  public static Model loadFromPath(
      String prefix, List<Language> languages, double minLogProb, double twMinLogProb)
      throws IOException {
    return ModelLoader.loadFromPath(prefix, languages, minLogProb, twMinLogProb, 0);
  }

  /**
   * Loads (or returns cached) a Model for the given prefix (directory) and language set.
   *
   * @param prefix prefix containing ngrams-XX.txt.gz and topwords-XX.txt.gz files
   * @param languages the languages to load
   * @param minLogProb minimum log-probability; ngrams below this threshold are discarded
   * @param twMinLogProb minimum log-probability for topwords; words below are discarded. Use {@code
   *     Double.NaN} to skip loading topwords entirely.
   * @param cjMinLogProb minimum log-probability floor for CJClassifier; 0 means use the file's
   *     MinLogProb value
   * @return the loaded Model
   * @throws IOException if model files cannot be read
   */
  public static Model loadFromPath(
      String prefix,
      List<Language> languages,
      double minLogProb,
      double twMinLogProb,
      double cjMinLogProb)
      throws IOException {
    return ModelLoader.loadFromPath(prefix, languages, minLogProb, twMinLogProb, cjMinLogProb);
  }

  /**
   * Loads a Model from the classpath, auto-discovering the variant. Tries "full" first, then
   * "lite". Requires a {@code langidentify-models-lite} or {@code langidentify-models-full}
   * dependency.
   *
   * @param languages the languages to load
   * @return the loaded Model
   */
  public static Model load(List<Language> languages) throws IOException {
    return ModelLoader.load(languages);
  }

  /**
   * Loads a Model from the classpath, forcing the "lite" (lower memory) resolution. If only the
   * full model is available on the classpath, reads it with lite thresholds (-12/-12).
   *
   * @param languages the languages to load
   * @return the loaded Model
   * @throws IOException if model files cannot be read
   */
  public static Model loadLite(List<Language> languages) throws IOException {
    return ModelLoader.loadLite(languages);
  }

  /**
   * Loads a Model from the classpath, forcing the "full" (higher accuracy) resolution.
   *
   * @param languages the languages to load
   * @return the loaded Model
   * @throws IOException if model files cannot be read
   */
  public static Model loadFull(List<Language> languages) throws IOException {
    return ModelLoader.loadFull(languages);
  }

  /**
   * Loads a Model from the classpath using a named variant ("lite" or "full").
   *
   * @param variant model variant name
   * @param languages the languages to load
   * @return the loaded Model
   */
  public static Model load(String variant, List<Language> languages) throws IOException {
    return ModelLoader.load(variant, languages);
  }

  /**
   * Loads a Model from the classpath with tuning parameters.
   *
   * @param variant model variant name ("lite" or "full")
   * @param languages the languages to load
   * @param minLogProb minimum log-probability; ngrams below this threshold are discarded
   * @param twMinLogProb minimum log-probability for topwords; words below are discarded. Use {@code
   *     Double.NaN} to skip loading topwords entirely.
   * @return the loaded Model
   * @throws IOException if model files cannot be read
   */
  public static Model load(
      String variant, List<Language> languages, double minLogProb, double twMinLogProb)
      throws IOException {
    return ModelLoader.load(variant, languages, minLogProb, twMinLogProb, 0);
  }

  /** Clears the model cache, forcing the next load to re-read from disk/classpath. */
  public static void clearCache() {
    ModelLoader.clearCache();
  }

  // ========================================================================
  // Accessors
  // ========================================================================

  /** Returns the NgramTable for the given ngram size (1..maxNgram). */
  public NgramTable getTable(int ngramSize) {
    return tables[ngramSize - 1];
  }

  /** Returns the ordered array of languages in this model. */
  public Language[] getLanguages() {
    return languages;
  }

  /** Returns the number of languages in this model. */
  public int numLanguages() {
    return languages.length;
  }

  /** Maps a language to its column index, or -1 if not in the model. */
  public int langIndex(Language lang) {
    Integer idx = langIndexMap.get(lang);
    return idx != null ? idx : -1;
  }

  /** Returns the minimum log-probability floor used when loading this model. */
  public double getMinLogProb() {
    return minLogProb;
  }

  /** Returns the maximum ngram size loaded in this model. */
  public int getMaxNgram() {
    return maxNgram;
  }

  /** Returns the ordered array of active alphabets in this model. */
  public Alphabet[] getAlphabets() {
    return alphabets;
  }

  /** Returns the number of active alphabets in this model. */
  public int numAlphabets() {
    return alphabets.length;
  }

  /** Maps an alphabet to its local index, or -1 if not in the model. */
  public int alphabetIndex(Alphabet alpha) {
    Integer idx = alphabetIndexMap.get(alpha);
    return idx != null ? idx : -1;
  }

  /** Returns the language indices associated with a given alphabet index. */
  public int[] langIndicesForAlphabet(int alphaIdx) {
    return alphabetToLangIndices[alphaIdx];
  }

  /** Returns true if this alphabet index maps to exactly one language. */
  public boolean alphabetImpliesOneLanguage(int alphaIdx) {
    return alphabetImpliesOneLanguage[alphaIdx];
  }

  /**
   * For a unique alphabet, returns the single language it maps to. Caller must ensure
   * alphabetImpliesOneLanguage(alphaIdx) is true.
   */
  public Language uniqueLanguageForAlphabet(int alphaIdx) {
    return languages[alphabetToLangIndices[alphaIdx][0]];
  }

  /** Returns true if the alphabet at this index is part of the CJ group (HAN or JA_KANA). */
  public boolean isCJAlphabet(int alphaIdx) {
    return cjAlphabet[alphaIdx];
  }

  /** Returns the CJClassifier for HAN disambiguation, or null. */
  public CJClassifier getCJClassifier() {
    return cjClassifier;
  }

  /** Returns the topwords table, or null if no topwords files were found. */
  public NgramTable getTopwordsTable() {
    return topwordsTable;
  }

  /** Returns the minimum log-probability floor for topwords. */
  public double getTwMinLogProb() {
    return twMinLogProb;
  }

  /**
   * Builds a boost array for {@link Detector}. Boosts bias detection toward expected languages,
   * which is useful for short or ambiguous text where ngram scores alone may not discriminate (e.g.
   * "message" or "table" score equally in French and English). An external signal such as an HTTP
   * Accept-Language header or user locale can tip the balance.
   *
   * <p>Each value is a fraction of the language's absolute score to add as a bonus. Values are
   * typically in the range 0.0..1.0; a boost of 0.2 adds 20% of that language's score.
   */
  public double[] buildBoostArray(Map<Language, Double> boostMap) {
    double[] boosts = new double[languages.length];
    for (Map.Entry<Language, Double> entry : boostMap.entrySet()) {
      int li = langIndex(entry.getKey());
      if (li >= 0) {
        boosts[li] = entry.getValue();
      }
    }
    return boosts;
  }

  /** Convenience overload for boosting a single language. See {@link #buildBoostArray(Map)}. */
  public double[] buildBoostArray(Language lang, double boost) {
    double[] boosts = new double[languages.length];
    int li = langIndex(lang);
    if (li >= 0) {
      boosts[li] = boost;
    }
    return boosts;
  }

  /** Convenience overload for boosting two languages. See {@link #buildBoostArray(Map)}. */
  public double[] buildBoostArray(Language lang1, double boost1, Language lang2, double boost2) {
    double[] boosts = new double[languages.length];
    int li = langIndex(lang1);
    if (li >= 0) {
      boosts[li] = boost1;
    }
    li = langIndex(lang2);
    if (li >= 0) {
      boosts[li] = boost2;
    }
    return boosts;
  }
}
