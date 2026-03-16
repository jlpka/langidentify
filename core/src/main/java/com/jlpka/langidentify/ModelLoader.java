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
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * Package-private loader for ngram-based language detection models. Reads per-language ngram files
 * and builds a {@link Model} containing {@link NgramTable}s with log-probability entries.
 *
 * <p>Use {@link Model#load} or {@link Model#loadFromPath} as the public entry points.
 */
class ModelLoader {
  private static final Logger logger = Logger.getLogger(ModelLoader.class.getName());

  // Singleton so that we don't load the same model twice.
  private static final Map<String, Model> cache = Collections.synchronizedMap(new HashMap<>());

  // ========================================================================
  // ResourceResolver abstraction
  // ========================================================================

  /** Abstraction for resolving and opening model data files from filesystem or classpath. */
  interface ResourceResolver {
    /** Opens the named resource for reading, decompressing .gz files automatically. */
    InputStream open(String name) throws IOException;

    /** Returns true if the named resource exists and can be opened. */
    boolean exists(String name);
  }

  /** Resolves model files from a filesystem directory or prefix path. */
  static class FileResolver implements ResourceResolver {
    private final String prefix;

    FileResolver(String raw) {
      String stripped = raw;
      while (stripped.endsWith(File.separator) || stripped.endsWith("/")) {
        stripped = stripped.substring(0, stripped.length() - 1);
      }
      this.prefix = new File(stripped).isDirectory() ? stripped + File.separator : stripped;
    }

    @Override
    public boolean exists(String name) {
      return new File(prefix + name).exists();
    }

    @Override
    public InputStream open(String name) throws IOException {
      String path = prefix + name;
      InputStream in = new BufferedInputStream(new FileInputStream(path), 1024 * 1024);
      if (name.endsWith(".gz")) {
        return new GZIPInputStream(in, 1024 * 1024);
      }
      return in;
    }
  }

  /** Resolves model files from the classpath under a variant-specific base path. */
  static class ClasspathResolver implements ResourceResolver {
    private final String basePath;

    ClasspathResolver(String variant) {
      this.basePath = "/com/jlpka/langidentify/models/" + variant + "/";
    }

    @Override
    public boolean exists(String name) {
      return Model.class.getResource(basePath + name) != null;
    }

    @Override
    public InputStream open(String name) throws IOException {
      InputStream in = Model.class.getResourceAsStream(basePath + name);
      if (in == null) {
        throw new FileNotFoundException("Classpath resource not found: " + basePath + name);
      }
      in = new BufferedInputStream(in, 1024 * 1024);
      if (name.endsWith(".gz")) {
        return new GZIPInputStream(in, 1024 * 1024);
      }
      return in;
    }
  }

  // ========================================================================
  // Entry points
  // ========================================================================

  /** Clears the model cache, forcing the next load to re-read from disk/classpath. */
  static synchronized void clearCache() {
    cache.clear();
  }

  /** Loads (or returns cached) a Model with default min log-probability and max ngram size. */
  static Model loadFromPath(String prefix, List<Language> languages) throws IOException {
    return loadFromPath(prefix, languages, 0, 0, 0);
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
   */
  static Model loadFromPath(
      String prefix,
      List<Language> languages,
      double minLogProb,
      double twMinLogProb,
      double cjMinLogProb)
      throws IOException {
    ResourceResolver resolver = new FileResolver(prefix);

    // Canonicalize Language order.
    EnumSet<Language> langSet = EnumSet.noneOf(Language.class);
    langSet.addAll(languages);
    Language[] orderedLangs = langSet.toArray(new Language[0]);

    boolean skipTopwords = Double.isNaN(twMinLogProb);
    String cacheKey =
        langSet.stream().map(Language::isoCode).collect(Collectors.joining(","))
            + ":"
            + minLogProb
            + (skipTopwords ? ":notw" : ":" + twMinLogProb)
            + ":cj"
            + cjMinLogProb;
    // cache is a Collections.synchronizedMap(), so cache access is safe without being synchronized.
    // We avoid synchronizing this loadFromPath() method so that if somebody wants to access an
    // already-loaded Model while another thread is loading a new Model, then it won't be blocked.
    Model cached = cache.get(cacheKey);
    if (cached != null) {
      return cached;
    }
    return actuallyLoad(orderedLangs, cacheKey, resolver, minLogProb, twMinLogProb, cjMinLogProb);
  }

  /**
   * Loads a Model from the classpath, auto-discovering the variant. Tries "full" first, then
   * "lite". Requires a {@code langidentify-models-lite} or {@code langidentify-models-full}
   * dependency.
   */
  static Model load(List<Language> languages) throws IOException {
    return load(languages, 0, 0, 0);
  }

  /** Loads a Model from the classpath with tuning parameters, auto-discovering the variant. */
  static Model load(
      List<Language> languages, double minLogProb, double twMinLogProb, double cjMinLogProb)
      throws IOException {
    if (languages.isEmpty()) {
      throw new IllegalArgumentException("At least one language required");
    }
    String testFile = "ngrams-" + languages.get(0).isoCode() + ".txt.gz";
    for (String variant : new String[] {"full", "lite"}) {
      ClasspathResolver resolver = new ClasspathResolver(variant);
      if (resolver.exists(testFile)) {
        return load(variant, languages, minLogProb, twMinLogProb, cjMinLogProb);
      }
    }
    throw new IOException(
        "No model found on classpath for "
            + languages.get(0).isoCode()
            + ". Add a langidentify-models-lite or langidentify-models-full dependency.");
  }

  /** Loads (or returns cached) a Model from the classpath using a named variant. */
  static Model load(String variant, List<Language> languages) throws IOException {
    return load(variant, languages, 0, 0, 0);
  }

  /**
   * Loads a Model from the classpath with specified languages, forcing the lite model. If the full
   * model is all that exists, we read the full model files with lite thresholds (-12/-12).
   */
  static Model loadLite(List<Language> languages) throws IOException {
    if (languages.isEmpty()) {
      throw new IllegalArgumentException("At least one language required");
    }
    String testFile = "ngrams-" + languages.get(0).isoCode() + ".txt.gz";
    ClasspathResolver liteResolver = new ClasspathResolver("lite");
    if (liteResolver.exists(testFile)) {
      return load("lite", languages, -12, -12, 0);
    }
    // Fall back to full model files with lite thresholds.
    return load("full", languages, -12, -12, 0);
  }

  /** Loads a Model from the classpath with specified parameters, forcing the full model. */
  static Model loadFull(List<Language> languages) throws IOException {
    return load("full", languages, 0, 0, 0);
  }

  /**
   * Loads (or returns cached) a Model from the classpath for the given variant and language set.
   *
   * @param variant model variant name ("lite" or "full")
   * @param languages the languages to load
   * @param minLogProb minimum log-probability; ngrams below this threshold are discarded
   * @param twMinLogProb minimum log-probability for topwords; words below are discarded. Use {@code
   *     Double.NaN} to skip loading topwords entirely.
   * @param cjMinLogProb minimum log-probability floor for CJClassifier; 0 means use the file's
   *     MinLogProb value
   * @return the loaded Model
   */
  static Model load(
      String variant,
      List<Language> languages,
      double minLogProb,
      double twMinLogProb,
      double cjMinLogProb)
      throws IOException {
    ResourceResolver resolver = new ClasspathResolver(variant);

    EnumSet<Language> langSet = EnumSet.noneOf(Language.class);
    langSet.addAll(languages);
    Language[] orderedLangs = langSet.toArray(new Language[0]);

    boolean skipTopwords = Double.isNaN(twMinLogProb);
    String cacheKey =
        "classpath:"
            + variant
            + ":"
            + langSet.stream().map(Language::isoCode).collect(Collectors.joining(","))
            + ":"
            + minLogProb
            + (skipTopwords ? ":notw" : ":" + twMinLogProb)
            + ":cj"
            + cjMinLogProb;
    Model cached = cache.get(cacheKey);
    if (cached != null) {
      return cached;
    }
    return actuallyLoad(orderedLangs, cacheKey, resolver, minLogProb, twMinLogProb, cjMinLogProb);
  }

  // ========================================================================
  // Core loading logic
  // ========================================================================

  private static synchronized Model actuallyLoad(
      Language[] orderedLangs,
      String cacheKey,
      ResourceResolver resolver,
      double minLogProb,
      double twMinLogProb,
      double cjMinLogProb)
      throws IOException {
    // Re-check cache with the lock held.
    Model cached = cache.get(cacheKey);
    if (cached != null) {
      return cached;
    }
    long startTime = System.nanoTime();
    boolean skipTopwords = Double.isNaN(twMinLogProb);
    boolean[] skipNgrams = computeSkipNgrams(orderedLangs);

    // Resolve resource names up front so we can fail fast.
    String[] ngramNames = new String[orderedLangs.length];
    for (int li = 0; li < orderedLangs.length; li++) {
      if (skipNgrams[li]) {
        continue;
      }
      String isoCode = orderedLangs[li].isoCode();
      String gzName = "ngrams-" + isoCode + ".txt.gz";
      String txtName = "ngrams-" + isoCode + ".txt";
      if (resolver.exists(gzName)) {
        ngramNames[li] = gzName;
      } else if (resolver.exists(txtName)) {
        ngramNames[li] = txtName;
      } else {
        throw new FileNotFoundException(
            "Ngram file not found for " + isoCode + ": tried " + gzName + " and " + txtName);
      }
    }

    String[] topwordNames = new String[orderedLangs.length];
    if (!skipTopwords) {
      for (int li = 0; li < orderedLangs.length; li++) {
        if (skipNgrams[li]) {
          continue;
        }
        String isoCode = orderedLangs[li].isoCode();
        String gzName = "topwords-" + isoCode + ".txt.gz";
        String txtName = "topwords-" + isoCode + ".txt";
        if (resolver.exists(gzName)) {
          topwordNames[li] = gzName;
        } else if (resolver.exists(txtName)) {
          topwordNames[li] = txtName;
        }
      }
    }

    // Determine if CJ classifier is needed.
    EnumMap<Language, Integer> tempLangIndex = new EnumMap<>(Language.class);
    for (int i = 0; i < orderedLangs.length; i++) {
      tempLangIndex.put(orderedLangs[i], i);
    }
    boolean hasJa = tempLangIndex.containsKey(Language.JAPANESE);
    boolean hasZhHans = tempLangIndex.containsKey(Language.CHINESE_SIMPLIFIED);
    boolean hasZhHant = tempLangIndex.containsKey(Language.CHINESE_TRADITIONAL);
    boolean needsCJ = (hasJa && (hasZhHans || hasZhHant)) || (hasZhHans && hasZhHant);

    // Load ngrams, topwords, and CJ classifier in parallel.
    ExecutorService executor = Executors.newFixedThreadPool(3);
    try {
      // --- Ngrams task: load + compact ---
      // When 0, use permissive defaults for loading; effective values derived after loading
      final String[] ngramNamesFinal = ngramNames;
      final int loadMaxNgram = 5;
      final double loadMinLogProb = minLogProb == 0.0 ? -Double.MAX_VALUE : minLogProb;
      Future<LoadedNgramData> ngramFuture =
          executor.submit(
              () -> {
                long t0 = System.nanoTime();
                NgramTable.Builder[] builders = new NgramTable.Builder[loadMaxNgram];
                for (int i = 0; i < loadMaxNgram; i++) {
                  builders[i] = new NgramTable.Builder();
                }
                CharPageAllocator allocator = new CharPageAllocator();
                LoadNgramInfo[] infos = new LoadNgramInfo[orderedLangs.length];
                for (int li = 0; li < orderedLangs.length; li++) {
                  infos[li] = new LoadNgramInfo();
                  infos[li].wantedMinLogProb = loadMinLogProb;
                }
                for (int li = 0; li < orderedLangs.length; li++) {
                  if (ngramNamesFinal[li] != null) {
                    loadLanguageNgrams(
                        resolver, ngramNamesFinal[li], li, builders, allocator, infos[li]);
                  }
                }
                int effMaxNgram = loadMaxNgram;
                effMaxNgram = 0;
                for (LoadNgramInfo info : infos) {
                  effMaxNgram = Math.max(effMaxNgram, info.seenMaxNgram);
                }
                if (effMaxNgram == 0) {
                  effMaxNgram = loadMaxNgram;
                }
                // Compute global compact floor: max of caller threshold and all file thresholds.
                double compactFloor = loadMinLogProb;
                for (LoadNgramInfo info : infos) {
                  if (!Double.isNaN(info.seenMinLogProb)) {
                    compactFloor = Math.max(compactFloor, info.seenMinLogProb);
                  }
                }
                if (compactFloor == -Double.MAX_VALUE) {
                  compactFloor = 0.0;
                }
                NgramTable[] tables = new NgramTable[effMaxNgram];
                for (int n = 1; n <= effMaxNgram; n++) {
                  tables[n - 1] = builders[n - 1].compact((float) compactFloor);
                }
                double sec = (System.nanoTime() - t0) / 1_000_000_000.0;
                return new LoadedNgramData(tables, infos, sec);
              });

      // --- Topwords task: skipwords + topwords + compact ---
      final String[] topwordNamesFinal = topwordNames;
      final double loadTwMinLogProb =
          skipTopwords ? 0.0 : (twMinLogProb == 0.0 ? -Double.MAX_VALUE : twMinLogProb);
      final String swName =
          (!skipTopwords && resolver.exists("skipwords.txt")) ? "skipwords.txt" : null;
      Future<LoadedTopwordsData> topwordsFuture =
          executor.submit(
              () -> {
                long t0 = System.nanoTime();
                if (skipTopwords) {
                  double sec = (System.nanoTime() - t0) / 1_000_000_000.0;
                  return new LoadedTopwordsData(
                      NgramTable.EMPTY,
                      new int[orderedLangs.length],
                      0,
                      new double[orderedLangs.length],
                      sec);
                }
                NgramTable.Builder builder = new NgramTable.Builder();
                CharPageAllocator allocator = new CharPageAllocator();
                if (swName != null) {
                  loadSkipWords(resolver, swName, builder, allocator);
                }
                int langsLoaded = 0;
                int[] counts = new int[orderedLangs.length];
                double[] fileMinTwLogProbs = new double[orderedLangs.length];
                Arrays.fill(fileMinTwLogProbs, Double.NaN);
                for (int li = 0; li < orderedLangs.length; li++) {
                  if (topwordNamesFinal[li] != null) {
                    counts[li] =
                        loadTopWords(
                            resolver,
                            topwordNamesFinal[li],
                            li,
                            builder,
                            allocator,
                            loadTwMinLogProb,
                            fileMinTwLogProbs);
                    langsLoaded++;
                  }
                }
                // Compute global compact floor for topwords.
                double twCompactFloor = loadTwMinLogProb;
                for (double fml : fileMinTwLogProbs) {
                  if (!Double.isNaN(fml)) {
                    twCompactFloor = Math.max(twCompactFloor, fml);
                  }
                }
                if (twCompactFloor == -Double.MAX_VALUE) {
                  twCompactFloor = 0.0;
                }
                NgramTable table =
                    langsLoaded > 0 ? builder.compact((float) twCompactFloor) : NgramTable.EMPTY;
                double sec = (System.nanoTime() - t0) / 1_000_000_000.0;
                return new LoadedTopwordsData(table, counts, langsLoaded, fileMinTwLogProbs, sec);
              });

      // --- CJ classifier task ---
      Future<LoadedCJData> cjFuture =
          executor.submit(
              () -> {
                long t0 = System.nanoTime();
                CJClassifier cj = needsCJ ? CJClassifier.load(cjMinLogProb) : null;
                double sec = (System.nanoTime() - t0) / 1_000_000_000.0;
                return new LoadedCJData(cj, sec);
              });

      // Collect results (will propagate any IOException).
      LoadedNgramData ngramResult = unwrap(ngramFuture);
      LoadedTopwordsData twResult = unwrap(topwordsFuture);
      LoadedCJData cjResult = unwrap(cjFuture);

      // Derive effective values: max of caller threshold and all file thresholds.
      // If no files contributed a value, fall back to 0.0 (no filtering).
      double effectiveMinLogProb = loadMinLogProb;
      for (LoadNgramInfo info : ngramResult.infos) {
        if (!Double.isNaN(info.seenMinLogProb)) {
          effectiveMinLogProb = Math.max(effectiveMinLogProb, info.seenMinLogProb);
        }
      }
      if (effectiveMinLogProb == -Double.MAX_VALUE) {
        effectiveMinLogProb = 0.0;
      }
      int effectiveMaxNgram = ngramResult.tables.length;
      double effectiveTwMinLogProb = loadTwMinLogProb;
      for (double fml : twResult.fileMinTwLogProbs) {
        if (!Double.isNaN(fml)) {
          effectiveTwMinLogProb = Math.max(effectiveTwMinLogProb, fml);
        }
      }
      if (effectiveTwMinLogProb == -Double.MAX_VALUE) {
        effectiveTwMinLogProb = 0.0;
      }

      Model model =
          new Model(
              ngramResult.tables,
              orderedLangs,
              effectiveMinLogProb,
              effectiveTwMinLogProb,
              effectiveMaxNgram,
              cjResult.classifier,
              twResult.table);
      cache.put(cacheKey, model);

      double elapsedSec = (System.nanoTime() - startTime) / 1_000_000_000.0;
      StringBuilder sb = new StringBuilder();
      sb.append(String.format("ModelLoader: ngrams (minLogProb=%.1f):", effectiveMinLogProb));
      for (int li = 0; li < orderedLangs.length; li++) {
        if (ngramResult.infos[li].count > 0) {
          sb.append(' ')
              .append(orderedLangs[li].isoCode())
              .append(':')
              .append(ngramResult.infos[li].count);
        }
      }
      if (twResult.langsLoaded > 0) {
        sb.append(String.format("  topwords (minLogProb=%.1f):", effectiveTwMinLogProb));
        for (int li = 0; li < orderedLangs.length; li++) {
          if (twResult.counts[li] > 0) {
            sb.append(' ')
                .append(orderedLangs[li].isoCode())
                .append(':')
                .append(twResult.counts[li]);
          }
        }
      }
      sb.append(
          String.format(
              "  [ngrams %.1fs, tw %.1fs, cj %.1fs, total %.1fs]",
              ngramResult.sec, twResult.sec, cjResult.sec, elapsedSec));
      logger.info(sb.toString());

      // Detect file MinLogProb inconsistencies (different files have different thresholds).
      double minFileLogProb = Double.MAX_VALUE, maxFileLogProb = -Double.MAX_VALUE;
      for (LoadNgramInfo info : ngramResult.infos) {
        if (!Double.isNaN(info.seenMinLogProb)) {
          minFileLogProb = Math.min(minFileLogProb, info.seenMinLogProb);
          maxFileLogProb = Math.max(maxFileLogProb, info.seenMinLogProb);
        }
      }
      boolean ngramInconsistency =
          minFileLogProb != Double.MAX_VALUE && minFileLogProb != maxFileLogProb;

      double minTwFileLogProb = Double.MAX_VALUE, maxTwFileLogProb = -Double.MAX_VALUE;
      boolean twInconsistency = false;
      if (!skipTopwords) {
        for (double fml : twResult.fileMinTwLogProbs) {
          if (!Double.isNaN(fml)) {
            minTwFileLogProb = Math.min(minTwFileLogProb, fml);
            maxTwFileLogProb = Math.max(maxTwFileLogProb, fml);
          }
        }
        twInconsistency =
            minTwFileLogProb != Double.MAX_VALUE && minTwFileLogProb != maxTwFileLogProb;
      }

      // Print derived values when auto-detected or when files are inconsistent.
      StringBuilder derived = new StringBuilder("ModelLoader: derived:");
      derived.append(String.format(" minLogProb=%.1f", effectiveMinLogProb));
      if (ngramInconsistency) {
        derived.append(String.format(" (files: %.1f..%.1f)", minFileLogProb, maxFileLogProb));
      }
      derived.append(String.format(" maxNgram=%d", effectiveMaxNgram));
      if (!skipTopwords) {
        derived.append(String.format(" twMinLogProb=%.1f", effectiveTwMinLogProb));
        if (twInconsistency) {
          derived.append(String.format(" (files: %.1f..%.1f)", minTwFileLogProb, maxTwFileLogProb));
        }
      } else {
        derived.append(" topwords=skipped");
      }
      logger.info(derived.toString());

      // Log languages whose file MinLogProb is more restrictive than the requested threshold
      StringBuilder overrides = null;
      for (int li = 0; li < orderedLangs.length; li++) {
        double fml = ngramResult.infos[li].seenMinLogProb;
        if (!Double.isNaN(fml) && fml > minLogProb) {
          if (overrides == null) {
            overrides = new StringBuilder("ModelLoader: WARNING: file MinLogProb > requested for:");
          }
          overrides.append(String.format(" %s(%.1f)", orderedLangs[li].isoCode(), fml));
        }
      }
      if (overrides != null) {
        logger.warning(overrides.toString());
      }

      return model;
    } finally {
      executor.shutdown();
    }
  }

  // ========================================================================
  // Helpers to hold the parallel loaded data
  // ========================================================================

  /** Per-language information for ngram loading: inputs from caller, outputs filled by loader. */
  static class LoadNgramInfo {
    // Inputs (set by caller)
    double wantedMinLogProb;

    // Outputs (filled by loadLanguageNgrams)
    int count; // number of entries loaded
    double seenMinLogProb = Double.NaN; // MinLogProb from file header
    int seenMaxNgram; // max ngram size found in file header
  }

  private static class LoadedNgramData {
    final NgramTable[] tables;
    final LoadNgramInfo[] infos;
    final double sec;

    LoadedNgramData(NgramTable[] tables, LoadNgramInfo[] infos, double sec) {
      this.tables = tables;
      this.infos = infos;
      this.sec = sec;
    }
  }

  private static class LoadedTopwordsData {
    final NgramTable table;
    final int[] counts;
    final int langsLoaded;
    final double[] fileMinTwLogProbs;
    final double sec;

    LoadedTopwordsData(
        NgramTable table, int[] counts, int langsLoaded, double[] fileMinTwLogProbs, double sec) {
      this.table = table;
      this.counts = counts;
      this.langsLoaded = langsLoaded;
      this.fileMinTwLogProbs = fileMinTwLogProbs;
      this.sec = sec;
    }
  }

  private static class LoadedCJData {
    final CJClassifier classifier;
    final double sec;

    LoadedCJData(CJClassifier classifier, double sec) {
      this.classifier = classifier;
      this.sec = sec;
    }
  }

  /** Unwraps a Future, converting ExecutionException to IOException. */
  private static <T> T unwrap(Future<T> future) throws IOException {
    try {
      return future.get();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException) {
        throw (IOException) cause;
      }
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      throw new IOException("Error during parallel loading", cause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Loading interrupted", e);
    }
  }

  /**
   * Pre-computes which languages can skip ngram loading. A language skips if every alphabet it uses
   * is unique to it within the given language set.
   */
  private static boolean[] computeSkipNgrams(Language[] orderedLangs) {
    EnumMap<Alphabet, Integer> alphaLangCount = new EnumMap<>(Alphabet.class);
    for (Language lang : orderedLangs) {
      for (Alphabet alpha : lang.getAlphabets()) {
        alphaLangCount.merge(alpha, 1, Integer::sum);
      }
    }
    boolean[] skip = new boolean[orderedLangs.length];
    for (int li = 0; li < orderedLangs.length; li++) {
      boolean allUnique = true;
      for (Alphabet alpha : orderedLangs[li].getAlphabets()) {
        if (alphaLangCount.getOrDefault(alpha, 0) > 1) {
          allUnique = false;
          break;
        }
      }
      skip[li] = allUnique || Language.CJ.contains(orderedLangs[li]);
    }
    return skip;
  }

  // ========================================================================
  // Per-resource loading methods
  // ========================================================================

  private static void loadLanguageNgrams(
      ResourceResolver resolver,
      String name,
      int langIdx,
      NgramTable.Builder[] builders,
      CharPageAllocator allocator,
      LoadNgramInfo info)
      throws IOException {

    double minLogProb = info.wantedMinLogProb;
    long[] totals = new long[6]; // index 1..5
    int entryCount = 0;

    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(resolver.open(name), StandardCharsets.UTF_8), 1024 * 1024)) {

      // Parse header: "# 1:unique/total 2:unique/total ... MinLogProb: -16.0"
      String header = reader.readLine();
      if (header == null) {
        throw new IllegalArgumentException("Empty ngram file: " + name);
      }
      if (header.startsWith("# ")) {
        header = header.substring(2);
      }
      boolean foundMinLogProb = false;
      int seenMaxN = 0;
      String[] headerParts = header.split(" ");
      for (int pi = 0; pi < headerParts.length; pi++) {
        String part = headerParts[pi];
        if ("MinLogProb:".equals(part) && pi + 1 < headerParts.length) {
          double parsedMinLogProb = Double.parseDouble(headerParts[pi + 1]);
          info.seenMinLogProb = parsedMinLogProb;
          foundMinLogProb = true;
          // Use the more restrictive (higher) of the file's and caller's thresholds
          if (parsedMinLogProb > minLogProb) {
            minLogProb = parsedMinLogProb;
          }
          pi++; // skip the value token
          continue;
        }
        int colonIdx = part.indexOf(':');
        if (colonIdx > 0) {
          int n = Integer.parseInt(part.substring(0, colonIdx));
          String value = part.substring(colonIdx + 1);
          int slashIdx = value.indexOf('/');
          if (slashIdx < 0) {
            throw new IllegalArgumentException(
                "Expected unique/total format in ngram file header: " + name);
          }
          long total = Long.parseLong(value.substring(slashIdx + 1));
          if (n >= 1 && n <= 5) {
            totals[n] = total;
            if (n > seenMaxN) {
              seenMaxN = n;
            }
          }
        }
      }
      if (!foundMinLogProb) {
        throw new IllegalArgumentException("Missing MinLogProb in ngram file header: " + name);
      }
      info.seenMaxNgram = seenMaxN;

      // Read ngram lines: "ngram count"
      String line;
      int skipCount = 0;
      // Reusable lookup key
      NgramTable.Ngram lookupKey = new NgramTable.Ngram(new char[5], 0, 0);

      while ((line = reader.readLine()) != null) {
        if (line.isEmpty() || line.charAt(0) == '#') continue;
        int spaceIdx = line.lastIndexOf(' ');
        if (spaceIdx <= 0) continue;

        String ngram = line.substring(0, spaceIdx);
        String valueStr = line.substring(spaceIdx + 1);
        int n = ngram.length();
        if (n < 1 || totals[n] == 0) continue;

        float logProb;
        if (valueStr.charAt(0) == '-') {
          // Negative value is already a log-probability
          logProb = Float.parseFloat(valueStr);
        } else {
          long count = Long.parseLong(valueStr);
          logProb = (float) Math.log((double) count / totals[n]);
        }
        if (logProb < minLogProb) {
          skipCount++;
          continue; // skip entries below floor
        }

        NgramTable.Builder builder = builders[n - 1];

        // Set up lookup key to check if ngram already exists in table
        char[] lookupChars = lookupKey.chars;
        if (lookupChars.length < n) {
          lookupChars = new char[n];
          lookupKey.chars = lookupChars;
        }
        ngram.getChars(0, n, lookupChars, 0);
        lookupKey.offset = 0;
        lookupKey.length = n;

        NgramTable.LangProbListBuilder existing = builder.get(lookupKey);
        if (existing != null) {
          existing.add(langIdx, logProb);
        } else {
          // New entry — allocate chars from page allocator
          int pageOffset = allocator.allocate(n);
          char[] page = allocator.currentPage();
          ngram.getChars(0, n, page, pageOffset);

          NgramTable.Ngram key = new NgramTable.Ngram(page, pageOffset, n);
          NgramTable.LangProbListBuilder lpBuilder = new NgramTable.LangProbListBuilder();
          lpBuilder.add(langIdx, logProb);
          builder.put(key, lpBuilder);
        }

        entryCount++;
      }
    }
    info.count = entryCount;
  }

  /**
   * Loads universal skipwords from a plain text file (one word per line). Each word is inserted
   * into the topwords builder as a poisoned entry, so that subsequent topwords loading won't add
   * language data for these words. Lines starting with '#' and empty lines are skipped.
   */
  private static void loadSkipWords(
      ResourceResolver resolver,
      String name,
      NgramTable.Builder builder,
      CharPageAllocator allocator)
      throws IOException {

    int count = 0;
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(resolver.open(name), StandardCharsets.UTF_8))) {

      String line;
      NgramTable.Ngram lookupKey = new NgramTable.Ngram(new char[32], 0, 0);

      while ((line = reader.readLine()) != null) {
        if (line.isEmpty() || line.charAt(0) == '#') {
          continue;
        }
        String word = line.trim();
        int len = word.length();
        if (len == 0) {
          continue;
        }

        // Set up lookup key
        char[] lookupChars = lookupKey.chars;
        if (lookupChars.length < len) {
          lookupChars = new char[len];
          lookupKey.chars = lookupChars;
        }
        word.getChars(0, len, lookupChars, 0);
        lookupKey.offset = 0;
        lookupKey.length = len;

        NgramTable.LangProbListBuilder existing = builder.get(lookupKey);
        if (existing == null) {
          int pageOffset = allocator.allocate(len);
          char[] page = allocator.currentPage();
          word.getChars(0, len, page, pageOffset);

          NgramTable.Ngram key = new NgramTable.Ngram(page, pageOffset, len);
          NgramTable.LangProbListBuilder lpBuilder = new NgramTable.LangProbListBuilder();
          lpBuilder.poison();
          builder.put(key, lpBuilder);
        } else {
          existing.poison();
        }
        count++;
      }
    }
  }

  /**
   * Loads topwords for a single language from a file with header "# Count: NNN" and data lines
   * "word count". Skips single-character ASCII words (codepoint <= 0x7f). Words are loaded as long
   * as log(count/total) >= topwordsMinLogProb. The file is assumed to be sorted by descending
   * count.
   */
  private static int loadTopWords(
      ResourceResolver resolver,
      String name,
      int langIdx,
      NgramTable.Builder builder,
      CharPageAllocator allocator,
      double topwordsMinLogProb,
      double[] fileMinTwLogProbs)
      throws IOException {

    int entryCount = 0;

    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(resolver.open(name), StandardCharsets.UTF_8), 1024 * 1024)) {

      // Parse header: "# Count: [number] MinLogProb: [-10.0]"
      String header = reader.readLine();
      if (header == null) {
        throw new IllegalArgumentException("Empty topwords file: " + name);
      }
      if (!header.startsWith("# Count: ")) {
        throw new IllegalArgumentException(
            "Expected '# Count: NNN' header in topwords file: " + name);
      }
      String afterCount = header.substring("# Count: ".length()).trim();
      int spIdx = afterCount.indexOf(' ');
      long total = Long.parseLong(spIdx >= 0 ? afterCount.substring(0, spIdx) : afterCount);
      if (total <= 0) {
        throw new IllegalArgumentException("Invalid total count in topwords file: " + name);
      }
      // Parse MinLogProb (required)
      if (spIdx < 0 || !afterCount.substring(spIdx + 1).trim().startsWith("MinLogProb: ")) {
        throw new IllegalArgumentException("Missing MinLogProb in topwords file header: " + name);
      }
      double fileMinTwLogProb =
          Double.parseDouble(
              afterCount.substring(spIdx + 1).trim().substring("MinLogProb: ".length()).trim());
      fileMinTwLogProbs[langIdx] = fileMinTwLogProb;
      if (fileMinTwLogProb > topwordsMinLogProb) {
        topwordsMinLogProb = fileMinTwLogProb;
      }

      // Reusable lookup key
      NgramTable.Ngram lookupKey = new NgramTable.Ngram(new char[32], 0, 0);

      String line;
      int skipCount = 0;

      while ((line = reader.readLine()) != null) {
        if (line.isEmpty() || line.charAt(0) == '#') {
          continue;
        }
        int spaceIdx = line.lastIndexOf(' ');
        if (spaceIdx <= 0) {
          continue;
        }

        String word = line.substring(0, spaceIdx);
        int len = word.length();

        // Skip single-character ASCII words (keep non-ASCII single chars)
        if (len == 1 && word.charAt(0) <= 0x7f) {
          skipCount++;
          continue;
        }

        String valueStr = line.substring(spaceIdx + 1);
        float logProb;
        if (valueStr.charAt(0) == '-') {
          // Negative value is already a log-probability
          logProb = Float.parseFloat(valueStr);
        } else {
          long count = Long.parseLong(valueStr);
          logProb = (float) Math.log((double) count / total);
        }
        if (logProb < topwordsMinLogProb) {
          break; // sorted by descending count, so remaining are below threshold
        }

        // Set up lookup key
        char[] lookupChars = lookupKey.chars;
        if (lookupChars.length < len) {
          lookupChars = new char[len];
          lookupKey.chars = lookupChars;
        }
        word.getChars(0, len, lookupChars, 0);
        lookupKey.offset = 0;
        lookupKey.length = len;

        NgramTable.LangProbListBuilder existing = builder.get(lookupKey);
        if (existing != null) {
          existing.add(langIdx, logProb);
        } else {
          int pageOffset = allocator.allocate(len);
          char[] page = allocator.currentPage();
          word.getChars(0, len, page, pageOffset);

          NgramTable.Ngram key = new NgramTable.Ngram(page, pageOffset, len);
          NgramTable.LangProbListBuilder lpBuilder = new NgramTable.LangProbListBuilder();
          lpBuilder.add(langIdx, logProb);
          builder.put(key, lpBuilder);
        }

        entryCount++;
      }
    }
    return entryCount;
  }

  // ========================================================================
  // Char page suballocator
  // ========================================================================

  /** Allocates char storage in pages to avoid millions of tiny char[] allocations. */
  static class CharPageAllocator {
    private static final int PAGE_SIZE = 1024;
    private char[] current = new char[PAGE_SIZE];
    private int offset;

    /** Allocates {@code length} chars and returns the offset within the current page. */
    int allocate(int length) {
      if (offset + length > PAGE_SIZE) {
        current = new char[PAGE_SIZE];
        offset = 0;
      }
      int result = offset;
      offset += length;
      return result;
    }

    /** Returns the current page (the one last allocated from). */
    char[] currentPage() {
      return current;
    }
  }
}
