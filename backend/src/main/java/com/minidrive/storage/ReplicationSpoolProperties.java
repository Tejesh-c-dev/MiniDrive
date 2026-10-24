package com.minidrive.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "minidrive.storage.replication.spool")
public class ReplicationSpoolProperties {
    private Path directory = Path.of("./data/replication-spool");
    private long maxFileBytes = 5L * 1024 * 1024 * 1024;
    private long maxReservedBytes = 5L * 1024 * 1024 * 1024;
    private int maxConcurrent = 1;
    private long minFreeBytes = 1024L * 1024 * 1024;
    private long capacityCheckIntervalBytes = 16L * 1024 * 1024;

    public Path getDirectory() { return directory; }
    public void setDirectory(Path directory) { this.directory = directory; }
    public long getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }
    public long getMaxReservedBytes() { return maxReservedBytes; }
    public void setMaxReservedBytes(long maxReservedBytes) { this.maxReservedBytes = maxReservedBytes; }
    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
    public long getMinFreeBytes() { return minFreeBytes; }
    public void setMinFreeBytes(long minFreeBytes) { this.minFreeBytes = minFreeBytes; }
    public long getCapacityCheckIntervalBytes() { return capacityCheckIntervalBytes; }
    public void setCapacityCheckIntervalBytes(long capacityCheckIntervalBytes) { this.capacityCheckIntervalBytes = capacityCheckIntervalBytes; }

    public void validate() {
        if (directory == null || maxFileBytes <= 0 || maxReservedBytes <= 0 || maxConcurrent <= 0
                || minFreeBytes < 0 || capacityCheckIntervalBytes <= 0) {
            throw new IllegalStateException("Invalid replication spool policy: directory, positive size limits, concurrency, and check interval are required; free-space reserve cannot be negative");
        }
    }
}
