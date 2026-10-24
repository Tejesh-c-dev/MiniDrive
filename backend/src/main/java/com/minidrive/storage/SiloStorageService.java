package com.minidrive.storage;

import com.minidrive.exception.StorageException;
import io.minio.MinioClient;
import io.minio.GetObjectArgs;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Optional;

public class SiloStorageService implements StorageService {
    private static final Logger log = LoggerFactory.getLogger(SiloStorageService.class);
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
            log.error("Unable to store Silo object {}", objectKey, e);
            throw new StorageException("Unable to store uploaded file", e);
        }
    }

    @Override
    public InputStream download(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            log.error("Unable to retrieve Silo object {}", objectKey, e);
            throw new StorageException("Unable to retrieve stored file", e);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            log.error("Unable to remove Silo object {}", objectKey, e);
            throw new StorageException("Unable to remove uploaded object", e);
        }
    }

    @Override
    public Optional<Boolean> exists(String objectKey) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return Optional.of(true);
        } catch (io.minio.errors.ErrorResponseException e) {
            String code = e.errorResponse() == null ? "" : e.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code) || "NotFound".equals(code))
                return Optional.of(false);
            throw new StorageException("Unable to inspect stored file", e);
        } catch (Exception e) {
            throw new StorageException("Unable to inspect stored file", e);
        }
    }
}
