package com.minidrive.storage;

import com.minidrive.exception.StorageException;
import io.minio.*;
import java.io.InputStream;

public class NodeStorageClient {
    private final String id;
    private final MinioClient client;
    private final String bucket;
    public NodeStorageClient(String id, MinioClient client, String bucket) { this.id=id; this.client=client; this.bucket=bucket; }
    public String id() { return id; }
    /** Confirms the configured credentials can reach the node and its bucket. */
    public boolean isHealthy() {
        try {
            return client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        } catch (Exception e) {
            return false;
        }
    }
    public void upload(String key, InputStream in, long size, String type) {
        try { client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(in,size,-1L).contentType(type).build()); }
        catch (Exception e) { throw new StorageException("Unable to store uploaded file",e); }
    }
    public InputStream download(String key) {
        try { return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build()); }
        catch (Exception e) { throw new StorageException("Unable to retrieve stored file",e); }
    }
    public void delete(String key) {
        try { client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build()); }
        catch (Exception e) { throw new StorageException("Unable to remove uploaded object",e); }
    }
}
