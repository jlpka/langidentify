package com.jlpka.langidentify.tools;

import com.jlpka.cjclassifier.CJClassifier;
import com.jlpka.langidentify.AccentRemover;
import com.jlpka.langidentify.Detector;
import com.jlpka.langidentify.Language;
import com.jlpka.langidentify.Model;
import com.jlpka.langidentify.Pair;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.xml.stream.*;

/**
 * Evaluates language detection models. Run with no arguments for usage.
 *
 * <pre>
 * # The shade plugin produces a fat jar; invoke via:
 * export INVOKE_EVAL="java -jar tools/target/langidentify-tools-1.0.jar"
 * export WIKI_DERIVED="../wikidata/derived/"
 * export WIKI_ORIG="/Volumes/devdata/wikidata/orig"
 * export PHRASE_TESTDATA="../lingua/src/accuracyReport/resources/language-testdata/sentences/"
 *
 * # adhoc — detect a single phrase:
 * $INVOKE_EVAL adhoc --languages europe_west_common --phrase "this is a test sentence"
 * $INVOKE_EVAL adhoc --languages en,fr,de --model $WIKI_DERIVED --phrase "this is a test sentence"
 *
 * # phraseeval — accuracy on test-phrase files:
 * $INVOKE_EVAL phraseeval --languages europe_west_common --testdir $PHRASE_TESTDATA --model full
 * $INVOKE_EVAL phraseeval --languages efigs,cj --model $WIKI_DERIVED --testdir $PHRASE_TESTDATA --misses
 *
 * # wikieval — accuracy on Wikipedia dumps:
 * $INVOKE_EVAL wikieval --languages europe_west_common \
 *     --infiles $WIKI_ORIG/dewiki-20260201-pages-articles.xml.bz2:de \
 *     --byparagraph --limit 100000 --sampledmisses 100
 *
 * $INVOKE_EVAL wikieval --languages europe_west_common \
 *     --infiles $WIKI_ORIG/frwiki-20260201-pages-articles.xml.bz2:fr
 *     --limit 100000
 *
 * # detectspeed — benchmark detection throughput:
 * $INVOKE_EVAL detectspeed --languages efigsnp --model $WIKI_DERIVED \
 *     --testdir $PHRASE_TESTDATA --duration 30
 *
 * # loadmodelspeed — benchmark model loading and memory consumption:
 * $INVOKE_EVAL loadmodelspeed --languages efigsnp --model lite
 * $INVOKE_EVAL loadmodelspeed --languages efigsnp --model full
 * $INVOKE_EVAL loadmodelspeed --languages efigsnp --model $WIKI_DERIVED
 * </pre>
 */
public class EvalTool {

  /** Poison pill for the producer-consumer queue (identity-compared). */
  @SuppressWarnings("StringOperationCanBeSimplified")
  private static final String POISON = new String("");

  // In Wikipedia and other corpora, simplified and traditional Chinese are mixed together.
  // Hence call it equivalent if they're equal or both Chinese.
  private static boolean isEquivalent(Language a, Language b) {
    return a == b || (a.isChinese() && b.isChinese());
  }

  private static void phraseEvalCommand(
      Detector detector, String testDir, boolean removeAccents, boolean showMisses)
      throws Exception {
    Model model = detector.model();
    AccentRemover accentRemover = removeAccents ? new AccentRemover() : null;
    Language[] modelLangs = model.getLanguages();

    int overallCorrect = 0;
    int overallTotal = 0;
    // Per-language results for summary line
    List<String> langSummaries = new ArrayList<>();

    for (Language expectedLang : modelLangs) {
      String isoCode = expectedLang.isChinese() ? "zh" : expectedLang.isoCode();
      File testFile = new File(testDir, isoCode + ".txt");
      if (!testFile.exists()) {
        System.err.printf(
            "Warning: test file not found: %s, skipping %s%n", testFile.getPath(), isoCode);
        continue;
      }

      int correct = 0;
      int total = 0;
      int skipped = 0;
      List<String> misses = new ArrayList<>();

      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(new FileInputStream(testFile), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank()) continue;
          CharSequence input = accentRemover != null ? accentRemover.remove(line) : line;
          detector.detect(input);
          Detector.Results results = detector.results();
          if (results.result == null || results.result == Language.UNKNOWN) {
            skipped++;
            continue;
          }
          total++;
          if (isEquivalent(results.result, expectedLang)) {
            correct++;
          } else if (showMisses) {
            String phrase = line.length() > 80 ? line.substring(0, 80) + "..." : line;
            misses.add(
                String.format(
                    "  MISS [expected=%s detected=%s] scores={%s} \"%s\"",
                    isoCode, results.result.isoCode(), results, phrase));
          }
        }
      }

      double pct = total > 0 ? 100.0 * correct / total : 0;
      System.err.printf(
          "%s: %d/%d correct (%.1f%%), %d skipped%n", isoCode, correct, total, pct, skipped);
      for (String miss : misses) {
        System.err.println(miss);
      }

