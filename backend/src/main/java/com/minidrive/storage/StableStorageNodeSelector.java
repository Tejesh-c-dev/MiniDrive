package com.minidrive.storage;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.TreeSet;
@Component
public class StableStorageNodeSelector implements StorageNodeSelector {
    public String select(String key, Collection<String> ids) {
        var sorted = new TreeSet<>(ids);
        if(sorted.isEmpty()) throw new IllegalStateException("No eligible storage nodes");
        try { byte[] hash=MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return sorted.stream().skip(Math.floorMod(java.nio.ByteBuffer.wrap(hash).getLong(), sorted.size())).findFirst().orElseThrow();
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
}
