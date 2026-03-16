package com.jlpka.langidentify.models.lite;

/**
 * Metadata marker for the LangIdentify Lite model module.
 *
 * <p>This module bundles compact ngram and topword data for 80+ languages. The lite model
 * uses a lower-resolution ngram set (minLogProb&nbsp;=&nbsp;-12) for a smaller download at
 * the cost of some accuracy on very short text.
 */
public final class LiteModelInfo {
  private LiteModelInfo() {}
}
