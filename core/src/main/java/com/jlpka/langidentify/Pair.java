package com.jlpka.langidentify;

import java.util.Objects;

/** A generic pair of two values. */
public class Pair<A, B> {
  private final A first;
  private final B second;

  public Pair(A first, B second) {
    this.first = first;
    this.second = second;
  }

  /** Creates a new pair of the given values. */
  public static <A, B> Pair<A, B> of(A first, B second) {
    return new Pair<>(first, second);
  }

  /** Returns the first element of the pair. */
  public A first() {
    return first;
  }

  /** Returns the second element of the pair. */
  public B second() {
    return second;
  }

  @Override
  public int hashCode() {
    return Objects.hash(first, second);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (!(obj instanceof Pair)) return false;
    Pair<?, ?> other = (Pair<?, ?>) obj;
    return Objects.equals(first, other.first) && Objects.equals(second, other.second);
  }
}
