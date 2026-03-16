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

import com.jlpka.langidentify.AccentRemover;
import com.jlpka.langidentify.Alphabet;
import com.jlpka.langidentify.Language;
import java.io.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import javax.xml.stream.*;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

/**
 * Utilities for building language models from Wikipedia XML dumps. The pipeline is:
 *
 * <ol>
 *   <li>{@link #extract} — stream {@code <text>} elements from a MediaWiki XML dump
 *   <li>{@link #wrapRemoveWikiMarkup} — strip markup syntax ({@code [[]]}, {@code {{}}}, HTML tags,
 *       URLs, wiki tables, HTML entities) so that only natural-language prose remains
 *   <li>{@link #wrapRemoveAccents} — optionally normalize accented Latin characters to ASCII
 *   <li>{@link #segmentWords} — split prose into words by alphabet, filtering digits and
 *       normalizing apostrophes
 * </ol>
 *
 * <p>Markup removal is important because wiki syntax tokens (template names, link targets, HTML
 * attributes) are not natural language and would pollute ngram frequency counts if left in.
 */
public class ContentUtils {

  // ========================================================================
  // I/O utilities
  // ========================================================================

  /**
   * Opens a decompression stream based on file extension (.bz2 or .gz). Returns raw buffered stream
   * for other extensions.
   */
  public static InputStream openCompressed(String filename) throws IOException {
    InputStream fileIn = new BufferedInputStream(new FileInputStream(filename), 1024 * 1024);
    if (filename.endsWith(".bz2")) {
      return new BZip2CompressorInputStream(fileIn, true);
    } else if (filename.endsWith(".gz")) {
      return new GZIPInputStream(fileIn, 1024 * 1024);
    }
    return fileIn;
  }

  /** Formats a byte count as a human-readable string (e.g. "1.2M", "340K", "56B"). */
  public static String humanBytes(long bytes) {
    if (bytes >= 1024 * 1024) {
      return String.format("%.1fM", bytes / (1024.0 * 1024.0));
    } else if (bytes >= 1024) {
      return String.format("%.1fK", bytes / 1024.0);
    } else {
      return bytes + "B";
    }
  }

  /**
   * Streams {@code <text>} elements from a MediaWiki XML dump, passing each article's text content
   * to the consumer along with the given tagged language.
   */
  public static void extract(
      InputStream input, Language taggedLanguage, BiConsumer<CharSequence, Language> consumer)
      throws XMLStreamException {
    XMLInputFactory factory = XMLInputFactory.newInstance();
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    // Wikipedia dumps can exceed default XML entity size limits
    factory.setProperty(
        "http://www.oracle.com/xml/jaxp/properties/totalEntitySizeLimit", Integer.MAX_VALUE);
    factory.setProperty(
        "http://www.oracle.com/xml/jaxp/properties/maxGeneralEntitySizeLimit", Integer.MAX_VALUE);

    XMLStreamReader reader = factory.createXMLStreamReader(input, "UTF-8");

    StringBuilder textContent = new StringBuilder(64 * 1024);
    boolean inText = false;

    while (reader.hasNext()) {
      int event = reader.next();

      switch (event) {
        case XMLStreamConstants.START_ELEMENT:
          if ("text".equals(reader.getLocalName())) {
            inText = true;
            textContent.setLength(0);
          }
          break;

        case XMLStreamConstants.CHARACTERS:
        case XMLStreamConstants.CDATA:
          if (inText) {
            textContent.append(reader.getText());
          }
          break;

        case XMLStreamConstants.END_ELEMENT:
          if ("text".equals(reader.getLocalName())) {
            inText = false;
            if (textContent.length() > 0) {
              consumer.accept(textContent, taggedLanguage);
            }
          }
          break;
      }
    }
    reader.close();
  }

