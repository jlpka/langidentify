package com.jlpka.langidentify.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.jlpka.langidentify.Alphabet;
import com.jlpka.langidentify.Language;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import javax.xml.stream.XMLStreamException;
import org.junit.jupiter.api.Test;

class ContentUtilsTest {

  // ========================================================================
  // humanBytes
  // ========================================================================

  @Test
  void humanBytesSmall() {
    assertEquals("0B", ContentUtils.humanBytes(0));
    assertEquals("512B", ContentUtils.humanBytes(512));
    assertEquals("1023B", ContentUtils.humanBytes(1023));
  }

  @Test
  void humanBytesKilo() {
    assertEquals("1.0K", ContentUtils.humanBytes(1024));
    assertEquals("1.5K", ContentUtils.humanBytes(1536));
  }

  @Test
  void humanBytesMega() {
    assertEquals("1.0M", ContentUtils.humanBytes(1024 * 1024));
    assertEquals("2.5M", ContentUtils.humanBytes((long) (2.5 * 1024 * 1024)));
  }

  // ========================================================================
  // removeWikiMarkupCruft
  // ========================================================================

  @Test
  void removeMarkupPlainText() {
    assertEquals("hello world", ContentUtils.removeWikiMarkupCruft("hello world").toString());
  }

  @Test
  void removeMarkupDoubleSquareBrackets() {
    assertEquals(
        "see  for details",
        ContentUtils.removeWikiMarkupCruft("see [[link target]] for details").toString());
  }

  @Test
  void removeMarkupDoubleCurlyBraces() {
    assertEquals(
        "before  after",
        ContentUtils.removeWikiMarkupCruft("before {{template|arg}} after").toString());
  }

  @Test
  void removeMarkupNestedBrackets() {
    assertEquals(
        "a  b", ContentUtils.removeWikiMarkupCruft("a [[outer [[inner]] text]] b").toString());
  }

  @Test
  void removeMarkupAngleBrackets() {
    assertEquals(
        "before  after",
        ContentUtils.removeWikiMarkupCruft("before <ref name=\"x\"> after").toString());
  }

  @Test
  void removeMarkupAngleBracketsStopAtNewline() {
    // Angle bracket removal stops at newline (doesn't swallow multi-line)
    String input = "before <unclosed\nnext line";
    String result = ContentUtils.removeWikiMarkupCruft(input).toString();
    assertTrue(result.contains("next line"));
  }

  @Test
  void removeMarkupUrls() {
    assertEquals(
        "visit  for info",
        ContentUtils.removeWikiMarkupCruft("visit https://example.com/page for info").toString());
    assertEquals(
        "see  ok", ContentUtils.removeWikiMarkupCruft("see http://foo.bar/baz ok").toString());
  }

  @Test
  void removeMarkupHtmlEntities() {
    assertEquals("a  b  c", ContentUtils.removeWikiMarkupCruft("a &lt; b &amp; c").toString());
  }

  @Test
  void removeMarkupWikiTable() {
    assertEquals(
        "before  after",
        ContentUtils.removeWikiMarkupCruft("before {| class=\"wikitable\"\n|cell\n|} after")
            .toString());
  }

  @Test
  void removeMarkupPipeLines() {
    // Lines starting with | are table rows — content is stripped but newlines remain
    String input = "header\n| row1\n| row2\nfooter";
    String result = ContentUtils.removeWikiMarkupCruft(input).toString();
    assertEquals("header\n\n\nfooter", result);
  }

  @Test
  void removeMarkupEmpty() {
    assertEquals("", ContentUtils.removeWikiMarkupCruft("").toString());
  }

  // ========================================================================
  // segmentWords
  // ========================================================================

  private List<String> segmentLatin(String input) {
    List<String> words = new ArrayList<>();
    ContentUtils.segmentWords(input, Alphabet.LATIN, cs -> words.add(cs.toString()));
    return words;
  }

