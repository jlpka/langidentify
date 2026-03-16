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

import com.jlpka.cjclassifier.CJClassifier;
import com.jlpka.cjclassifier.CJLanguage;
import java.io.IOException;
import java.io.Reader;
import java.util.Arrays;

/**
 * Detects the language of text using n-gram and topword scoring against a loaded {@link Model}.
 *
 * <p>Detector is inexpensive to construct and intentionally not thread-safe. For concurrent
 * detection, use a separate instance per thread (e.g. via {@link ThreadLocal}).
 *
 * <p>{@link Model} is heavyweight to load the first time (disk I/O), but results are cached as a
 * static singleton.
 *
 * <pre>
 *   List&lt;Language&gt; languages = Language.fromCommaSeparated("en,fr,de");
 *   Model model = Model.loadLite(languages);
 *   Detector detector = new Detector(model);
 *   Language lang = detector.detect("Bonjour le monde");
 * </pre>
 *
 * <p>Optional features:
 *
 * <ul>
 *   <li><b>Boosts</b> — bias scoring toward expected languages when context is available (e.g. HTTP
 *       Accept-Language header or user locale). Build via {@link Model#buildBoostArray}.
 *   <li><b>Accuracy params</b> — trade off CPU cost against accuracy by adjusting the n-gram range
 *       evaluated per word. See {@link #setAccuracyParams}.
 * </ul>
 */
public class Detector {

  private final Model model;
  private int minNgram;
  private int stopIfNgramCovered;
  private int maxNgram;
  private boolean useTopwords;
  private final Results results;
  private final WordSegmenter segmenter;
  private final NgramTable.Ngram lookupKey; // points at segmenter.wordBuf

  /**
   * Creates a detector.
   *
   * @param model the loaded ngram model
   */
  public Detector(Model model) {
    this.model = model;
    // Typically, minNgram is 1, maxNgram is 5, and we stop covering ngrams lower than 3 if all the
    // 3-grams are covered.
    this.minNgram = 1;
    this.stopIfNgramCovered = Math.min(3, model.getMaxNgram());
    this.maxNgram = model.getMaxNgram();
    this.useTopwords = true;
    this.segmenter = makeWordSegmenter(model);
    this.lookupKey = new NgramTable.Ngram(segmenter.wordBuf, 0, 0);
    this.results = new Results(model);
  }

  /**
   * Adjusts the n-gram evaluation range and topword usage to trade off CPU cost against accuracy.
   * The defaults (minNgram=1, stopIfNgramCovered=3, maxNgram=model max, useTopwords=true) are
   * reasonable for most use cases.
   *
   * <p>It's mostly not necessary to fiddle with this.
   *
   * @param minNgram smallest n-gram size to evaluate (must be &ge; 1)
   * @param stopIfNgramCovered skip smaller n-grams when all tiles of this size are found for a
   *     word, reducing lookups at slight accuracy cost
   * @param maxNgram largest n-gram size to evaluate (must be &le; model's max)
   * @param useTopwords whether to include topword scoring
   * @return {@code true} if the parameters are valid and were applied, {@code false} otherwise
   */
  public boolean setAccuracyParams(
      int minNgram, int stopIfNgramCovered, int maxNgram, boolean useTopwords) {
    if (minNgram < 1
        || maxNgram > model.getMaxNgram()
        || minNgram > maxNgram
        || stopIfNgramCovered < minNgram
        || stopIfNgramCovered > maxNgram) {
      return false;
    }
    this.minNgram = minNgram;
    this.stopIfNgramCovered = stopIfNgramCovered;
    this.maxNgram = maxNgram;
    this.useTopwords = useTopwords;
    return true;
  }

  // ========================================================================
  // Detection
  // ========================================================================

  /**
   * Detects the language of the given text. Clears results, processes text, computes result. After
   * this call, {@link #results()} contains the detection details.
   *
   * @param text the text to detect
   * @return the detected language, or {@link Language#UNKNOWN} if detection fails
   */
  public Language detect(CharSequence text) {
    results.scores.clear();
    segmenter.segment(text);
    results.computeResult(null);
    return results.result;
  }

