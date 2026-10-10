package com.minidrive.entity;

/**
 * Lifecycle state of a chunked upload session.
 *
 * <p>{@link #INITIATED} is the only writable state: chunks may be ingested
 * while a session is initiated. {@link #ABORTED} means the owner cancelled the
 * upload and the server removed its staging objects. {@link #COMPLETED} and
 * {@link #EXPIRED} are reserved for the finalization phase (8.4) and for
 * expiry cleanup, so this phase never creates them.</p>
 */
public enum UploadSessionStatus {
    INITIATED,
    COMPLETED,
    ABORTED,
    EXPIRED
}
