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

package com.jlpka.langidentify.tools;

import com.jlpka.langidentify.Alphabet;
import com.jlpka.langidentify.Language;
import com.jlpka.langidentify.NgramTable;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;
import javax.xml.stream.*;

/**
 * Builds language detection models from Wikipedia XML dump files. Run with no arguments for usage.
 *
 * <pre>
 * # The shade plugin produces a fat jar (main class is EvalTool by default);
 * # invoke ModelBuilder explicitly:
 * export INVOKEBUILDER="java -cp tools/target/langidentify-tools-1.0.jar  com.jlpka.langidentify.tools.ModelBuilder"
 * export WIKIDERIVED="../wikidata/derived"
 * export WIKIORIG="/Volumes/devdata/wikidata/orig"
 *
 * # Extract ngrams + topwords for a single language:
 * $INVOKEBUILDER ngrams --infile $WIKIORIG/frwiki-pages-articles.xml.bz2 \
 *     --outfile $WIKIDERIVED/ngrams-fr.txt --topwords $WIKIDERIVED/topwords-fr.txt \
 *     --alphabet LATIN --minlogprob -18 --twminlogprob -15
 *
 * # Batch-extract for all languages sharing an alphabet:
 * alpha=cyrillic; for l in `$INVOKEBUILDER showlanguages --alphabet $alpha`; do \
 *     $INVOKEBUILDER ngrams --infile $WIKIORIG/${l}wiki-pages-articles.xml.bz2 \
 *     --outfile $WIKIDERIVED/ngrams-$l.txt --topwords $WIKIDERIVED/topwords-$l.txt \
 *     --alphabet $alpha --minlogprob -18 --twminlogprob -15; done
 *
 * # Filter out common English topwords from another language's topwords:
 * $INVOKEBUILDER ngrams --infile $WIKIORIG/${lang}wiki-pages-articles.xml.bz2 \
 *     --outfile $WIKIDERIVED/ngrams-$lang.txt --topwords $WIKIDERIVED/topwords-$lang.txt \
 *     --alphabet LATIN --minlogprob -18 --twminlogprob -15 \
 *     --skipwords $WIKIDERIVED/topwords-en.txt/100
 *
 * # Reduce ngrams/topwords to smaller models for production:
 * $INVOKEBUILDER reducengrams --infile $WIKIDERIVED/ngrams-fr.txt \
 *     --outfile $WIKIDERIVED/sm-ngrams-fr.txt.gz --minlogprob -12.0 --maxngram 3
 * $INVOKEBUILDER reducetopwords --infile $WIKIDERIVED/topwords-fr.txt \
 *     --outfile $WIKIDERIVED/sm-topwords-fr.txt.gz --twminlogprob -10.0
 *
 * # Inspect raw Wikipedia text:
 * $INVOKEBUILDER showwiki --infile $WIKIORIG/frwiki-pages-articles.xml.bz2
 *
 * # List supported languages:
 * $INVOKEBUILDER showlanguages
 * $INVOKEBUILDER showlanguages --alphabet latin
 * </pre>
 */
public class ModelBuilder {

  private static void showWikiCommand(String infile, String outfile, boolean noMarkup)
      throws Exception {
    PrintWriter out =
        outfile != null
            ? new PrintWriter(
                new BufferedWriter(new FileWriter(outfile, StandardCharsets.UTF_8), 1024 * 1024))
            : new PrintWriter(
                new BufferedWriter(
                    new OutputStreamWriter(System.out, StandardCharsets.UTF_8), 1024 * 1024));

    long[] pageCount = {0};
    long startTime = System.currentTimeMillis();

    BiConsumer<CharSequence, Language> printer =
        (text, lang) -> {
          if (pageCount[0] > 0) {
            out.print("\n\n");
          }
          out.print(text);
          pageCount[0]++;

          if (pageCount[0] % 10000 == 0) {
            System.err.printf("Processed %d pages...%n", pageCount[0]);
          }
        };
    if (noMarkup) {
      printer = ContentUtils.wrapRemoveWikiMarkup(printer);
    }

    try (InputStream in = ContentUtils.openCompressed(infile)) {
      ContentUtils.extract(in, null, printer);
    }

    out.flush();
    if (outfile != null) {
      out.close();
    }

    long elapsed = System.currentTimeMillis() - startTime;
    System.err.printf("Done. Extracted %d pages in %.2f seconds%n", pageCount[0], elapsed / 1000.0);
  }