  /**
   * Detects the language with boosts applied during scoring. Boosts bias detection toward expected
   * languages, useful for short or ambiguous phrases. Build via {@link Model#buildBoostArray}.
   *
   * @param text the text to detect
   * @param boosts per-language boost array, or {@code null} for no boosts
   * @return the detected language, or {@link Language#UNKNOWN} if detection fails
   */
  public Language detect(CharSequence text, double[] boosts) {
    results.scores.clear();
    segmenter.segment(text);
    results.computeResult(boosts);
    return results.result;
  }

  // ========================================================================
  // Alternate detection api that allows a piecemeal sequence, or use of
  // non-CharSequence like Reader or char[].
  //
  // The pattern here is more like
  //   detector.clearScores();
  //   detector.addText(text1);
  //   detector.addText(text2);...
  //   detector.computeResult();
  // ========================================================================

  /** Clears the scores in the results, for use with the {@link #addText} API. */
  public void clearScores() {
    results.scores.clear();
  }

  /** Adds text to the current detection accumulator without clearing or computing a result. */
  public void addText(CharSequence text) {
    segmenter.segment(text);
  }

  /**
   * Adds text from a char array subsection to the detection accumulator.
   *
   * @param text the character array containing the text
   * @param ofs the start offset within {@code text}
   * @param len the number of characters to process
   */
  public void addText(char[] text, int ofs, int len) {
    segmenter.segment(text, ofs, len);
  }

  /**
   * Adds text from a {@link Reader} to the detection accumulator.
   *
   * @param reader the reader to consume text from
   * @throws IOException if the reader throws
   */
  public void addText(Reader reader) throws IOException {
    segmenter.segment(reader);
  }

  /**
   * Computes the result from all text added since the last {@link #clearScores} call.
   *
   * @return the detected language, or {@link Language#UNKNOWN} if detection fails
   */
  public Language computeResult() {
    results.computeResult(null);
    return results.result;
  }

  /**
   * Computes the result with boosts applied during scoring. Build the boost array via {@link
   * Model#buildBoostArray}.
   *
   * @param boosts per-language boost array, or {@code null} for no boosts
   * @return the detected language, or {@link Language#UNKNOWN} if detection fails
   */
  public Language computeResult(double[] boosts) {
    results.computeResult(boosts);
    return results.result;
  }

  // ========================================================================
  // Scores class: accumulated during word scoring
  // ========================================================================

  /** Accumulates per-language scoring data during word-level ngram and topword lookups. */
  public static class Scores {
    // Ngrams Signal
    // -------------
    /** Per-language raw ngram scores (sum of log-probs from matched ngrams). */
    public final double[] ngramScores;

    /** Per-language count of matched ngram tiles. */
    public final int[] ngramHitsPerLang;

    /** Per-language topword scores (sum of log-probs from matched topwords). */
    public final double[] twScores;

    /** Per-language count of matched topwords. */
    public final int[] twHitsPerLang;

    /** Total number of topword lookups attempted. */
    public int twNumLookups;

    // Alphabets
    // ---------
    /** Per-alphabet character counts, indexed by model's local alphabet number. */
    public final int[] alphabetCounts;

    /** Number of words processed. */
    public int numWords;

    /** CJClassifier scores, or null if no CJClassifier in model. */
    public final CJClassifier.Scores cjScores;

    Scores(Model model) {
      int numLangs = model.numLanguages();
      this.ngramScores = new double[numLangs];
      this.ngramHitsPerLang = new int[numLangs];
      this.twScores = new double[numLangs];
      this.twHitsPerLang = new int[numLangs];
      this.alphabetCounts = new int[model.numAlphabets()];
      this.cjScores = model.getCJClassifier() != null ? new CJClassifier.Scores() : null;
    }

    /** Resets all accumulated scores and counters to zero. */
    public void clear() {
      numWords = 0;
      Arrays.fill(ngramScores, 0);
      Arrays.fill(ngramHitsPerLang, 0);
      Arrays.fill(twScores, 0);
      Arrays.fill(twHitsPerLang, 0);
      twNumLookups = 0;
      Arrays.fill(alphabetCounts, 0);
      if (cjScores != null) {
        cjScores.clear();
      }
    }

