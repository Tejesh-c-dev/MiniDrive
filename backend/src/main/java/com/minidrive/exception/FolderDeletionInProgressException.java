package com.minidrive.exception;

public class FolderDeletionInProgressException extends RuntimeException {
    public FolderDeletionInProgressException() { super("Folder deletion is pending cleanup"); }
}
