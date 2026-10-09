package com.minidrive.entity;

/**
 * Effective role a user holds for a single file.
 *
 * <p>{@link #OWNER} is never persisted as a permission row: the file's
 * {@code owner_id} column is authoritative for ownership. Only {@link #EDITOR}
 * and {@link #VIEWER} can be granted to collaborators.</p>
 */
public enum FileRole {
    OWNER,
    EDITOR,
    VIEWER
}
