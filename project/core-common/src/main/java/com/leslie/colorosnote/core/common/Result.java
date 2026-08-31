package com.leslie.colorosnote.core.common;

/**
 * Sealed-style success/failure result type (Java: final class + static factories).
 * Immutable; either a value or an error, never both.
 */
public final class Result<T> {

    private final T value;
    private final Throwable error;

    private Result(T value, Throwable error) {
        this.value = value;
        this.error = error;
    }

    public static <T> Result<T> success(T value) {
        return new Result<>(value, null);
    }

    public static <T> Result<T> failure(Throwable error) {
        if (error == null) {
            throw new IllegalArgumentException("error must not be null");
        }
        return new Result<>(null, error);
    }

    public boolean isSuccess() {
        return error == null;
    }

    public boolean isFailure() {
        return error != null;
    }

    /** Returns the value, or null when this is a failure. */
    public T getOrNull() {
        return value;
    }

    /** Returns the value, or the given fallback when this is a failure. */
    public T getOrElse(T fallback) {
        return error == null ? value : fallback;
    }

    /** Returns the failure cause, or null when this is a success. */
    public Throwable errorOrNull() {
        return error;
    }

    /** Returns the value or throws the failure cause wrapped in a RuntimeException if needed. */
    public T getOrThrow() {
        if (error == null) {
            return value;
        }
        if (error instanceof RuntimeException) {
            throw (RuntimeException) error;
        }
        throw new RuntimeException(error);
    }

    /** Maps the success value; failures pass through unchanged. */
    public <R> Result<R> map(Mapper<T, R> mapper) {
        if (error != null) {
            return new Result<>(null, error);
        }
        return success(mapper.map(value));
    }

    public interface Mapper<T, R> {
        R map(T value);
    }
}