      overallCorrect += correct;
      overallTotal += total;
      langSummaries.add(String.format("%s:%d/%d:%.1f%%", isoCode, correct, total, pct));
    }

    if (overallTotal > 0) {
      double overallPct = 100.0 * overallCorrect / overallTotal;
      System.err.printf(
          "%nOverall: %d/%d correct (%.1f%%)  %s%n",
          overallCorrect, overallTotal, overallPct, String.join(" ", langSummaries));
    }
  }

  private static void adhocCommand(Detector detector, String phrase) throws Exception {
    Language result = detector.detect(phrase);
    Detector.Results results = detector.results();
    System.out.println("Result: " + (result != null ? result.isoCode() : "null"));
    System.out.println(results);
  }

  private static void wikiEvalCommand(
      Detector detector,
      List<Pair<String, Language>> infiles,
      long limit,
      boolean byParagraph,
      boolean removeAccents,
      boolean showMisses,
      int sampledMisses)
      throws Exception {
    long startTime = System.currentTimeMillis();

    // Per-expected-language counters
    Map<Language, int[]> correctByLang =
        new TreeMap<>((a, b) -> a.isoCode().compareTo(b.isoCode()));
    Map<Language, int[]> totalByLang = new TreeMap<>((a, b) -> a.isoCode().compareTo(b.isoCode()));
    Map<Language, long[]> charsByLang = new TreeMap<>((a, b) -> a.isoCode().compareTo(b.isoCode()));
    Map<Language, long[]> wordsByLang = new TreeMap<>((a, b) -> a.isoCode().compareTo(b.isoCode()));
    Map<Language, int[]> shortMissByLang =
        new TreeMap<>((a, b) -> a.isoCode().compareTo(b.isoCode()));
    Map<Language, int[]> skippedByLang =
        new TreeMap<>((a, b) -> a.isoCode().compareTo(b.isoCode()));
    // Per-expected-language: detected-as -> count
    Map<Language, Map<Language, int[]>> missByDetectedLang =
        new TreeMap<>((a, b) -> a.isoCode().compareTo(b.isoCode()));

    int overallCorrect = 0;
    int overallTotal = 0;
    long overallChars = 0;
    long[] globalChunkCount = {0};
    boolean[] limitReached = {false};

    // Reservoir sampling state for --sampledmisses
    List<String> reservoir = sampledMisses > 0 ? new ArrayList<>(sampledMisses) : null;
    long[] totalMissCount = {0};
    Random rng = sampledMisses > 0 ? new Random() : null;

    for (Pair<String, Language> lf : infiles) {
      Language expectedLang = lf.second();
      int[] correctHolder = correctByLang.computeIfAbsent(expectedLang, k -> new int[1]);
      int[] totalHolder = totalByLang.computeIfAbsent(expectedLang, k -> new int[1]);
      long[] charsHolder = charsByLang.computeIfAbsent(expectedLang, k -> new long[1]);
      long[] wordsHolder = wordsByLang.computeIfAbsent(expectedLang, k -> new long[1]);
      int[] shortMissHolder = shortMissByLang.computeIfAbsent(expectedLang, k -> new int[1]);
      int[] skippedHolder = skippedByLang.computeIfAbsent(expectedLang, k -> new int[1]);
      Map<Language, int[]> detectedCounts =
          missByDetectedLang.computeIfAbsent(
              expectedLang, k -> new TreeMap<>((a, b) -> a.isoCode().compareTo(b.isoCode())));

      long[] chunkCount = {0};
      long[] nextLog = {1000};
      String[] lastMiss = {""};

      // Evaluates a single chunk of text (whole article or one paragraph).
      Consumer<CharSequence> evalChunk =
          (chunk) -> {
            if (chunk.length() < 20) return; // skip very short chunks
            if (++globalChunkCount[0] > limit) {
              limitReached[0] = true;
              return;
            }
            chunkCount[0]++;

            detector.detect(chunk);
            Detector.Results results = detector.results();
            wordsHolder[0] += results.scores.numWords;

            // Skip UNKNOWN results (garbage, wrong alphabets, etc.)
            if (results.result == null || results.result == Language.UNKNOWN) {
              skippedHolder[0]++;
              return;
            }

            totalHolder[0]++;
            charsHolder[0] += chunk.length();
            boolean correct = isEquivalent(results.result, expectedLang);
            if (correct) {
              correctHolder[0]++;
            } else {
              if (results.scores.numWords <= 2) {
                shortMissHolder[0]++;
              }
              Language detectedLang = results.result;
              detectedCounts.computeIfAbsent(detectedLang, k -> new int[1])[0]++;
              if (showMisses || sampledMisses > 0) {
                int lineLimit = sampledMisses > 0 ? 256 : 80;
                String snippet =
                    chunk.length() > lineLimit
                        ? chunk.subSequence(0, lineLimit).toString() + "..."
                        : chunk.toString();
                snippet = snippet.replace('\n', ' ');
                String detected = results.result != null ? results.result.isoCode() : "null";
                String missLine =
                    String.format(
                        "  MISS [expected=%s detected=%s] scores={%s} \"%s\"",
                        expectedLang.isoCode(), detected, results, snippet);
                if (showMisses) {
                  if (lastMiss[0].isEmpty() || Math.random() < 0.2) {
                    lastMiss[0] = missLine;
                  }
                }
                if (sampledMisses > 0) {
                  long n = ++totalMissCount[0];
                  if (n <= sampledMisses) {
                    reservoir.add(missLine);
                  } else {
                    int r = rng.nextInt((int) Math.min(n, Integer.MAX_VALUE));
                    if (r < sampledMisses) {
                      reservoir.set(r, missLine);
                    }
                  }
                }
              }
            }

            if (chunkCount[0] >= nextLog[0]) {
              int missCount = totalHolder[0] - correctHolder[0];
              double pct = totalHolder[0] > 0 ? 100.0 * correctHolder[0] / totalHolder[0] : 0;
              double timeSec = (System.currentTimeMillis() - startTime) / 1000.0;
              System.err.printf(
                  "%s: %d %s processed, %d/%d correct (%.1f%%),"
                      + " %d misses (%d short), %d skipped,"
                      + " avg %d chars %d words [%.1fs] (%.2fMw/s)%n",
                  expectedLang.isoCode(),
                  chunkCount[0],
                  byParagraph ? "paragraphs" : "articles",
                  correctHolder[0],
                  totalHolder[0],
                  pct,
                  missCount,
                  shortMissHolder[0],
                  skippedHolder[0],
                  totalHolder[0] > 0 ? charsHolder[0] / totalHolder[0] : 0,
                  totalHolder[0] > 0 ? wordsHolder[0] / totalHolder[0] : 0,
                  timeSec,
                  timeSec > 0.0 ? wordsHolder[0] / timeSec / 1_000_000.0 : 0.0);
              if (!lastMiss[0].isEmpty()) {
                System.err.println(lastMiss[0]);
                lastMiss[0] = "";
              }
              nextLog[0] += 1000;
            }
          };

      // Producer-consumer: producer thread does XML parsing + text cleanup + paragraph
      // splitting, snapshots each chunk to a String and enqueues it. The main thread
      // dequeues and runs detection (evalChunk) so the two halves overlap.
      BlockingQueue<String> queue = new ArrayBlockingQueue<>(1024);

      // Build the producer's wrapping chain: terminal enqueues snapshots.
      Consumer<CharSequence> enqueue =
          (chunk) -> {
            if (chunk.length() < 20) return;
            try {
              queue.put(chunk.toString());
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          };

      BiConsumer<CharSequence, Language> evalConsumer;
      if (byParagraph) {
        evalConsumer =
            (text, lang) -> {
              // Split on blank lines (two or more consecutive newlines)
              int len = text.length();
              int start = 0;
              for (int i = 0; i < len - 1; i++) {
                if (text.charAt(i) == '\n' && text.charAt(i + 1) == '\n') {
                  if (i > start) {
                    enqueue.accept(text.subSequence(start, i));
                  }
                  // Skip past all consecutive newlines
                  i += 2;
                  while (i < len && text.charAt(i) == '\n') {
                    i++;
                  }
                  start = i;
                }
              }
              if (start < len) {
                enqueue.accept(text.subSequence(start, len));
              }
            };
      } else {
        evalConsumer = (text, lang) -> enqueue.accept(text);
      }

      System.err.printf(
          "Processing %s (%s)%s...%n",
          lf.first(), expectedLang.isoCode(), byParagraph ? " (paragraph mode)" : "");

      // Start producer thread: XML extraction + wiki markup removal + accent removal.
      BiConsumer<CharSequence, Language> wrapped = evalConsumer;
      if (removeAccents) {
        wrapped = ContentUtils.wrapRemoveAccents(wrapped);
      }
      wrapped = ContentUtils.wrapRemoveWikiMarkup(wrapped);
      // Wrap with limit check so we skip all expensive work once limit is hit.
      BiConsumer<CharSequence, Language> innerChain = wrapped;
      BiConsumer<CharSequence, Language> producerChain =
          (text, lang) -> {
            if (!limitReached[0]) {
              innerChain.accept(text, lang);
            }
          };
      Exception[] producerError = {null};
      InputStream[] producerStream = {null};
      Thread producer =
          new Thread(
              () -> {
                try {
                  InputStream in = ContentUtils.openCompressed(lf.first());
                  producerStream[0] = in;
                  try {
                    ContentUtils.extract(in, expectedLang, producerChain);
                  } finally {
                    in.close();
                  }
                } catch (Exception e) {
                  if (!limitReached[0]) {
                    producerError[0] = e;
                  }
                } finally {
                  try {
                    queue.put(POISON);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }
              },
              "wikieval-producer");
      producer.start();

      // Consumer loop: main thread runs detection.
      while (true) {
        String chunk = queue.take();
        if (chunk == POISON) break;
        evalChunk.accept(chunk);
        if (limitReached[0]) break;
      }
      if (limitReached[0]) {
        // Close the input stream to abort XML parsing, then drain the queue
        // so the producer's finally block can enqueue the poison pill.
        InputStream ps = producerStream[0];
        if (ps != null) {
          ps.close();
        }
        queue.clear();
      }
      producer.join();
      if (producerError[0] != null && !limitReached[0]) {
        throw producerError[0];
      }
      if (limitReached[0]) break;
    }

    // Print summary
    System.err.println();
    String unit = byParagraph ? "paragraphs" : "articles";
    int overallShortMiss = 0;
    int overallSkipped = 0;
    for (Map.Entry<Language, int[]> entry : totalByLang.entrySet()) {
      Language lang = entry.getKey();
      int total = entry.getValue()[0];
      int correct = correctByLang.getOrDefault(lang, new int[1])[0];
      long chars = charsByLang.getOrDefault(lang, new long[1])[0];
      long words = wordsByLang.getOrDefault(lang, new long[1])[0];
      int shortMiss = shortMissByLang.getOrDefault(lang, new int[1])[0];
      int skipped = skippedByLang.getOrDefault(lang, new int[1])[0];
      int missCount = total - correct;
      double pct = total > 0 ? 100.0 * correct / total : 0;
      long avgChars = total > 0 ? chars / total : 0;
      long avgWords = total > 0 ? words / total : 0;
      System.err.printf(
          "%s: %d/%d %s correct (%.1f%%), %d misses (%d short), %d skipped,"
              + " avg %d chars %d words%n",
          lang.isoCode(),
          correct,
          total,
          unit,
          pct,
          missCount,
          shortMiss,
          skipped,
          avgChars,
          avgWords);
      overallShortMiss += shortMiss;
      overallSkipped += skipped;
      overallCorrect += correct;
      overallTotal += total;
      overallChars += chars;

      // Print misclassification breakdown sorted by count descending
      Map<Language, int[]> detected = missByDetectedLang.get(lang);
      if (detected != null && !detected.isEmpty()) {
        List<Map.Entry<Language, int[]>> sorted = new ArrayList<>(detected.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
        StringBuilder sb = new StringBuilder("  misclassified as: ");
        for (int i = 0; i < sorted.size(); i++) {
          if (i > 0) sb.append(", ");
          Language det = sorted.get(i).getKey();
          sb.append(det == Language.UNKNOWN ? "Unknown" : det.isoCode())
              .append(": ")
              .append(sorted.get(i).getValue()[0]);
        }
        System.err.println(sb);
      }
    }

    if (overallTotal > 0) {
      double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
      double overallPct = 100.0 * overallCorrect / overallTotal;
      int overallMiss = overallTotal - overallCorrect;
      long overallAvgChars = overallChars / overallTotal;
      long overallWords = 0;
      StringJoiner langList = new StringJoiner(",");
      for (Map.Entry<Language, int[]> entry : totalByLang.entrySet()) {
        langList.add(entry.getKey().isoCode());
        overallWords += wordsByLang.getOrDefault(entry.getKey(), new long[1])[0];
      }
      System.err.printf(
          "%nOverall: (%s) %d/%d %s correct (%.1f%%), %d misses (%d short), %d skipped,"
              + " avg %d chars, %.1fs (%.2fMw/s)%n",
          langList,
          overallCorrect,
          overallTotal,
          unit,
          overallPct,
          overallMiss,
          overallShortMiss,
          overallSkipped,
          overallAvgChars,
          elapsed,
          elapsed > 0.0 ? overallWords / elapsed / 1_000_000.0 : 0.0);
    }

    if (reservoir != null && !reservoir.isEmpty()) {
      System.err.printf("%nSampled misses (%d of %d):%n", reservoir.size(), totalMissCount[0]);
      for (String miss : reservoir) {
        System.err.println(miss);
      }
    }
  }

  private static void detectSpeedCommand(
      Detector detector, String testDir, boolean removeAccents, int durationSec) throws Exception {
    Model model = detector.model();
    AccentRemover accentRemover = removeAccents ? new AccentRemover() : null;
    Language[] modelLangs = model.getLanguages();

    // Load all phrases into memory
    List<String> phrases = new ArrayList<>();
    for (Language expectedLang : modelLangs) {
      String isoCode = expectedLang.isChinese() ? "zh" : expectedLang.isoCode();
      File testFile = new File(testDir, isoCode + ".txt");
      if (!testFile.exists()) {
        System.err.printf(
            "Warning: test file not found: %s, skipping %s%n", testFile.getPath(), isoCode);
        continue;
      }
      int count = 0;
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(new FileInputStream(testFile), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank()) continue;
          if (accentRemover != null) {
            line = accentRemover.remove(line).toString();
          }
          phrases.add(line);
          count++;
        }
      }
      System.err.printf("  loaded %d phrases for %s%n", count, isoCode);
    }
    if (phrases.isEmpty()) {
      System.err.println("Error: no phrases loaded");
      return;
    }
    System.err.printf(
        "Detect speed benchmark: %d phrases, %d languages, duration %ds%n",
        phrases.size(), modelLangs.length, durationSec);

    // Warmup: one pass through all phrases
    System.err.println("Warmup...");
    for (String phrase : phrases) {
      detector.detect(phrase);
    }

    // Benchmark loop
    long deadlineNs = System.nanoTime() + durationSec * 1_000_000_000L;
    long intervalNs = 5_000_000_000L; // 5 seconds
    long nextReportNs = System.nanoTime() + intervalNs;
    long totalWords = 0;
    long totalDetections = 0;
    long intervalWords = 0;
    long intervalDetections = 0;
    long intervalStartNs = System.nanoTime();
    long benchStartNs = intervalStartNs;
    int phraseIdx = 0;

    while (System.nanoTime() < deadlineNs) {
      detector.detect(phrases.get(phraseIdx));
      long words = detector.results().scores.numWords;
      totalWords += words;
      totalDetections++;
      intervalWords += words;
      intervalDetections++;

      phraseIdx++;
      if (phraseIdx >= phrases.size()) {
        phraseIdx = 0;
      }

      long now = System.nanoTime();
      if (now >= nextReportNs) {
        double intervalSec = (now - intervalStartNs) / 1_000_000_000.0;
        double mWordsPerSec = intervalWords / intervalSec / 1_000_000.0;
        double nsPerWord = intervalWords > 0 ? (double) (now - intervalStartNs) / intervalWords : 0;
        double totalSec = (now - benchStartNs) / 1_000_000_000.0;
        double totalMwPerSec = totalWords / totalSec / 1_000_000.0;
        System.err.printf(
            "  %.0fs: %.2f Mwords/s  %.0f ns/word  (%d phrases, cumulative: %.2f Mwords/s)%n",
            totalSec, mWordsPerSec, nsPerWord, totalDetections, totalMwPerSec);
        intervalWords = 0;
        intervalDetections = 0;
        intervalStartNs = now;
        nextReportNs = now + intervalNs;
      }
    }

    long endNs = System.nanoTime();
    double totalSec = (endNs - benchStartNs) / 1_000_000_000.0;
    double mWordsPerSec = totalWords / totalSec / 1_000_000.0;
    double nsPerWord = totalWords > 0 ? (double) (endNs - benchStartNs) / totalWords : 0;
    System.err.printf(
        "%nFinal: %d detections, %d words in %.1fs%n", totalDetections, totalWords, totalSec);
    System.err.printf("  %.2f Mwords/s  %.0f ns/word%n", mWordsPerSec, nsPerWord);
  }

  private static void modelLoadingBenchmarkCommand(
      List<Language> languages,
      String modelArg,
      double minLogProb,
      double twMinLogProb,
      double cjMinLogProb,
      int durationSec)
      throws Exception {
    System.err.printf(
        "Model loading benchmark: %d languages, model=%s, duration %ds%n",
        languages.size(), modelArg, durationSec);
    System.err.printf(
        "  minLogProb=%.1f twMinLogProb=%s cjMinLogProb=%.1f%n",
        minLogProb,
        Double.isNaN(twMinLogProb) ? "skipped" : String.valueOf(twMinLogProb),
        cjMinLogProb);

    // Warmup run with memory measurement
    System.err.println("Warmup...");
    Runtime rt = Runtime.getRuntime();
    System.gc();
    long heapBefore = rt.totalMemory() - rt.freeMemory();
    loadModel(modelArg, languages, minLogProb, twMinLogProb, cjMinLogProb);
    System.gc();
    long heapAfter = rt.totalMemory() - rt.freeMemory();
    double modelMB = (heapAfter - heapBefore) / (1024.0 * 1024.0);
    System.err.printf("** MODEL MEMORY: %.1fMB%n", modelMB);
    Model.clearCache();
    CJClassifier.clearCachedModels();

    long deadline = System.currentTimeMillis() + durationSec * 1000L;
    int iteration = 0;
    double totalSec = 0;
    double minSec = Double.MAX_VALUE;
    double maxSec = 0;

    while (System.currentTimeMillis() < deadline) {
      iteration++;
      long t0 = System.nanoTime();
      loadModel(modelArg, languages, minLogProb, twMinLogProb, cjMinLogProb);
      double sec = (System.nanoTime() - t0) / 1_000_000_000.0;
      Model.clearCache();
      CJClassifier.clearCachedModels();

      totalSec += sec;
      minSec = Math.min(minSec, sec);
      maxSec = Math.max(maxSec, sec);
      System.err.printf("  iteration %d: %.3fs%n", iteration, sec);
    }

    if (iteration > 0) {
      double avgSec = totalSec / iteration;
      System.err.printf("%nBenchmark results: %d iterations in %.1fs%n", iteration, totalSec);
      System.err.printf("  avg=%.3fs  min=%.3fs  max=%.3fs%n", avgSec, minSec, maxSec);
    }
  }

  // ========================================================================
  // CLI utilities
  // ========================================================================

  /** Configures detector accuracy params. stopIfNgramCovered/maxNgram <= 0 means use default. */
  private static void configureAccuracy(
      Detector detector,
      Model model,
      int minNgram,
      int maxNgram,
      int stopIfNgramCovered,
      boolean useTopwords) {
    int effectiveMax = maxNgram > 0 ? Math.min(maxNgram, model.getMaxNgram()) : model.getMaxNgram();
    int effectiveStop = stopIfNgramCovered > 0 ? stopIfNgramCovered : Math.min(3, effectiveMax);
    detector.setAccuracyParams(minNgram, effectiveStop, effectiveMax, useTopwords);
  }

  /**
   * Parses the common model/accuracy CLI args and builds a configured Detector. The common args
   * are: --languages, --model, --minlogprob, --twminlogprob, --maxngram, --minngram,
   * --stopifngramcovered, --skiptopwords. --model defaults to "full" if omitted. Exits with an
   * error if --languages is missing.
   */
  private static Detector buildDetector(String[] args) throws Exception {
    String langsArg = getArg(args, "languages");
    String modelArg = getArg(args, "model");
    if (modelArg == null) {
      modelArg = "full";
    }
    if (langsArg == null) {
      System.err.println("Error: --languages is required");
      printUsage();
      System.exit(1);
    }
    List<Language> languages = Language.fromCommaSeparated(langsArg);
    if (languages.isEmpty()) {
      System.err.println("Error: no valid languages in: " + langsArg);
      System.exit(1);
    }
    String minLogProbStr = getArg(args, "minlogprob");
    double minLogProb = minLogProbStr != null ? Double.parseDouble(minLogProbStr) : 0;
    String twMinLogProbStr = getArg(args, "twminlogprob");
    double twMinLogProb = twMinLogProbStr != null ? Double.parseDouble(twMinLogProbStr) : 0;
    String cjMinLogProbStr = getArg(args, "cjminlogprob");
    double cjMinLogProb = cjMinLogProbStr != null ? Double.parseDouble(cjMinLogProbStr) : 0;
    boolean useTopwords = true;
    if (hasFlag(args, "skiptopwords")) {
      twMinLogProb = Double.NaN;
      useTopwords = false;
    }
    String maxNgramStr = getArg(args, "maxngram");
    int maxNgram = maxNgramStr != null ? Integer.parseInt(maxNgramStr) : 0;
    String minNgramStr = getArg(args, "minngram");
    int minNgram = minNgramStr != null ? Integer.parseInt(minNgramStr) : 1;
    String stopStr = getArg(args, "stopifngramcovered");
    int stopIfNgramCovered = stopStr != null ? Integer.parseInt(stopStr) : 0;

    Model model = loadModel(modelArg, languages, minLogProb, twMinLogProb, cjMinLogProb);
    Detector detector = new Detector(model);
    configureAccuracy(detector, model, minNgram, maxNgram, stopIfNgramCovered, useTopwords);
    return detector;
  }

  /**
   * Loads a Model from "lite", "full" (classpath or Maven module resources), or a filesystem path.
   */
  private static Model loadModel(
      String modelArg,
      List<Language> languages,
      double minLogProb,
      double twMinLogProb,
      double cjMinLogProb)
      throws IOException {
    if ("lite".equals(modelArg) || "full".equals(modelArg)) {
      try {
        if ("lite".equals(modelArg)) {
          return Model.loadLite(languages);
        } else {
          return Model.loadFull(languages);
        }
      } catch (FileNotFoundException e) {
        // Classpath not available (e.g. running from fat jar); try Maven module resources.
        String resourcePath =
            "models-"
                + modelArg
                + "/src/main/resources/com/jlpka/langidentify/models/"
                + modelArg
                + "/";
        if (new File(resourcePath).isDirectory()) {
          double mlp = "lite".equals(modelArg) ? -12 : minLogProb;
          double tmlp = "lite".equals(modelArg) ? -12 : twMinLogProb;
          return Model.loadFromPath(resourcePath, languages, mlp, tmlp, cjMinLogProb);
        }
        throw new IOException(
            "Model '"
                + modelArg
                + "' not found on classpath or filesystem. "
                + "Use a filesystem path, or add langidentify-models-"
                + modelArg
                + " to the classpath.",
            e);
      }
    } else {
      return Model.loadFromPath(modelArg, languages, minLogProb, twMinLogProb, cjMinLogProb);
    }
  }

  private static String getArg(String[] args, String name) {
    String flag = "--" + name;
    for (int i = 0; i < args.length - 1; i++) {
      if (flag.equals(args[i])) {
        return args[i + 1];
      }
    }
    return null;
  }

  private static boolean hasFlag(String[] args, String name) {
    String flag = "--" + name;
    for (String arg : args) {
      if (flag.equals(arg)) return true;
    }
    return false;
  }

  /**
   * Checks for unrecognized --flags and exits with an error if any are found.
   *
   * @param args the full args array (args[0] is the command name)
   * @param validFlags set of valid flag names (without the "--" prefix)
   */
  private static void checkUnrecognizedArgs(String[] args, Set<String> validFlags) {
    // Flags whose values are separate args (--flag value); skip the value too.
    Set<String> valuedFlags =
        Set.of(
            "languages",
            "model",
            "testdir",
            "minlogprob",
            "twminlogprob",
            "maxngram",
            "minngram",
            "stopifngramcovered",
            "limit",
            "duration",
            "sampledmisses",
            "infiles",
            "cjminlogprob",
            "modelfile",
            "minrun",
            "phrase");
    for (int i = 1; i < args.length; i++) {
      if (args[i].startsWith("--")) {
        String name = args[i].substring(2);
        if (!validFlags.contains(name)) {
          System.err.println("Error: unrecognized option: " + args[i]);
          printUsage();
          System.exit(1);
        }
        // Skip the value argument for valued flags
        if (valuedFlags.contains(name) && i + 1 < args.length) {
          i++;
        }
      } else {
        System.err.println("Error: unexpected argument: " + args[i]);
        printUsage();
        System.exit(1);
      }
    }
  }

  private static void printUsage() {
    p("Usage: EvalTool <command> [options]");
    p("");
    p("Commands:");

    p("  phraseeval    Evaluate detection accuracy on test phrase files");
    p("                --testdir <dir>       {lang}.txt test files (required)");
    p("                --misses              Print individual miss details");
    printModelOpts();
    printDetectionOpts();
    p("                --removeaccents       Remove accents/ligatures before detection");

    p("");
    p("  wikieval      Evaluate detection accuracy on Wikipedia dumps");
    p("                --infiles <list>      file:lang pairs, e.g. wiki-en.bz2:en (required)");
    p("                --byparagraph         Evaluate per-paragraph instead of per-article");
    p("                --misses              Print miss details incrementally");
    p("                --sampledmisses <n>   Reservoir-sample n misses, print at end");
    p("                --limit <n>           Max articles/paragraphs to process");
    printModelOpts();
    printDetectionOpts();
    p("                --removeaccents       Remove accents/ligatures before detection");

    p("");
    p("  adhoc         Detect language of a single phrase");
    p("                --phrase <text>       The phrase to detect (required)");
    printModelOpts();
    printDetectionOpts();

    p("");
    p("  loadmodelspeed  Benchmark model loading time");
    printModelOpts();
    p("                --duration <sec>      Benchmark duration in seconds (default 30)");

    p("");
    p("  detectspeed   Benchmark detection throughput (Mwords/s)");
    p("                --testdir <dir>       {lang}.txt test files (required)");
    printModelOpts();
    printDetectionOpts();
    p("                --removeaccents       Remove accents/ligatures before detection");
    p("                --duration <sec>      Benchmark duration in seconds (default 30)");
  }

  private static void printModelOpts() {
    p("                --languages <list>    Comma-separated language codes (required)");
    p(
        "                --model <name>        \"lite\", \"full\", or filesystem path (default:"
            + " full)");
    p("                --minlogprob <val>    Min log-prob threshold (0=auto)");
    p("                --twminlogprob <val>  Min topword log-prob threshold (0=auto)");
    p("                --cjminlogprob <val>  CJ classifier log-prob floor (0=use file value)");
    p("                --maxngram <n>        Max ngram size for detection (0=auto)");
    p("                --skiptopwords        Skip loading topwords data");
  }

  private static void printDetectionOpts() {
    p("                --minngram <n>        Min ngram size for detection (default 1)");
    p("                --stopifngramcovered <n>  Stop lower ngrams if covered (default 3)");
  }

  private static void p(String s) {
    System.err.println(s);
  }

  // ========================================================================
  // Main
  // ========================================================================

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      printUsage();
      System.exit(1);
    }

    String command = args[0];
    if (command.equals("phraseeval")) {
      checkUnrecognizedArgs(
          args,
          Set.of(
              "languages",
              "model",
              "testdir",
              "minlogprob",
              "twminlogprob",
              "cjminlogprob",
              "maxngram",
              "minngram",
              "stopifngramcovered",
              "misses",
              "removeaccents",
              "skiptopwords"));
      String testDir = getArg(args, "testdir");
      if (testDir == null) {
        System.err.println("Error: phraseeval requires --testdir");
        System.exit(1);
      }
      Detector detector = buildDetector(args);
      phraseEvalCommand(detector, testDir, hasFlag(args, "removeaccents"), hasFlag(args, "misses"));
    } else if (command.equals("wikieval")) {
      checkUnrecognizedArgs(
          args,
          Set.of(
              "languages",
              "model",
              "infiles",
              "minlogprob",
              "twminlogprob",
              "cjminlogprob",
              "maxngram",
              "minngram",
              "stopifngramcovered",
              "limit",
              "byparagraph",
              "removeaccents",
              "misses",
              "sampledmisses",
              "skiptopwords"));
      String infilesArg = getArg(args, "infiles");
      if (infilesArg == null) {
        System.err.println("Error: wikieval requires --infiles");
        System.exit(1);
      }
      String limitStr = getArg(args, "limit");
      long evalLimit = limitStr != null ? Long.parseLong(limitStr) : Long.MAX_VALUE;
      String sampledMissesStr = getArg(args, "sampledmisses");
      int sampledMisses = sampledMissesStr != null ? Integer.parseInt(sampledMissesStr) : 0;

      List<Pair<String, Language>> langFiles = new ArrayList<>();
      for (String entry : infilesArg.split(",")) {
        int colonIdx = entry.lastIndexOf(':');
        if (colonIdx < 0) {
          System.err.println(
              "Error: each infile must be followed by :language_code, got: " + entry);
          System.exit(1);
        }
        String path = entry.substring(0, colonIdx);
        Language lang = Language.fromString(entry.substring(colonIdx + 1));
        if (lang == Language.UNKNOWN) {
          System.err.println("Error: unknown language code in: " + entry);
          System.exit(1);
        }
        langFiles.add(new Pair<>(path, lang));
      }
      Detector detector = buildDetector(args);
      wikiEvalCommand(
          detector,
          langFiles,
          evalLimit,
          hasFlag(args, "byparagraph"),
          hasFlag(args, "removeaccents"),
          hasFlag(args, "misses"),
          sampledMisses);
    } else if (command.equals("detectspeed")) {
      checkUnrecognizedArgs(
          args,
          Set.of(
              "languages",
              "model",
              "testdir",
              "minlogprob",
              "twminlogprob",
              "cjminlogprob",
              "maxngram",
              "minngram",
              "stopifngramcovered",
              "removeaccents",
              "skiptopwords",
              "duration"));
      String testDir = getArg(args, "testdir");
      if (testDir == null) {
        System.err.println("Error: detectspeed requires --testdir");
        System.exit(1);
      }
      String durationStr = getArg(args, "duration");
      int duration = durationStr != null ? Integer.parseInt(durationStr) : 30;
      Detector detector = buildDetector(args);
      detectSpeedCommand(detector, testDir, hasFlag(args, "removeaccents"), duration);
    } else if (command.equals("loadmodelspeed")) {
      checkUnrecognizedArgs(
          args,
          Set.of(
              "languages",
              "model",
              "minlogprob",
              "twminlogprob",
              "cjminlogprob",
              "skiptopwords",
              "duration"));
      String langsArg = getArg(args, "languages");
      String modelArg = getArg(args, "model");
      String minLogProbStr = getArg(args, "minlogprob");
      double minLogProb = minLogProbStr != null ? Double.parseDouble(minLogProbStr) : 0;
      String twMinLogProbStr = getArg(args, "twminlogprob");
      double twMinLogProb = twMinLogProbStr != null ? Double.parseDouble(twMinLogProbStr) : 0;
      String cjMinLogProbStr = getArg(args, "cjminlogprob");
      double cjMinLogProb = cjMinLogProbStr != null ? Double.parseDouble(cjMinLogProbStr) : 0;
      if (hasFlag(args, "skiptopwords")) {
        twMinLogProb = Double.NaN;
      }
      String durationStr = getArg(args, "duration");
      int duration = durationStr != null ? Integer.parseInt(durationStr) : 30;

      if (modelArg == null) {
        modelArg = "full";
      }
      if (langsArg == null) {
        System.err.println("Error: loadmodelspeed requires --languages");
        System.exit(1);
      }
      List<Language> languages = Language.fromCommaSeparated(langsArg);
      if (languages.isEmpty()) {
        System.err.println("Error: no valid languages in: " + langsArg);
        System.exit(1);
      }
      modelLoadingBenchmarkCommand(
          languages, modelArg, minLogProb, twMinLogProb, cjMinLogProb, duration);
    } else if (command.equals("adhoc")) {
      checkUnrecognizedArgs(
          args,
          Set.of(
              "languages",
              "model",
              "minlogprob",
              "twminlogprob",
              "cjminlogprob",
              "maxngram",
              "minngram",
              "stopifngramcovered",
              "skiptopwords",
              "phrase"));
      String phrase = getArg(args, "phrase");
      if (phrase == null) {
        System.err.println("Error: adhoc requires --phrase");
        System.exit(1);
      }
      Detector detector = buildDetector(args);
      adhocCommand(detector, phrase);
    } else {
      System.err.println("Unknown command: " + command);
      printUsage();
      System.exit(1);
    }
  }
}