    /** Adds all scoring fields from {@code other} into this instance. CJ results are not added. */
    public void addFrom(Scores other) {
      numWords += other.numWords;
      twNumLookups += other.twNumLookups;
      for (int i = 0; i < ngramScores.length; i++) {
        ngramScores[i] += other.ngramScores[i];
        ngramHitsPerLang[i] += other.ngramHitsPerLang[i];
        twScores[i] += other.twScores[i];
        twHitsPerLang[i] += other.twHitsPerLang[i];
      }
      for (int i = 0; i < alphabetCounts.length; i++) {
        alphabetCounts[i] += other.alphabetCounts[i];
      }
    }

    /** Returns the total number of scored characters across all alphabets. */
    public int numChars() {
      int sum = 0;
      for (int v : alphabetCounts) {
        sum += v;
      }
      return sum;
    }
  }

  // ========================================================================
  // Results: computed after scoring
  // ========================================================================

  /** Detection results, including accumulated {@link Scores} and the computed result. */
  public static class Results {
    private final Model model;
    // Scratch space for topword normalization.
    final double[] twScratch;

    /** Accumulated scoring data from scoreWord() - everything mutated at scoring time is in here */
    public final Scores scores;

    /** CJClassifier results (wraps scores.cjScores), or null if no CJClassifier in model. */
    public final CJClassifier.Results cjResults;

    // All of these will be populated by computeResults (clearing is not necessary):

    /** Per-language final scores after normalization for missing hits. */
    public final double[] totalScores;

    /** The detected language result, or null before detection. */
    public Language result;

    /** Gap between the winning language's score and the runner-up (0.0 == close, 1.0 far) */
    public double gap;

    /** The predominant alphabet index after computeResult, or -1. */
    public int predominantAlphaIdx;

    public Results(Model model) {
      this.model = model;
      int numLangs = model.numLanguages();
      this.scores = new Scores(model);
      this.cjResults = scores.cjScores != null ? new CJClassifier.Results(scores.cjScores) : null;
      this.totalScores = new double[numLangs];
      this.twScratch = new double[numLangs];
    }

    /**
     * Computes final normalized scores and determines the winning language. All exit paths will
     * fill {@link #result}, {@link #totalScores}, {@link #gap}, and {@link #predominantAlphaIdx}.
     *
     * <p>Call this after all text has been added via {@link Detector#addText}.
     *
     * @param boosts per-language boost array, or {@code null} for no boosts
     */
    public void computeResult(double[] boosts) {
      int numLangs = scores.ngramScores.length;

      // Determine predominant alphabet by weighted character count.
      // When a CJClassifier exists, JA_KANA and HAN are treated as a
      // combined group: if their joint weighted total is predominant and any kana
      // chars are present the result is JAPANESE, otherwise delegate to CJClassifier.
      predominantAlphaIdx = -1;
      double maxWeightedCount = 0;
      Alphabet[] alphas = model.getAlphabets();
      int cjUnifiedIdx = model.alphabetIndex(Alphabet.HAN);
      int jaKanaIdx = model.alphabetIndex(Alphabet.JA_KANA);
      double cjGroupWeight = 0;
      for (int ai = 0; ai < scores.alphabetCounts.length; ai++) {
        if (scores.alphabetCounts[ai] > 0) {
          double wc = scores.alphabetCounts[ai] * alphas[ai].weight();
          if (cjResults != null && (ai == cjUnifiedIdx || ai == jaKanaIdx)) {
            cjGroupWeight += wc;
          } else {
            if (wc > maxWeightedCount) {
              maxWeightedCount = wc;
              predominantAlphaIdx = ai;
            }
          }
        }
      }
      // Check if the CJ group beats all other alphabets
      if (cjGroupWeight > maxWeightedCount && computeResultForCJ(boosts)) {
        return;
      }

      // No alphabet chars at all
      if (predominantAlphaIdx < 0) {
        Arrays.fill(totalScores, 0);
        result = Language.UNKNOWN;
        return;
      }

      // If this alphabet uniquely identifies a language, return it directly.
      // Set totalScores: 1.0 for the unique language, 0 for others.
      if (model.alphabetImpliesOneLanguage(predominantAlphaIdx)) {
        int li = model.langIndicesForAlphabet(predominantAlphaIdx)[0];
        for (int i = 0; i < numLangs; i++) {
          totalScores[i] = (i == li) ? 1.0 : 0;
        }
        result = model.uniqueLanguageForAlphabet(predominantAlphaIdx);
        gap = 1.0;
        return;
      }

      // Normalize the ngram scores to totalScores.
      normalizedNgramScoresTo(totalScores);

      // Maybe blend in topwords scores.
      double twFactor = normalizedTwScoresTo(twScratch);
      if (twFactor > 0.0) {
        double ngramWeight = 1.0 - twFactor;
        for (int li = 0; li < numLangs; li++) {
          totalScores[li] = (totalScores[li] * ngramWeight) + (twScratch[li] * twFactor);
        }
      }

      // Apply any language boosts.
      if (boosts != null) {
        for (int li = 0; li < numLangs; li++) {
          if (boosts[li] != 0.0) {
            totalScores[li] += boosts[li] * Math.abs(totalScores[li]);
          }
        }
      }

      // Restrict to languages using the predominant alphabet
      int[] candidates = model.langIndicesForAlphabet(predominantAlphaIdx);
      int bestIdx = candidates[0];
      int secondIdx = -1;
      for (int k = 1; k < candidates.length; k++) {
        int li = candidates[k];
        if (totalScores[li] > totalScores[bestIdx]) {
          secondIdx = bestIdx;
          bestIdx = li;
        } else if (secondIdx < 0 || totalScores[li] > totalScores[secondIdx]) {
          secondIdx = li;
        }
      }
      if (totalScores[bestIdx] != 0.0) {
        result = model.getLanguages()[bestIdx];
        // Compute gap: 1 - (best / second). Scores are negative, so best is least negative.
        double second = secondIdx >= 0 ? totalScores[secondIdx] : totalScores[bestIdx];
        gap = (second != 0.0) ? 1.0 - (totalScores[bestIdx] / second) : 0.0;
      } else {
        result = Language.UNKNOWN;
      }
    }

