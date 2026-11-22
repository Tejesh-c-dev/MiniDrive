package com.minidrive.storage;

import java.io.InputStream;
import java.util.Optional;

public interface StorageService {
    void upload(String objectKey, InputStream input, long size, String contentType);
    InputStream download(String objectKey);
    void delete(String objectKey);

    /** Bounded metadata-only check; empty means the adapter cannot inspect safely. */
    default Optional<Boolean> exists(String objectKey) { return Optional.empty(); }
}