  public static final int MAX_WORD_LENGTH = 64;

  private static void ngramsCommand(
      String infile,
      String outfile,
      Alphabet alpha,
      boolean gzip,
      double minLogProb,
      boolean emitTopwords,
      String topwordsOutfile,
      double twminlogprob,
      boolean twAsLogProb,
      Set<String> skipWords)
      throws Exception {
    long startTime = System.currentTimeMillis();
    long[] wordCount = {0};

    // Ngram counts: key is the ngram string (length 1..5), value is count
    Map<NgramTable.Ngram, AtomicLong> ngramCounts = new HashMap<>();
    // Total count of ngrams by size (index 0 unused, 1..5 used)
    long[] ngramTotals = new long[6];
    // Distinct ngram count by size (index 0 unused, 1..5 used)
    long[] ngramDistinct = new long[6];

    // Optional topwords accumulator
    Map<String, AtomicLong> wordCounts = emitTopwords ? new HashMap<>() : null;

    NgramTable.Ngram wb = new NgramTable.Ngram(new char[MAX_WORD_LENGTH], 0, 0);

    Consumer<CharSequence> perWord =
        (cs) -> {
          String word = cs.toString();
          if (skipWords.contains(word)) {
            return;
          }
          if (emitTopwords) {
            int apos = word.indexOf('\'');
            if (apos >= 0) {
              String part1 = word.substring(0, apos + 1);
              String part2 = word.substring(apos + 1);
              wordCounts.computeIfAbsent(part1, k -> new AtomicLong(0)).incrementAndGet();
              if (!part2.isEmpty()) {
                wordCounts.computeIfAbsent(part2, k -> new AtomicLong(0)).incrementAndGet();
              }
            } else {
              wordCounts.computeIfAbsent(word, k -> new AtomicLong(0)).incrementAndGet();
            }
          }
          int len = word.length();
          if (len < wb.chars.length) {
            for (int i = 0; i < len; ++i) {
              wb.chars[i] = cs.charAt(i);
            }
            for (int n = 1; n <= 5; n++) {
              wb.length = n;
              for (int i = 0; i <= len - n; i++) {
                wb.offset = i;
                AtomicLong count = ngramCounts.get(wb);
                if (count == null) {
                  count = ngramCounts.computeIfAbsent(wb.copy(), k -> new AtomicLong(0));
                }
                if (count.getAndIncrement() == 0) {
                  ngramDistinct[n]++;
                }
                ngramTotals[n]++;
              }
            }
          }
          if (++wordCount[0] % 1_000_000 == 0) {
            System.err.printf(
                "Processed %dM words... NgramCount: %d"
                    + "  1:%d/%d 2:%d/%d 3:%d/%d 4:%d/%d 5:%d/%d%n",
                wordCount[0] / 1_000_000,
                ngramCounts.size(),
                ngramDistinct[1],
                ngramTotals[1],
                ngramDistinct[2],
                ngramTotals[2],
                ngramDistinct[3],
                ngramTotals[3],
                ngramDistinct[4],
                ngramTotals[4],
                ngramDistinct[5],
                ngramTotals[5]);
          }
        };

    // Producer-consumer: producer thread does XML parsing and enqueues raw page text.
    // The main thread dequeues and runs wiki markup removal + segmentWords + perWord
    // so the two halves overlap.
    BlockingQueue<String> queue = new ArrayBlockingQueue<>(256);
    @SuppressWarnings("StringOperationCanBeSimplified")
    String poison = new String(""); // identity-compared sentinel

    BiConsumer<CharSequence, Language> producerCallback =
        (text, lang) -> {
          try {
            queue.put(text.toString());
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        };

    Exception[] producerError = {null};
    Thread producer =
        new Thread(
            () -> {
              try (InputStream in = ContentUtils.openCompressed(infile)) {
                ContentUtils.extract(in, null, producerCallback);
              } catch (Exception e) {
                producerError[0] = e;
              } finally {
                try {
                  queue.put(poison);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            },
            "ngrams-producer");
    producer.start();

    // Consumer loop: main thread runs wiki markup removal + word segmentation + counting.
    BiConsumer<CharSequence, Language> processArticle =
        ContentUtils.wrapRemoveWikiMarkup(
            (text, lang) -> ContentUtils.segmentWords(text, alpha, perWord));
    while (true) {
      String article = queue.take();
      if (article == poison) break; // identity comparison
      processArticle.accept(article, null);
    }
    producer.join();
    if (producerError[0] != null) {
      throw producerError[0];
    }

    // Compute per-size minimum count thresholds from minLogProb:
    //   log(count / total) >= floor  =>  count >= total * exp(floor)
    long[] minCount = new long[6];
    for (int n = 1; n <= 5; n++) {
      minCount[n] = (long) Math.ceil(ngramTotals[n] * Math.exp(minLogProb));
      if (minCount[n] < 1) minCount[n] = 1;
    }

    // Sort by ngram size first, then lexicographically within each size
    List<Map.Entry<NgramTable.Ngram, AtomicLong>> sorted = new ArrayList<>(ngramCounts.entrySet());
    sorted.sort(
        (a, b) -> {
          int cmp = Integer.compare(a.getKey().length, b.getKey().length);
          return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
        });

    // Recompute distinct counts post-filter
    long[] filteredDistinct = new long[6];
    for (Map.Entry<NgramTable.Ngram, AtomicLong> entry : sorted) {
      int n = entry.getKey().length;
      if (entry.getValue().get() >= minCount[n]) {
        filteredDistinct[n]++;
      }
    }

    // Write ngrams output
    OutputStream fileOut = new FileOutputStream(outfile);
    if (gzip) {
      fileOut = new GZIPOutputStream(fileOut);
    }
    try (PrintWriter out =
        new PrintWriter(
            new BufferedWriter(
                new OutputStreamWriter(fileOut, StandardCharsets.UTF_8), 1024 * 1024))) {
      // Header line: "# 1:distinct/total 2:distinct/total ... MinLogProb: -16.0"
      StringBuilder header = new StringBuilder("# ");
      for (int n = 1; n <= 5; n++) {
        if (n > 1) header.append(' ');
        header.append(n).append(':').append(filteredDistinct[n]).append('/').append(ngramTotals[n]);
      }
      header.append(String.format(" MinLogProb: %.1f", minLogProb));
      out.println(header);

      for (Map.Entry<NgramTable.Ngram, AtomicLong> entry : sorted) {
        int n = entry.getKey().length;
        if (entry.getValue().get() < minCount[n]) continue;
        out.print(entry.getKey().toString());
        out.print(' ');
        out.println(entry.getValue().get());
      }
    }

    long elapsed = System.currentTimeMillis() - startTime;
    System.err.printf(
        "Done. Extracted ngrams from %d words in %.2f seconds (minLogProb=%.1f)%n",
        wordCount[0], elapsed / 1000.0, minLogProb);
    for (int n = 1; n <= 5; n++) {
      System.err.printf(
          "  %d-grams: %d total, %d distinct, %d emitted (minCount=%d)%n",
          n, ngramTotals[n], ngramDistinct[n], filteredDistinct[n], minCount[n]);
    }

    // Write topwords if requested
    if (emitTopwords && topwordsOutfile != null) {
      BufferedWriter twOut =
          new BufferedWriter(new FileWriter(topwordsOutfile, StandardCharsets.UTF_8), 1024 * 1024);
      twOut.write(
          "# Count: " + wordCount[0] + String.format(" MinLogProb: %.1f", twminlogprob) + "\n");
      wordCounts.entrySet().stream()
          .sorted((e1, e2) -> Long.compare(e2.getValue().get(), e1.getValue().get()))
          .forEach(
              entry -> {
                try {
                  String word = entry.getKey();
                  if (word.length() == 1 && word.charAt(0) < 0x80) return;
                  double logProb = Math.log((double) entry.getValue().get() / wordCount[0]);
                  if (logProb >= twminlogprob) {
                    if (twAsLogProb) {
                      twOut.write(word + " " + String.format("%.6f", logProb) + "\n");
                    } else {
                      twOut.write(word + " " + entry.getValue().get() + "\n");
                    }
                  }
                } catch (IOException e) {
                  System.err.println("Error writing topword: " + e.getMessage());
                }
              });
      twOut.flush();
      twOut.close();
      System.err.printf("Wrote topwords to %s%n", topwordsOutfile);
    }
  }

  private static void reduceNgramsCommand(
      String infile, String outfile, double minLogProb, int maxNgram) throws Exception {
    long startTime = System.currentTimeMillis();

    long[] totals = new long[6]; // index 1..5: total occurrence counts from input header
    long[] filteredDistinct = new long[6]; // distinct ngrams emitted per size
    List<String> outputLines = new ArrayList<>();

    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(ContentUtils.openCompressed(infile), StandardCharsets.UTF_8),
            1024 * 1024)) {
      // Parse header: "# 1:distinct/total 2:distinct/total ... [MinLogProb: -16.0]"
      String header = reader.readLine();
      if (header == null) {
        throw new IllegalArgumentException("Empty ngram file: " + infile);
      }
      String headerBody = header.startsWith("# ") ? header.substring(2) : header;
      String[] headerParts = headerBody.split(" ");
      for (int pi = 0; pi < headerParts.length; pi++) {
        String part = headerParts[pi];
        if ("MinLogProb:".equals(part)) {
          pi++; // skip the value token
          continue;
        }
        int colonIdx = part.indexOf(':');
        if (colonIdx > 0) {
          int n = Integer.parseInt(part.substring(0, colonIdx));
          String value = part.substring(colonIdx + 1);
          int slashIdx = value.indexOf('/');
          long total = Long.parseLong(slashIdx >= 0 ? value.substring(slashIdx + 1) : value);
          if (n >= 1 && n <= 5) {
            totals[n] = total;
          }
        }
      }

      // Compute per-size minimum count thresholds:
      //   log(count / total) >= minLogProb  =>  count >= total * exp(minLogProb)
      long[] minCount = new long[6];
      for (int n = 1; n <= 5; n++) {
        minCount[n] = (long) Math.ceil(totals[n] * Math.exp(minLogProb));
        if (minCount[n] < 1) minCount[n] = 1;
      }

      // Stream data lines, filtering by maxNgram and minLogProb
      String line;
      long inputCount = 0;
      long skipCount = 0;
      while ((line = reader.readLine()) != null) {
        if (line.isEmpty()) continue;
        int spaceIdx = line.lastIndexOf(' ');
        if (spaceIdx <= 0) continue;

        int n = spaceIdx; // ngram length == position of space
        if (n > maxNgram) break; // file is sorted by size, so done
        if (n == 1 && line.charAt(0) < 0x80) {
          skipCount++;
          continue;
        }
        if (n < 1 || totals[n] == 0) {
          skipCount++;
          continue;
        }

        inputCount++;
        long count = Long.parseLong(line.substring(spaceIdx + 1));
        if (count < minCount[n]) {
          skipCount++;
          continue;
        }

        filteredDistinct[n]++;
        outputLines.add(line);
      }

      System.err.printf("Read %d ngrams, skipped %d%n", inputCount, skipCount);
    }

    // Determine if output should be gzipped based on file extension
    boolean gzip = outfile.endsWith(".gz");

    // Write output
    OutputStream fileOut = new FileOutputStream(outfile);
    if (gzip) {
      fileOut = new GZIPOutputStream(fileOut);
    }
    try (PrintWriter out =
        new PrintWriter(
            new BufferedWriter(
                new OutputStreamWriter(fileOut, StandardCharsets.UTF_8), 1024 * 1024))) {
      // Header line: "# 1:distinct/total 2:distinct/total ... MinLogProb: -16.0"
      StringBuilder header = new StringBuilder("# ");
      for (int n = 1; n <= maxNgram; n++) {
        if (n > 1) header.append(' ');
        header.append(n).append(':').append(filteredDistinct[n]).append('/').append(totals[n]);
      }
      header.append(String.format(" MinLogProb: %.1f", minLogProb));
      out.println(header);

      for (String line : outputLines) {
        out.println(line);
      }
    }

    long elapsed = System.currentTimeMillis() - startTime;
    long totalEmitted = 0;
    for (int n = 1; n <= maxNgram; n++) {
      totalEmitted += filteredDistinct[n];
    }
    System.err.printf(
        "Done. Reduced ngrams in %.2f seconds (minLogProb=%.1f, maxNgram=%d)%n",
        elapsed / 1000.0, minLogProb, maxNgram);
    for (int n = 1; n <= maxNgram; n++) {
      System.err.printf(
          "  %d-grams: %d total, %d emitted (minCount=%d)%n",
          n,
          totals[n],
          filteredDistinct[n],
          (long) Math.max(1, Math.ceil(totals[n] * Math.exp(minLogProb))));
    }
    System.err.printf(
        "Wrote %d ngrams to %s (%s)%n",
        totalEmitted, outfile, ContentUtils.humanBytes(new File(outfile).length()));
  }

  private static void reduceTopwordsCommand(String infile, String outfile, double twminlogprob)
      throws Exception {
    long startTime = System.currentTimeMillis();

    long totalCount = 0;
    List<String> outputLines = new ArrayList<>();

    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(ContentUtils.openCompressed(infile), StandardCharsets.UTF_8),
            1024 * 1024)) {
      // Parse header: "# Count: <total> MinLogProb: <val>"
      String header = reader.readLine();
      if (header == null || !header.startsWith("# Count: ")) {
        throw new IllegalArgumentException("Invalid topwords file (bad header): " + infile);
      }
      String afterCount = header.substring("# Count: ".length()).trim();
      int spIdx = afterCount.indexOf(' ');
      totalCount = Long.parseLong(spIdx >= 0 ? afterCount.substring(0, spIdx) : afterCount);

      // Stream data lines, filtering by twminlogprob and converting count to logprob
      String line;
      long inputCount = 0;
      long skipCount = 0;
      while ((line = reader.readLine()) != null) {
        if (line.isEmpty() || line.charAt(0) == '#') continue;
        int spaceIdx = line.lastIndexOf(' ');
        if (spaceIdx <= 0) continue;

        inputCount++;
        String word = line.substring(0, spaceIdx);
        String valueStr = line.substring(spaceIdx + 1);

        double logProb;
        if (valueStr.charAt(0) == '-') {
          // Already a logprob
          logProb = Double.parseDouble(valueStr);
        } else {
          long count = Long.parseLong(valueStr);
          logProb = Math.log((double) count / totalCount);
        }
        if (logProb < twminlogprob) {
          skipCount++;
          continue;
        }

        outputLines.add(String.format("%s %.6f", word, logProb));
      }

      System.err.printf("Read %d topwords, skipped %d%n", inputCount, skipCount);
    }