    private static Language convertLanguage(CJLanguage cj) {
      switch (cj) {
        case CHINESE_SIMPLIFIED:
          return Language.CHINESE_SIMPLIFIED;
        case CHINESE_TRADITIONAL:
          return Language.CHINESE_TRADITIONAL;
        case JAPANESE:
          return Language.JAPANESE;
        default:
          break; // unknown
      }
      return Language.UNKNOWN;
    }

    private static final Language[] CJ_LANGUAGES;

    static {
      CJ_LANGUAGES = new Language[CJClassifier.CJ_LANGUAGES_LIST.size()];
      for (int i = 0; i < CJ_LANGUAGES.length; i++) {
        CJ_LANGUAGES[i] = convertLanguage(CJClassifier.CJ_LANGUAGES_LIST.get(i));
      }
    }

    private boolean computeResultForCJ(double[] boosts) {
      int jaKanaIdx = model.alphabetIndex(Alphabet.JA_KANA);
      int cjUnifiedIdx = model.alphabetIndex(Alphabet.HAN);
      int kanaChars = (jaKanaIdx >= 0) ? scores.alphabetCounts[jaKanaIdx] : 0;
      int hanChars = (cjUnifiedIdx >= 0) ? scores.alphabetCounts[cjUnifiedIdx] : 0;
      int totalCJ = kanaChars + hanChars;
      CJClassifier cjc = model.getCJClassifier();
      if (totalCJ > 0 && kanaChars > 0 && cjc != null) {
        double kanaRatio = (double) kanaChars / totalCJ;
        if (kanaRatio > cjc.getToleratedKanaThreshold()) {
          // Kana fraction exceeds (very low, circa 1%) threshold — classify as Japanese.
          // We have this as a safety mechanism in case a larger Chinese article briefly
          // quotes Japanese text.
          predominantAlphaIdx = jaKanaIdx;
          result = Language.JAPANESE;
          gap = 1.0;
          Arrays.fill(totalScores, 0);
          int jaLi = model.langIndex(Language.JAPANESE);
          if (jaLi >= 0) {
            totalScores[jaLi] = 1.0;
          }
          return true;
        }
        // Kana present but below threshold — fall through to CJClassifier scoring.
      }
      if (cjResults != null) {
        // Propagate detector-level boosts to CJClassifier stats.
        mapCJBoosts(boosts, cjResults);
        CJLanguage cjResult = cjc.computeResult(cjResults);
        if (cjResult != CJLanguage.UNKNOWN) {
          predominantAlphaIdx = cjUnifiedIdx;
          result = convertLanguage(cjResult);
          gap = cjResults.gap;
          // Populate totalScores from CJ results; zero out non-CJ languages.
          Arrays.fill(totalScores, 0);
          for (int ci = 0; ci < CJ_LANGUAGES.length; ci++) {
            int li = model.langIndex(CJ_LANGUAGES[ci]);
            if (li >= 0) {
              totalScores[li] = cjResults.totalScores[ci];
            }
          }
          return true;
        }
      }
      // CJClassifier returned UNKNOWN; fall through with HAN as predominant
      predominantAlphaIdx = cjUnifiedIdx;
      return false;
    }

