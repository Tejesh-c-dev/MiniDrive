package com.minidrive.storage;

import com.minidrive.exception.StorageException;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Service
public class SiloStorageService implements StorageService {
    private final MinioClient client;
    private final String bucket;

    public SiloStorageService(
            MinioClient client,
            @Value("${minio.bucket:minidrive-files}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public void upload(String objectKey, InputStream input, long size, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket).object(objectKey).stream(input, size, -1L)
                    .contentType(contentType).build());
        } catch (Exception e) {
            throw new StorageException("Unable to store uploaded file", e);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw new StorageException("Unable to remove uploaded object", e);
        }
    }
}
