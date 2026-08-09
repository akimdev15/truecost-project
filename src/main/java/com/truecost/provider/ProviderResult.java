package com.truecost.provider;

import java.util.function.Function;

/**
 * The partial result contract every external data provider returns instead of throwing. No
 * provider connector lets an exception cross this boundary, a failure always yields an Absent
 * carrying a human readable reason instead.
 */
public sealed interface ProviderResult<T> permits ProviderResult.Present, ProviderResult.Absent {

    record Present<T>(T value) implements ProviderResult<T> {
    }

    record Absent<T>(String reason) implements ProviderResult<T> {
    }

    static <T> ProviderResult<T> present(T value) {
        return new Present<>(value);
    }

    static <T> ProviderResult<T> absent(String reason) {
        return new Absent<>(reason);
    }

    default boolean isPresent() {
        return this instanceof Present<T>;
    }

    default <R> ProviderResult<R> map(Function<T, R> mapper) {
        return switch (this) {
            case Present<T> present -> ProviderResult.present(mapper.apply(present.value()));
            case Absent<T> absent -> ProviderResult.absent(absent.reason());
        };
    }

    default T orElseThrow() {
        return switch (this) {
            case Present<T> present -> present.value();
            case Absent<T> absent -> throw new IllegalStateException(
                    "provider result was absent, reason=" + absent.reason());
        };
    }
}
