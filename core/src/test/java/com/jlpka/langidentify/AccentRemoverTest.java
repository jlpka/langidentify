package com.jlpka.langidentify;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AccentRemoverTest {

  private final AccentRemover remover = new AccentRemover();

  @Test
  void asciiPassesThrough() {
    assertEquals("hello world", remover.remove("hello world").toString());
  }

  @Test
  void emptyString() {
    assertEquals("", remover.remove("").toString());
  }

  @Test
  void basicAccents() {
    assertEquals("cafe", remover.remove("café").toString());
    assertEquals("naive", remover.remove("naïve").toString());
    assertEquals("uber", remover.remove("über").toString());
  }

  @Test
  void ligatures() {
    assertEquals("ae", remover.remove("æ").toString());
    assertEquals("oe", remover.remove("œ").toString());
    assertEquals("ss", remover.remove("ß").toString());
  }

  @Test
  void typographicLigatures() {
    assertEquals("ff", remover.remove("ﬀ").toString());
    assertEquals("fi", remover.remove("ﬁ").toString());
    assertEquals("fl", remover.remove("ﬂ").toString());
    assertEquals("ffi", remover.remove("ﬃ").toString());
  }

  @Test
  void mixedContent() {
    assertEquals("Francais", remover.remove("Français").toString());
    assertEquals("Strasse", remover.remove("Straße").toString());
    assertEquals("resume", remover.remove("résumé").toString());
  }

  @Test
  void unmappedNonAsciiPassesThrough() {
    // CJK characters should pass through unchanged
    String cjk = "漢字";
    assertEquals(cjk, remover.remove(cjk).toString());
  }

  @Test
  void returnsInputForAllAscii() {
    // Fast path: all-ASCII input should return the original CharSequence
    String ascii = "plain ascii text";
    assertSame(ascii, remover.remove(ascii));
  }
}