  /**
   * Splits prose into lowercase words filtered to the given alphabet. Digits poison a word
   * (excluding it), and intra-word apostrophes are preserved. Each word is passed to the callback.
   */
  public static void segmentWords(
      CharSequence input, Alphabet alpha, Consumer<CharSequence> callback) {
    StringBuilder sb = new StringBuilder();
    final int length = input.length();
    boolean poisoned = false;
    boolean hasApostrophe = false;

    for (int i = 0; i < length; i++) {
      char c = input.charAt(i);
      int type = Character.getType(c);

      if (Character.isAlphabetic(c) && Alphabet.getAlphabet(c) == alpha) {
        sb.append(Character.toLowerCase(c));
      } else if (Character.isDigit(c)) { // filter out things like hex sequences
        poisoned = true;
      } else if (isApostrophe(c)
          && !sb.isEmpty()
          && !endsInApostrophe(sb)) { // intra-word apostrophes are ok.
        sb.append('\'');
        hasApostrophe = true;
      } else {
        if (postChecks(sb, poisoned, hasApostrophe)) {
          callback.accept(sb);
        }
        sb.setLength(0);
        poisoned = false;
        hasApostrophe = false;
      }
    }

    if (postChecks(sb, poisoned, hasApostrophe)) {
      callback.accept(sb);
    }
  }

  /**
   * Wraps a consumer to strip wiki markup before passing text through. Removes: [[...]], {{...}},
   * &lt;...&gt; (single-line only), and URLs (http:// or https:// until whitespace or ]).
   */
  public static BiConsumer<CharSequence, Language> wrapRemoveWikiMarkup(
      BiConsumer<CharSequence, Language> inner) {
    return (text, lang) -> inner.accept(removeWikiMarkupCruft(text), lang);
  }

  /**
   * Wraps a consumer to remove accents and expand ligatures before passing text through. Maps
   * accented characters to their ASCII equivalents (e.g. é→e, ß→ss, æ→ae).
   */
  public static BiConsumer<CharSequence, Language> wrapRemoveAccents(
      BiConsumer<CharSequence, Language> inner) {
    AccentRemover remover = new AccentRemover();
    return (text, lang) -> inner.accept(remover.remove(text), lang);
  }

  static boolean startsWith(CharSequence text, int offset, String prefix) {
    if (offset + prefix.length() > text.length()) return false;
    for (int i = 0; i < prefix.length(); i++) {
      if (text.charAt(offset + i) != prefix.charAt(i)) return false;
    }
    return true;
  }

  // ========================================================================
  // XML extraction
  // ========================================================================

  private static boolean isApostrophe(char ch) {
    // \u2019 is the curvy apostrophe. \u0092 is a likely version of that if we read Windows-1252 CP
    // as UTF-8.
    return ch == '\'' || ch == '\u2019' || ch == '\u0092';
  }

  private static boolean endsInApostrophe(CharSequence cs) {
    int len = cs.length();
    return len > 0 && isApostrophe(cs.charAt(len - 1));
  }

  private static int countApostrophes(CharSequence cs) {
    int len = cs.length();
    int tot = 0;
    for (int i = 0; i < len; ++i) {
      if (isApostrophe(cs.charAt(i))) {
        tot++;
      }
    }
    return tot;
  }

  /**
   * Returns the index of the first apostrophe that is neither the first nor last character, or
   * {@code -1} if none exists. Used to split words like "l'homme" at the apostrophe.
   */
  public static int midWordApostrophePosition(CharSequence cs) {
    int lenMinusOne = cs.length() - 1;
    for (int i = 1; i < lenMinusOne; ++i) {
      if (isApostrophe(cs.charAt(i))) {
        return i;
      }
    }
    return -1;
  }

  private static boolean postChecks(StringBuilder sb, boolean poisoned, boolean hasApostrophe) {
    if (sb.isEmpty() || poisoned) {
      return false;
    }
    if (hasApostrophe) {
      if (endsInApostrophe(sb)) {
        sb.setLength(sb.length() - 1);
      }
      if (countApostrophes(sb) > 1) {
        return false;
      }
    }
    return true;
  }

