package com.truecost.domain;

/**
 * A whole count of United States cents. Every monetary amount in TrueCost, toll rates,
 * program fees, rental quotes, and computed trip totals, is represented this way so that
 * pricing arithmetic is exact integer arithmetic and never floating point.
 */
public record Money(long cents) implements Comparable<Money> {

    public static final Money ZERO = new Money(0L);

    public static Money ofCents(long cents) {
        return new Money(cents);
    }

    public Money plus(Money other) {
        return new Money(cents + other.cents);
    }

    public Money times(int factor) {
        return new Money(cents * factor);
    }

    public Money min(Money other) {
        return cents <= other.cents ? this : other;
    }

    public boolean isZero() {
        return cents == 0L;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(cents, other.cents);
    }

    @Override
    public String toString() {
        return "$%d.%02d".formatted(cents / 100, Math.abs(cents % 100));
    }
}
