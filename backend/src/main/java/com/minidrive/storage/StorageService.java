package com.minidrive.storage;

import java.io.InputStream;

public interface StorageService {
    void upload(String objectKey, InputStream input, long size, String contentType);
    void delete(String objectKey);
}
