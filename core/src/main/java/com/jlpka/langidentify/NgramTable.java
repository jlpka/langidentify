package com.jlpka.langidentify;

import java.util.HashMap;
import java.util.Map;

/**
 * Immutable open-addressing hash table mapping ngrams to per-language probability data.
 *
 * <p>Built via {@link Builder}: accumulate entries with {@link Builder#put} and {@link
 * Builder#get}, then call {@link Builder#compact()} to produce an immutable {@code NgramTable}.
 *
 * <p>Callers can reuse a mutable {@link Ngram} instance, updating its fields before each {@link
 * #lookup} call to avoid allocation on the lookup path.
 */
public final class NgramTable {

  // NgramEntry entries are both key and value together to avoid per-Object overhead.
  private final NgramEntry[] entries;
  private final int[] hashes;
  private final int mask;
  private final int entryCount;
  // We keep the probability lists all in a single flat float[] array and just store
  // int offsets to the probabilities here.
  private final float[] probData;

  public static final NgramTable EMPTY =
      new NgramTable(new NgramEntry[1], new int[1], 0, 0, new float[0]);

  NgramTable(NgramEntry[] entries, int[] hashes, int mask, int entryCount, float[] probData) {
    this.entries = entries;
    this.hashes = hashes;
    this.mask = mask;
    this.entryCount = entryCount;
    this.probData = probData;
  }

  /**
   * Looks up the given ngram key. Returns the matching {@link NgramEntry}, or null if not found.
   */
  public NgramEntry lookup(Ngram key) {
    final int hash = key.hashCode();
    int h = hash & mask;
    while (true) {
      NgramEntry e = entries[h];
      if (e == null) {
        return null;
      }
      if (hashes[h] == hash && e.equalsNgram(key)) {
        return e;
      }
      h = (h + 1) & mask;
    }
  }

  /** Returns the flat array of all probability values. */
  public float[] probData() {
    return probData;
  }

  /** Returns the number of ngram entries in the table. */
  public int size() {
    return entryCount;
  }

  // ==========================================================================
  // Builder
  // ==========================================================================

  /**
   * Mutable builder for constructing an {@link NgramTable}. Uses an open-addressing hash table
   * internally for fast get/put during loading. Call {@link #compact()} to produce an immutable
   * {@code NgramTable} with a flat probability array.
   */
  public static class Builder {
    private static final int INITIAL_CAPACITY = 1024;

    private Ngram[] keys;
    private LangProbListBuilder[] values;
    private int[] hashes;
    private int mask;
    private int size;

    public Builder() {
      keys = new Ngram[INITIAL_CAPACITY];
      values = new LangProbListBuilder[INITIAL_CAPACITY];
      hashes = new int[INITIAL_CAPACITY];
      mask = INITIAL_CAPACITY - 1;
    }

    /** Stores a builder in the table during loading. */
    public void put(Ngram key, LangProbListBuilder value) {
      if (size * 4 >= keys.length * 3) { // > 75% load
        resize();
      }
      int hash = key.hashCode();
      int h = hash & mask;
      while (keys[h] != null) {
        if (hashes[h] == hash && keys[h].equalsNgram(key)) {
          values[h] = value;
          return;
        }
        h = (h + 1) & mask;
      }
      keys[h] = key;
      values[h] = value;
      hashes[h] = hash;
      size++;
    }

    /** Looks up the given ngram key and returns its LangProbListBuilder, or null. */
    public LangProbListBuilder get(Ngram key) {
      int hash = key.hashCode();
      int h = hash & mask;
      while (keys[h] != null) {
        if (hashes[h] == hash && keys[h].equalsNgram(key)) {
          return values[h];
        }
        h = (h + 1) & mask;
      }
      return null;
    }

    private void resize() {
      int newCapacity = keys.length * 2;
      Ngram[] newKeys = new Ngram[newCapacity];
      LangProbListBuilder[] newValues = new LangProbListBuilder[newCapacity];
      int[] newHashes = new int[newCapacity];
      int newMask = newCapacity - 1;
      for (int i = 0; i < keys.length; i++) {
        if (keys[i] != null) {
          int h = hashes[i] & newMask;
          while (newKeys[h] != null) {
            h = (h + 1) & newMask;
          }
          newKeys[h] = keys[i];
          newValues[h] = values[i];
          newHashes[h] = hashes[i];
        }
      }
      keys = newKeys;
      values = newValues;
      hashes = newHashes;
      mask = newMask;
    }

    /**
     * Compacts all builders into an immutable {@link NgramTable} with an open-addressing hash table
     * and a flat probability array. Interns {@code langIndices} arrays so identical patterns share
     * one instance. No probability clamping is applied.
     */
    public NgramTable compact() {
      return compact(0.0f);
    }