    /**
     * Maps detector-level boosts (indexed by Model langIdx) to CJClassifier boosts (indexed by CJ
     * lang index: 0=zh-hans, 1=zh-hant, 2=ja).
     */
    private void mapCJBoosts(double[] detectorBoosts, CJClassifier.Results cjResults) {
      for (int ci = 0; ci < cjResults.boosts.length; ci++) {
        cjResults.boosts[ci] = 0.0;
      }
      if (detectorBoosts != null) {
        for (int ci = 0; ci < cjResults.boosts.length; ++ci) {
          int li = model.langIndex(CJ_LANGUAGES[ci]);
          if (li >= 0) {
            cjResults.boosts[ci] = detectorBoosts[li];
          }
        }
      }
    }

    // Split out so that toString can execute the same code.
    private void normalizedNgramScoresTo(double[] out) {
      double minLogProb = model.getMinLogProb();
      // Copy original ngram scores, and compute max.
      int maxHits = 0;
      for (int li = 0; li < out.length; li++) {
        out[li] = scores.ngramScores[li];
        if (scores.ngramHitsPerLang[li] > maxHits) {
          maxHits = scores.ngramHitsPerLang[li];
        }
      }
      double factor = maxHits > 0 ? 1.0 / (double) maxHits : 1.0;
      for (int li = 0; li < out.length; li++) {
        out[li] += (maxHits - scores.ngramHitsPerLang[li]) * minLogProb;
        out[li] *= factor;
      }
    }

    // Split out so that toString can execute the same code.
    private double normalizedTwScoresTo(double[] out) {
      if (scores.twNumLookups == 0) {
        return 0;
      }
      int twMaxHits = 0;
      for (int li = 0; li < out.length; li++) {
        out[li] = scores.twScores[li];
        if (scores.twHitsPerLang[li] > twMaxHits) {
          twMaxHits = scores.twHitsPerLang[li];
        }
      }
      if (twMaxHits == 0) {
        return 0.0;
      }
      double twMinLogProb = model.getTwMinLogProb();
      double factor = 1.0 / (double) twMaxHits;
      for (int li = 0; li < out.length; li++) {
        out[li] += (twMaxHits - scores.twHitsPerLang[li]) * twMinLogProb;
        out[li] *= factor;
      }

      // Blend factor: weight topwords more heavily when coverage is high.
      double ratio = (double) twMaxHits / scores.twNumLookups;
      if (ratio < 0.5) {
        return 0.5 * (double) twMaxHits / (double) scores.twNumLookups;
      } else {
        return 0.8 * (double) twMaxHits / (double) scores.twNumLookups;
      }
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("Results{result=");
      sb.append(result != null ? result.isoCode() : "null");
      sb.append(String.format(" gap=%.4f", gap));
      sb.append(" words=").append(scores.numWords).append(" chars=").append(scores.numChars());

      if (predominantAlphaIdx >= 0 && model.isCJAlphabet(predominantAlphaIdx)) {
        // CJ/kana path decided the result; show CJ info.
        CJClassifier.Results cjr = cjResults;
        CJClassifier.Scores cjs = cjr != null ? cjr.scores : null;
        if (cjs != null && cjs.kanaCount > 0) {
          sb.append(String.format(" kana=%d/%d", cjs.kanaCount, cjs.kanaCount + cjs.cjCharCount));
        }
        if (cjs != null) {
          for (int ci = 0; ci < CJ_LANGUAGES.length; ci++) {
            sb.append(
                String.format(
                    " | %s: uni=%.2f/%d bi=%.2f/%d total=%.3f",
                    CJ_LANGUAGES[ci].isoCode(),
                    cjs.unigramScores[ci],
                    cjs.unigramHitsPerLang[ci],
                    cjs.bigramScores[ci],
                    cjs.bigramHitsPerLang[ci],
                    cjr.totalScores[ci]));
          }
        }
      } else {
        // Ngram/topword path.
        Language[] langs = model.getLanguages();

        double[] normNgrams = new double[langs.length];
        normalizedNgramScoresTo(normNgrams);
        double[] normTw = new double[langs.length];
        normalizedTwScoresTo(normTw);

        // Sort language indices by descending totalScores
        Integer[] order = new Integer[langs.length];
        for (int i = 0; i < order.length; i++) {
          order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Double.compare(totalScores[b], totalScores[a]));

        for (int k = 0; k < order.length; k++) {
          int li = order[k];
          sb.append(
              String.format(
                  " | %s: ngram=%.3f/%d->%.3f tw=%.3f/%d->%.3f total=%.3f",
                  langs[li].isoCode(),
                  scores.ngramScores[li],
                  scores.ngramHitsPerLang[li],
                  normNgrams[li],
                  scores.twScores[li],
                  scores.twHitsPerLang[li],
                  normTw[li],
                  totalScores[li]));
        }
      }
      sb.append('}');
      return sb.toString();
    }

