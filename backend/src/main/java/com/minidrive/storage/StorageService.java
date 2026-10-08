package com.minidrive.storage;

import java.io.InputStream;

public interface StorageService {
    void upload(String objectKey, InputStream input, long size, String contentType);
    InputStream download(String objectKey);
    void delete(String objectKey);
}