  /**
   * Strips wiki markup so only natural-language prose remains. Removes {@code [[...]]}, {@code
   * {{...}}}, {@code {|...|}} tables, {@code |}-prefixed table rows, {@code <...>} tags
   * (single-line only), {@code &entity;} references, and URLs.
   */
  static CharSequence removeWikiMarkupCruft(CharSequence text) {
    int len = text.length();
    StringBuilder sb = new StringBuilder(len);
    int i = 0;
    boolean bol = true; // beginning of line
    while (i < len) {
      char c = text.charAt(i);
      // Lines starting with | (wiki table rows/cells)
      if (bol && c == '|') {
        while (i < len && text.charAt(i) != '\n') i++;
        continue;
      }
      bol = (c == '\n');
      // [[ ... ]]
      if (c == '[' && i + 1 < len && text.charAt(i + 1) == '[') {
        i = skipUntil(text, i + 2, ']', ']');
        continue;
      }
      // {{ ... }}
      if (c == '{' && i + 1 < len && text.charAt(i + 1) == '{') {
        i = skipUntil(text, i + 2, '}', '}');
        continue;
      }
      // {| ... |} (wiki tables)
      if (c == '{' && i + 1 < len && text.charAt(i + 1) == '|') {
        i = skipTable(text, i + 2);
        continue;
      }
      // < ... > (stop at newline)
      if (c == '<') {
        int j = i + 1;
        while (j < len) {
          char cj = text.charAt(j);
          if (cj == '>') {
            j++;
            break;
          }
          if (cj == '\n') break;
          j++;
        }
        i = j;
        continue;
      }
      // HTML entities: &lt; &nbsp; etc.
      if (c == '&' && i + 2 < len) {
        int j = i + 1;
        char cj = text.charAt(j);
        if ((cj >= 'a' && cj <= 'z') || (cj >= 'A' && cj <= 'Z')) {
          j++;
          while (j < len) {
            cj = text.charAt(j);
            if (cj == ';') {
              j++;
              i = j;
              break;
            }
            if (!((cj >= 'a' && cj <= 'z') || (cj >= 'A' && cj <= 'Z'))) break;
            j++;
          }
          if (i == j) continue; // matched &...;, already advanced
        }
        // not an entity — fall through to append
      }
      // URLs: http:// or https://
      if (c == 'h' && i + 7 < len && startsWith(text, i, "http://")
          || c == 'h' && i + 8 < len && startsWith(text, i, "https://")) {
        int j = i;
        while (j < len) {
          char cj = text.charAt(j);
          if (cj <= ' ' || cj == ']') break;
          j++;
        }
        i = j;
        continue;
      }
      sb.append(c);
      i++;
    }
    return sb;
  }

  // Advance past a closing two-char delimiter (e.g. ]] or }}), handling nesting.
  private static int skipUntil(CharSequence text, int start, char close1, char close2) {
    int len = text.length();
    int depth = 1;
    char open1 = (close1 == ']') ? '[' : '{';
    int i = start;
    while (i < len) {
      char c = text.charAt(i);
      if (c == open1 && i + 1 < len && text.charAt(i + 1) == open1) {
        depth++;
        i += 2;
      } else if (c == close1 && i + 1 < len && text.charAt(i + 1) == close2) {
        depth--;
        i += 2;
        if (depth == 0) return i;
      } else {
        i++;
      }
    }
    return len; // unclosed — skip to end
  }

  /** Advance past a closing |}, handling nested {| |} pairs. */
  private static int skipTable(CharSequence text, int start) {
    int len = text.length();
    int depth = 1;
    int i = start;
    while (i < len) {
      char c = text.charAt(i);
      if (c == '{' && i + 1 < len && text.charAt(i + 1) == '|') {
        depth++;
        i += 2;
      } else if (c == '|' && i + 1 < len && text.charAt(i + 1) == '}') {
        depth--;
        i += 2;
        if (depth == 0) return i;
      } else {
        i++;
      }
    }
    return len;
  }
}
