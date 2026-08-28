package com.plexon.shops.storage;

/** Unchecked wrapper used to complete asynchronous repository calls exceptionally. */
public final class StorageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