    /** Returns a compact ratio-based string, sorted best-first (highest ratio first). */
    public String toShortString() {
      if (result == null || result == Language.UNKNOWN) return "";
      double best = totalScores[0];
      for (int i = 1; i < totalScores.length; i++) {
        if (totalScores[i] > best) best = totalScores[i];
      }
      if (best == 0.0) return "";

      Language[] langs = model.getLanguages();
      int numLangs = langs.length;
      // Build (ratio, langIndex) pairs and sort descending by ratio
      double[] ratios = new double[numLangs];
      Integer[] indices = new Integer[numLangs];
      for (int i = 0; i < numLangs; i++) {
        ratios[i] = totalScores[i] != 0 ? best / totalScores[i] : 0;
        indices[i] = i;
      }
      Arrays.sort(indices, (a, b) -> Double.compare(ratios[b], ratios[a]));

      StringBuilder sb = new StringBuilder();
      for (int k = 0; k < numLangs; k++) {
        if (k > 0) sb.append(',');
        int i = indices[k];
        sb.append(langs[i].isoCode()).append(':');
        sb.append(String.format("%.3f", ratios[i]));
      }
      return sb.toString();
    }
  }

  /** Returns the model used by this detector. */
  public Model model() {
    return model;
  }

  /** Returns the detection results, populated after {@link #detect} or {@link #computeResult}. */
  public Results results() {
    return results;
  }

  private WordSegmenter makeWordSegmenter(Model model) {
    CJClassifier cjc = model.getCJClassifier();
    return new WordSegmenter(
        model,
        (wordBuf, wordLen, alphaIdx) -> {
          if (model.alphabetImpliesOneLanguage(alphaIdx) || scoreWord(wordLen)) {
            results.scores.alphabetCounts[alphaIdx] += wordLen;
            results.scores.numWords++;
          }
        },
        cjc != null
            ? (wordBuf, wordLen, alphaIdx) -> {
              cjc.addText(wordBuf, 0, wordLen, results.scores.cjScores);
              results.scores.alphabetCounts[alphaIdx] += wordLen;
              results.scores.numWords++;
            }
            : null);
  }

  // ========================================================================
  // Scoring Support
  // ========================================================================