  @Test
  void segmentSimpleWords() {
    assertEquals(List.of("hello", "world"), segmentLatin("Hello World"));
  }

  @Test
  void segmentWithPunctuation() {
    assertEquals(List.of("the", "cat", "sat"), segmentLatin("The cat, sat."));
  }

  @Test
  void segmentDigitsPoisonWord() {
    // Words containing digits are filtered out
    assertEquals(List.of("hello"), segmentLatin("hello abc123 "));
  }

  @Test
  void segmentApostrophe() {
    // Intra-word apostrophes are preserved
    List<String> words = segmentLatin("it's a test don't");
    assertTrue(words.contains("it's"));
    assertTrue(words.contains("don't"));
  }

  @Test
  void segmentTrailingApostropheTrimmed() {
    // Trailing apostrophe is trimmed
    List<String> words = segmentLatin("dogs' bones");
    assertTrue(words.contains("dogs"));
    assertTrue(words.contains("bones"));
  }

  @Test
  void segmentEmpty() {
    assertEquals(List.of(), segmentLatin(""));
  }

  @Test
  void segmentOnlyPunctuation() {
    assertEquals(List.of(), segmentLatin("!@#$%^&*()"));
  }

  // ========================================================================
  // wrapRemoveWikiMarkup
  // ========================================================================

  @Test
  void wrapRemoveWikiMarkupApplies() {
    List<String> results = new ArrayList<>();
    BiConsumer<CharSequence, Language> wrapped =
        ContentUtils.wrapRemoveWikiMarkup((text, lang) -> results.add(text.toString()));
    wrapped.accept("hello [[link]] world", Language.ENGLISH);
    assertEquals(1, results.size());
    assertEquals("hello  world", results.get(0));
  }

  // ========================================================================
  // wrapRemoveAccents
  // ========================================================================

  @Test
  void wrapRemoveAccentsApplies() {
    List<String> results = new ArrayList<>();
    BiConsumer<CharSequence, Language> wrapped =
        ContentUtils.wrapRemoveAccents((text, lang) -> results.add(text.toString()));
    wrapped.accept("café résumé", Language.FRENCH);
    assertEquals(1, results.size());
    assertEquals("cafe resume", results.get(0));
  }

  // ========================================================================
  // extract (XML)
  // ========================================================================

  @Test
  void extractParsesTextElements() throws XMLStreamException {
    String xml = "<page><text>Hello World</text></page>";
    ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    List<String> texts = new ArrayList<>();
    ContentUtils.extract(in, Language.ENGLISH, (text, lang) -> texts.add(text.toString()));
    assertEquals(1, texts.size());
    assertEquals("Hello World", texts.get(0));
  }

  @Test
  void extractMultipleTextElements() throws XMLStreamException {
    String xml = "<root><page><text>First</text></page><page><text>Second</text></page></root>";
    ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    List<String> texts = new ArrayList<>();
    ContentUtils.extract(
        in,
        Language.FRENCH,
        (text, lang) -> {
          texts.add(text.toString());
          assertEquals(Language.FRENCH, lang);
        });
    assertEquals(List.of("First", "Second"), texts);
  }

  @Test
  void extractSkipsEmptyText() throws XMLStreamException {
    String xml = "<page><text></text><text>notempty</text></page>";
    ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    List<String> texts = new ArrayList<>();
    ContentUtils.extract(in, Language.ENGLISH, (text, lang) -> texts.add(text.toString()));
    assertEquals(1, texts.size());
    assertEquals("notempty", texts.get(0));
  }

  // ========================================================================
  // startsWith helper
  // ========================================================================

  @Test
  void startsWithHelper() {
    assertTrue(ContentUtils.startsWith("hello world", 6, "world"));
    assertTrue(ContentUtils.startsWith("abcdef", 0, "abc"));
    assertFalse(ContentUtils.startsWith("hello", 3, "xyz"));
    assertFalse(ContentUtils.startsWith("hi", 0, "hello")); // prefix longer than remaining
  }
}