    // Write output
    boolean gzip = outfile.endsWith(".gz");
    OutputStream fileOut = new FileOutputStream(outfile);
    if (gzip) {
      fileOut = new GZIPOutputStream(fileOut);
    }
    try (PrintWriter out =
        new PrintWriter(
            new BufferedWriter(
                new OutputStreamWriter(fileOut, StandardCharsets.UTF_8), 1024 * 1024))) {
      out.printf("# Count: %d MinLogProb: %.1f%n", totalCount, twminlogprob);
      for (String line : outputLines) {
        out.println(line);
      }
    }

    long elapsed = System.currentTimeMillis() - startTime;
    System.err.printf(
        "Done. Reduced topwords in %.2f seconds (twminlogprob=%.1f)%n",
        elapsed / 1000.0, twminlogprob);
    System.err.printf(
        "Wrote %d topwords to %s (%s)%n",
        outputLines.size(), outfile, ContentUtils.humanBytes(new File(outfile).length()));
  }

  private static void showLanguagesCommand(String alphabetFilter, boolean fullName) {
    Alphabet alpha = alphabetFilter != null ? Alphabet.fromString(alphabetFilter) : null;
    if (alphabetFilter != null && (alpha == null || alpha == Alphabet.UNKNOWN)) {
      System.err.println("Error: unknown alphabet: " + alphabetFilter);
      System.exit(1);
    }

    List<Language> matches = new ArrayList<>();
    for (Language lang : Language.values()) {
      if (lang == Language.UNKNOWN) continue;
      if (alpha != null && !lang.getAlphabets().contains(alpha)) continue;
      matches.add(lang);
    }

    if (fullName) {
      for (Language lang : matches) {
        System.out.println(lang.name());
      }
    } else {
      StringJoiner sj = new StringJoiner(" ");
      for (Language lang : matches) {
        sj.add(lang.isoCode());
      }
      System.out.println(sj);
    }
  }

  // ========================================================================
  // CLI utilities
  // ========================================================================

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
   * Loads skip words from a comma-separated list of files. Each file contains one word per line.
   * Lines starting with '#' and empty lines are ignored. Only the text before the first space is
   * used as the word (to support topwords format).
   */
  private static Set<String> loadSkipWords(String skipwordsArg) throws IOException {
    Set<String> skipWords = new HashSet<>();
    if (skipwordsArg == null) {
      return skipWords;
    }
    for (String entry : skipwordsArg.split(",")) {
      entry = entry.trim();
      if (entry.isEmpty()) {
        continue;
      }
      // Optional /N suffix to limit to first N words from the file.
      String filename;
      int maxWords = Integer.MAX_VALUE;
      int slashIdx = entry.lastIndexOf('/');
      if (slashIdx > 0) {
        String suffix = entry.substring(slashIdx + 1);
        try {
          maxWords = Integer.parseInt(suffix);
          filename = entry.substring(0, slashIdx);
        } catch (NumberFormatException e) {
          // Not a number after slash — treat whole entry as filename.
          filename = entry;
        }
      } else {
        filename = entry;
      }
      int count = 0;
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(new FileInputStream(filename), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isEmpty() || line.startsWith("#")) {
            continue;
          }
          int space = line.indexOf(' ');
          String word = space >= 0 ? line.substring(0, space) : line;
          if (!word.isEmpty()) {
            skipWords.add(word);
            if (++count >= maxWords) {
              break;
            }
          }
        }
      }
    }
    if (!skipWords.isEmpty()) {
      System.err.printf("Loaded %d skip words%n", skipWords.size());
    }
    return skipWords;
  }

  private static void checkUnrecognizedArgs(String[] args, Set<String> validFlags) {
    Set<String> valuedFlags =
        Set.of(
            "infile",
            "outfile",
            "alphabet",
            "topwords",
            "twminlogprob",
            "minlogprob",
            "culllogprob",
            "maxngram",
            "infiles",
            "modelfile",
            "maxwordlength",
            "skipwords");
    for (int i = 1; i < args.length; i++) {
      if (args[i].startsWith("--")) {
        String name = args[i].substring(2);
        if (!validFlags.contains(name)) {
          System.err.println("Error: unrecognized option: " + args[i]);
          printUsage();
          System.exit(1);
        }
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
    p("Usage: ModelBuilder <command> [options]");
    p("");
    p("Commands:");
    p("  showwiki        Extract and display text from a Wikipedia XML dump");
    p("                  --infile <file>         Input file (required)");
    p("                  --outfile <file>        Output file (default: stdout)");
    p("                  --nomarkup              Strip wiki markup ([[]], {{}}, <>, URLs)");
    p("");
    p("  ngrams          Extract character n-grams (1..5) from words in a Wiki dump");
    p("                  --infile <file>         Input file (required)");
    p("                  --outfile <file>        Output file (required)");
    p("                  --alphabet <alpha>      Alphabet filter, e.g. LATIN (default LATIN)");
    p("                  --gzip                  Gzip the output file");
    p("                  --topwords <file>       Also emit topwords to this file");
    p("                  --minlogprob <val>      Min log-prob threshold (default -20)");
    p("                  --twminlogprob <val>    Min log-prob for topwords (default -20)");
    p("                  --skipwords <file>[/<n>]  Skip words from file (/<n> = top N)");
    p("                  --twaslogprob           Emit topwords as log-prob instead of counts");
    p("");
    p("  reducengrams    Filter an ngrams file by logprob and max ngram size");
    p("                  --infile <file>         Input ngrams file (required)");
    p("                  --outfile <file>        Output ngrams file (required, .gz for gzip)");
    p("                  --minlogprob <val>      Min log-prob threshold (default -16)");
    p("                  --maxngram <n>          Max ngram size to keep (default 5)");
    p("");
    p("  reducetopwords  Filter topwords by logprob, converting counts to log-probs");
    p("                  --infile <file>         Input topwords file (required)");
    p("                  --outfile <file>        Output topwords file (required, .gz for gzip)");
    p("                  --twminlogprob <val>    Min log-prob threshold (default -16)");
    p("");
    p("  showlanguages   List supported languages");
    p("                  --alphabet <alpha>      Filter by alphabet (e.g. LATIN, CYRILLIC)");
    p(
        "                  --fullname              Print enum names line-by-line instead of iso"
            + " codes");
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
    if (command.equals("showwiki")) {
      checkUnrecognizedArgs(args, Set.of("infile", "outfile", "nomarkup"));
      String infile = getArg(args, "infile");
      String outfile = getArg(args, "outfile");
      boolean noMarkup = hasFlag(args, "nomarkup");
      if (infile == null) {
        System.err.println("Error: show requires --infile");
        System.exit(1);
      }
      showWikiCommand(infile, outfile, noMarkup);
    } else if (command.equals("ngrams")) {
      checkUnrecognizedArgs(
          args,
          Set.of(
              "infile",
              "outfile",
              "alphabet",
              "gzip",
              "topwords",
              "minlogprob",
              "twminlogprob",
              "twaslogprob",
              "skipwords"));
      String infile = getArg(args, "infile");
      String outfile = getArg(args, "outfile");
      Alphabet alpha =
          hasFlag(args, "alphabet")
              ? Alphabet.fromString(getArg(args, "alphabet"))
              : Alphabet.LATIN;
      boolean gzip = hasFlag(args, "gzip");
      String topwordsOutfile = getArg(args, "topwords");
      boolean emitTopwords = topwordsOutfile != null;
      String minLogProbStr = getArg(args, "minlogprob");
      double minLogProb = minLogProbStr != null ? Double.parseDouble(minLogProbStr) : -20.0;
      String twminlogprobStr = getArg(args, "twminlogprob");
      double twminlogprob = twminlogprobStr != null ? Double.parseDouble(twminlogprobStr) : -20.0;
      boolean twAsLogProb = hasFlag(args, "twaslogprob");
      String skipwordsArg = getArg(args, "skipwords");
      Set<String> skipWords = loadSkipWords(skipwordsArg);
      if (infile == null || outfile == null) {
        System.err.println("Error: ngrams requires --infile and --outfile");
        System.exit(1);
      }
      ngramsCommand(
          infile,
          outfile,
          alpha,
          gzip,
          minLogProb,
          emitTopwords,
          topwordsOutfile,
          twminlogprob,
          twAsLogProb,
          skipWords);
    } else if (command.equals("reducengrams")) {
      checkUnrecognizedArgs(args, Set.of("infile", "outfile", "minlogprob", "maxngram"));
      String infile = getArg(args, "infile");
      String outfile = getArg(args, "outfile");
      String minLogProbStr = getArg(args, "minlogprob");
      double minLogProb = minLogProbStr != null ? Double.parseDouble(minLogProbStr) : -16.0;
      String maxNgramStr = getArg(args, "maxngram");
      int maxNgram = maxNgramStr != null ? Integer.parseInt(maxNgramStr) : 5;
      if (infile == null || outfile == null) {
        System.err.println("Error: reducengrams requires --infile and --outfile");
        System.exit(1);
      }
      reduceNgramsCommand(infile, outfile, minLogProb, maxNgram);
    } else if (command.equals("reducetopwords")) {
      checkUnrecognizedArgs(args, Set.of("infile", "outfile", "twminlogprob"));
      String infile = getArg(args, "infile");
      String outfile = getArg(args, "outfile");
      String twminlogprobStr = getArg(args, "twminlogprob");
      double twminlogprob = twminlogprobStr != null ? Double.parseDouble(twminlogprobStr) : -16.0;
      if (infile == null || outfile == null) {
        System.err.println("Error: reducetopwords requires --infile and --outfile");
        System.exit(1);
      }
      reduceTopwordsCommand(infile, outfile, twminlogprob);
    } else if (command.equals("showlanguages")) {
      checkUnrecognizedArgs(args, Set.of("alphabet", "fullname"));
      String alphabetFilter = getArg(args, "alphabet");
      boolean fullName = hasFlag(args, "fullname");
      showLanguagesCommand(alphabetFilter, fullName);
    } else {
      System.err.println("Unknown command: " + command);
      printUsage();
      System.exit(1);
    }
  }
}