  // Scores ngram and topwords within a word.
  // The word segmenter calls out to here from addText().
  // lookupKey.chars points at segmenter.wordBuf (shared buffer).
  private boolean scoreWord(int wordLen) {
    if (useTopwords && !scoreTopwords(wordLen)) {
      return false; // it's an (invalid to score) skipword (e.g. "http")
    }
    final Scores scores = results.scores;

    // Score all ngrams within a word against the model tables.
    // We got in reverse to support stopIfNgramCovered.
    // Often this range is from 5..1, but we skip the 1-gram and 2-grams if
    // the 3-grams are all covered (stopIfNgramCovered==3).
    for (int n = Math.min(maxNgram, wordLen); n >= minNgram; n--) {
      lookupKey.length = n;
      final NgramTable table = model.getTable(n);
      final float[] probData = table.probData();
      boolean isFullyCovered = true;
      for (lookupKey.offset = 0; lookupKey.offset <= wordLen - n; lookupKey.offset++) {
        NgramTable.NgramEntry entry = table.lookup(lookupKey);
        if (entry != null) {
          sumNgramEntry(entry, probData, results.scores);
        } else {
          isFullyCovered = false;
        }
      }
      if (isFullyCovered && n <= stopIfNgramCovered) break; // sufficient.
    }
    return true;
  }

  private void sumNgramEntry(NgramTable.NgramEntry entry, float[] probData, Scores scores) {
    int[] langIndices = entry.langIndices;
    int probOfs = entry.probOffset;
    for (int j = 0; j < langIndices.length; j++) {
      int li = langIndices[j];
      scores.ngramScores[li] += probData[probOfs + j];
      scores.ngramHitsPerLang[li]++;
    }
  }

  private boolean scoreTopwords(int wordLen) {
    // Topwords/skipwords lookup.
    // Only do this for multi-character words (at least non-accented ones)
    if (wordLen > 1 || lookupKey.chars[0] >= 0x80) {
      Scores scores = results.scores;
      // Topwords and skipwords: look up the whole word
      NgramTable twTable = model.getTopwordsTable();
      lookupKey.offset = 0;
      lookupKey.length = wordLen;
      scores.twNumLookups++;
      NgramTable.NgramEntry twEntry = twTable.lookup(lookupKey);
      if (twEntry != null) {
        int[] langIndices = twEntry.langIndices;
        if (langIndices == null) { // skipped word.
          // backout the stats for the skipped word.
          scores.twNumLookups--;
          return false;
        }
        float[] twProb = twTable.probData();
        int probOfs = twEntry.probOffset;
        for (int j = 0; j < langIndices.length; j++) {
          int li = langIndices[j];
          scores.twScores[li] += twProb[probOfs + j];
          scores.twHitsPerLang[li]++;
        }
      } else if (wordLen > 2) {
        int apos = midWordApostrophePosition(lookupKey.chars, wordLen);
        if (apos > 0) {
          scoreApostropheTopwords(twTable, apos, wordLen, results.scores);
        }
      }
    }
    return true;
  }

  // Some more complex logic for looking up apostrophes (e.g. l'homme -> l'  and homme),
  // separated out because it's not as common.
  private void scoreApostropheTopwords(NgramTable twTable, int apos, int wordLen, Scores scores) {
    // Look up prefix including the apostrophe (e.g. "l'")
    lookupKey.offset = 0;
    lookupKey.length = apos + 1;
    NgramTable.NgramEntry entry = twTable.lookup(lookupKey);
    if (entry != null && entry.langIndices != null) {
      int probOfs = entry.probOffset;
      int[] langIndices = entry.langIndices;
      float[] twProb = twTable.probData();
      for (int j = 0; j < langIndices.length; j++) {
        scores.twScores[langIndices[j]] += twProb[probOfs + j];
        scores.twHitsPerLang[langIndices[j]]++;
      }
    }
    // Look up suffix after the apostrophe (e.g. "homme")
    lookupKey.offset = apos + 1;
    lookupKey.length = wordLen - apos - 1;
    if (lookupKey.length > 1) {
      scores.twNumLookups++;
      entry = twTable.lookup(lookupKey);
      if (entry != null && entry.langIndices != null) {
        int probOfs = entry.probOffset;
        int[] langIndices = entry.langIndices;
        float[] twProb = twTable.probData();
        for (int j = 0; j < langIndices.length; j++) {
          scores.twScores[langIndices[j]] += twProb[probOfs + j];
          scores.twHitsPerLang[langIndices[j]]++;
        }
      }
    }
  }

  // Specific version for Detector.
  private static int midWordApostrophePosition(char[] chars, int len) {
    --len; // don't count the edge
    for (int i = 1; i < len; i++) {
      if (chars[i] == '\'') {
        return i;
      }
    }
    return -1;
  }
}