    /**
     * Compacts all builders into an immutable {@link NgramTable}, clamping any probability value
     * below {@code probFloor} up to that floor. A floor of 0.0f disables clamping (all log-probs
     * are negative).
     */
    public NgramTable compact(float probFloor) {
      IntArrayInterner interner = new IntArrayInterner();

      // First pass: compute total prob count (skip poisoned entries).
      int totalProbs = 0;
      for (int i = 0; i < keys.length; i++) {
        if (keys[i] != null && !values[i].isPoisoned()) {
          totalProbs += values[i].size();
        }
      }
      float[] probData = new float[totalProbs];

      // Size the open-addressing table: next power of 2 >= count * 4/3.
      int count = size;
      int capacity = Integer.highestOneBit(Math.max(count * 4 / 3, 1) - 1) << 1;
      if (capacity < 4) {
        capacity = 4;
      }
      NgramEntry[] entries = new NgramEntry[capacity];
      int outMask = capacity - 1;

      // Second pass: copy probs and insert into open-addressing table.
      int offset = 0;
      for (int i = 0; i < keys.length; i++) {
        if (keys[i] == null) {
          continue;
        }
        Ngram key = keys[i];
        LangProbListBuilder builder = values[i];
        NgramEntry ngEntry;
        if (builder.isPoisoned()) {
          // Skipword: no lang data, null langIndices signals "skip this word"
          ngEntry = NgramEntry.build(key.chars, key.offset, key.length, null, 0);
        } else {
          int sz = builder.size();
          System.arraycopy(builder.probs(), 0, probData, offset, sz);
          // Clamp probabilities below the floor up to the floor.
          if (probFloor != 0.0f) {
            for (int j = offset; j < offset + sz; j++) {
              if (probData[j] < probFloor) {
                probData[j] = probFloor;
              }
            }
          }
          int[] interned = interner.intern(builder.langIndices(), builder.size());
          ngEntry = NgramEntry.build(key.chars, key.offset, key.length, interned, offset);
          offset += sz;
        }
        // Linear-probe insert.
        int h = ngEntry.hashCode() & outMask;
        while (entries[h] != null) {
          h = (h + 1) & outMask;
        }
        entries[h] = ngEntry;
      }
      int[] outHashes = new int[capacity];
      for (int i = 0; i < entries.length; ++i) {
        if (entries[i] != null) {
          outHashes[i] = entries[i].hashCode();
        }
      }

      return new NgramTable(entries, outHashes, outMask, count, probData);
    }
  }

  // ==========================================================================
  // Ngram
  // ==========================================================================

  /** An ngram represented as a view into a char array. */
  public static class Ngram implements Comparable<Ngram> {
    /** The backing character array. */
    public char[] chars;

    /** The start offset within {@link #chars}. */
    public int offset;

    /** The number of characters in this ngram. */
    public int length;

    public Ngram(char[] chars, int offset, int length) {
      this.chars = chars;
      this.offset = offset;
      this.length = length;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Ngram)) {
        return false;
      }
      return equalsNgram((Ngram) o);
    }

    /** Returns {@code true} if this ngram has the same characters as {@code other}. */
    public boolean equalsNgram(Ngram other) {
      if (length != other.length) {
        return false;
      }
      for (int i = 0; i < length; i++) {
        if (chars[offset + i] != other.chars[other.offset + i]) {
          return false;
        }
      }
      return true;
    }

    @Override
    public int hashCode() {
      int h = 0;
      for (int i = 0; i < length; i++) {
        // Java usually uses 31, but we see better performance using the first
        // prime over 32 ('a-z' fits in 5 bits)
        h = 37 * h + chars[offset + i];
      }
      // This mixing code improves bit diversity, can speed table lookups by ~4x.
      //
      // The issue - some lower bits are somewhat clumpy/correlated (consider 1<<4 -
      // we have 26 a-z letters, but in a 5 bit space - there will be fewer with 1<<4 set).
      // With a less random hash, we will end up traversing more entries on lookups
      // than normal.
      //
      // So we use the first part of MurmurHash3 fmix32 finalizer - which uses the
      // golden ratio. The fmix32 initializer and latter part of fmix32 doesn't seem
      // to be needed or helpful to improve lookup speed.
      h ^= (h >>> 16);
      h *= 0x85ebca6b;
      h ^= (h >>> 13);
      return h;
    }

    /** Returns a copy of this ngram with its own backing array. */
    public Ngram copy() {
      char[] nc = new char[length];
      for (int i = 0; i < length; ++i) {
        nc[i] = chars[i + offset];
      }
      return new Ngram(nc, 0, length);
    }

    @Override
    public int compareTo(Ngram other) {
      int cmp = Integer.compare(length, other.length);
      if (cmp != 0) return cmp;
      for (int i = 0; i < length; i++) {
        cmp = Character.compare(chars[offset + i], other.chars[other.offset + i]);
        if (cmp != 0) return cmp;
      }
      return 0;
    }

    @Override
    public String toString() {
      return new String(chars, offset, length);
    }
  }

  // ==========================================================================
  // NgramEntry
  // ==========================================================================

  /**
   * An ngram with its associated language data, used as a slot in the open-addressing table.
   * Extends {@link Ngram} so it can be compared directly against lookup keys. Probabilities are
   * stored in the table's flat {@code probData} array at {@code probData[probOffset .. probOffset +
   * langIndices.length)}.
   */
  public static final class NgramEntry extends Ngram {
    // offset in probData to the probabilities
    public final int probOffset;
    // langIndices are the list of languageIndex for the ngram.
    // We use IntArrayInterner to try to store each unique array once here, and share.
    public final int[] langIndices;

    NgramEntry(char[] chars, int offset, int length, int[] langIndices, int probOffset) {
      super(chars, offset, length);
      this.langIndices = langIndices;
      this.probOffset = probOffset;
    }

    /** Creates a new entry with the given character data, language indices, and probability offset. */
    public static NgramEntry build(char[] chars, int ofs, int len, int[] li, int po) {
      return new NgramEntry(chars, ofs, len, li, po);
    }
  }

  // ==========================================================================
  // LangProbListBuilder
  // ==========================================================================

  /**
   * Mutable builder for accumulating (langIndex, probability) entries during loading. Stored in the
   * {@link Builder}'s table during loading, then consumed by {@link Builder#compact()}.
   */
  public static class LangProbListBuilder {
    private int[] langIndices;
    private float[] probs;
    private int size;

    public LangProbListBuilder() {
      langIndices = new int[4];
      probs = new float[4];
    }

    /** Marks this builder as poisoned (skipword). Subsequent add() calls are ignored. */
    public void poison() {
      size = -1;
    }

    /** Returns true if this builder is poisoned (skipword entry). */
    public boolean isPoisoned() {
      return size < 0;
    }

    /** Adds a (langIndex, probability) entry. No-op if poisoned. */
    public LangProbListBuilder add(int langIdx, float prob) {
      if (size < 0) {
        return this; // poisoned — ignore
      }
      if (size == langIndices.length) {
        int newCap = langIndices.length * 2;
        int[] newLangs = new int[newCap];
        float[] newProbs = new float[newCap];
        System.arraycopy(langIndices, 0, newLangs, 0, size);
        System.arraycopy(probs, 0, newProbs, 0, size);
        langIndices = newLangs;
        probs = newProbs;
      }
      langIndices[size] = langIdx;
      probs[size] = prob;
      size++;
      return this;
    }

    /** Returns the number of accumulated entries. */
    public int size() {
      return size;
    }

    /** Returns the backing probs array (may be oversized; only first {@link #size()} valid). */
    float[] probs() {
      return probs;
    }

    /**
     * Returns the backing langIndices array (may be oversized; only first {@link #size()} valid).
     */
    int[] langIndices() {
      return langIndices;
    }
  }

  // ==========================================================================
  // IntArrayInterner
  // ==========================================================================

  /**
   * Interns {@code int[]} arrays by content, so structurally equal arrays share one instance. Used
   * during {@link Builder#compact()} to deduplicate {@code langIndices} arrays.
   */
  static class IntArrayInterner {
    private final Map<IntArrayKey, int[]> cache = new HashMap<>();
    private final IntArrayKey lookupKey = new IntArrayKey(null, 0);

    /** Returns a canonical instance of the given array (content-equal). */
    int[] intern(int[] arr, int size) {
      // lookup whether existing key is present (no allocs), and return if so.
      lookupKey.replace(arr, size);
      int[] existing = cache.get(lookupKey);
      if (existing != null) {
        return existing;
      }
      // recopy
      int[] res = new int[size];
      System.arraycopy(arr, 0, res, 0, size);
      // insert with fresh key
      cache.put(new IntArrayKey(res, size), res);
      return res;
    }

    /** Wrapper providing content-based equals/hashCode for int[]. */
    private static final class IntArrayKey {
      private int[] arr;
      private int size;
      private int cachedHash;

      IntArrayKey(int[] arr, int size) {
        this.arr = arr;
        this.size = size;
        this.cachedHash = 0;
      }

      void replace(int[] arr, int size) {
        this.arr = arr;
        this.size = size;
        this.cachedHash = 0;
      }

      @Override
      public boolean equals(Object o) {
        if (!(o instanceof IntArrayKey)) {
          return false;
        }
        IntArrayKey other = (IntArrayKey) o;
        if (size != other.size) {
          return false;
        }
        for (int i = 0; i < size; ++i) {
          if (other.arr[i] != arr[i]) return false;
        }
        return true;
      }

      @Override
      public int hashCode() {
        if (cachedHash == 0) {
          int h = 0;
          for (int i = 0; i < size; ++i) {
            h = h * 31 + arr[i];
          }
          cachedHash = h;
        }
        return cachedHash;
      }
    }
  }
}
