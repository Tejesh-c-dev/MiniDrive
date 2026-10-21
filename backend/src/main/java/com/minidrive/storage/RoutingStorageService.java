package com.minidrive.storage;

import com.minidrive.exception.StorageException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.Callable;

@Service
public class RoutingStorageService implements StorageService {
    private final JdbcTemplate jdbc;
    private final Map<String, NodeStorageClient> nodes;
    private final StorageNodeSelector selector;
    private final MeterRegistry metrics;

    @Autowired
    public RoutingStorageService(
            JdbcTemplate jdbc,
            @Qualifier("storageNodeClients") Map<String, NodeStorageClient> nodes,
            StorageNodeSelector selector,
            MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.nodes = nodes;
        this.selector = selector;
        this.metrics = metrics;
    }

    /** Convenience constructor for callers that do not provide an application registry. */
    public RoutingStorageService(JdbcTemplate jdbc, Map<String, NodeStorageClient> nodes, StorageNodeSelector selector) {
        this(jdbc, nodes, selector, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private String existing(String key) {
        return jdbc.query(
                "select storage_node_id from object_locations where object_key = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                key);
    }

    private NodeStorageClient client(String id) {
        NodeStorageClient client = nodes.get(id);
        if (client == null) {
            throw new StorageException("Storage node required for an object is not configured: " + id);
        }
        return client;
    }

    @Override
    public void upload(String key, InputStream in, long size, String type) {
        String location = existing(key);
        if (location == null) {
            String selected = selector.select(key, nodes.keySet());
            jdbc.update("insert into object_locations(object_key,storage_node_id) values (?,?) on conflict (object_key) do nothing", key, selected);
            location = existing(key);
        }
        String nodeId = location;
        NodeStorageClient node = client(nodeId);
        measure(nodeId, "upload", () -> {
            node.upload(key, in, size, type);
            metrics.counter("minidrive_storage_node_bytes_total", "operation", "upload", "node_id", nodeId)
                    .increment(size);
            return null;
        });
    }

    @Override
    public InputStream download(String key) {
        String location = existing(key);
        if (location == null) {
            throw new StorageException("No storage location registered for object " + key);
        }
        String nodeId = location;
        NodeStorageClient node = client(nodeId);
        return timedDownload(nodeId, node, key);
    }

    @Override
    public void delete(String key) {
        String location = existing(key);
        if (location == null) return;
        NodeStorageClient node = client(location);
        measure(location, "delete", () -> {
            node.delete(key);
            return null;
        });
        jdbc.update("delete from object_locations where object_key=? and storage_node_id=?", key, location);
    }

    private <T> T measure(String nodeId, String operation, Callable<T> call) {
        Timer.Sample sample = Timer.start(metrics);
        String outcome = "success";
        try {
            return call.call();
        } catch (RuntimeException failure) {
            outcome = "failure";
            throw failure;
        } catch (Exception failure) {
            outcome = "failure";
            throw new IllegalStateException(failure);
        } finally {
            sample.stop(metrics.timer(
                    "minidrive_storage_node_operation_seconds",
                    "operation", operation,
                    "outcome", outcome,
                    "node_id", nodeId));
        }
    }

    private InputStream timedDownload(String nodeId, NodeStorageClient node, String key) {
        Timer.Sample sample = Timer.start(metrics);
        InputStream stream;
        try {
            stream = node.download(key);
        } catch (RuntimeException failure) {
            sample.stop(metrics.timer("minidrive_storage_node_operation_seconds", "operation", "download", "outcome", "failure", "node_id", nodeId));
            throw failure;
        }
        return new FilterInputStream(stream) {
            private boolean recorded;

            private void finish(String result) {
                if (!recorded) {
                    recorded = true;
                    sample.stop(metrics.timer("minidrive_storage_node_operation_seconds", "operation", "download", "outcome", result, "node_id", nodeId));
                }
            }

            private void record(long bytes) {
                if (bytes > 0) metrics.counter("minidrive_storage_node_bytes_total", "operation", "download", "node_id", nodeId).increment(bytes);
            }

            @Override
            public int read() throws IOException {
                try {
                    int value = super.read();
                    if (value == -1) finish("success"); else record(1);
                    return value;
                } catch (IOException | RuntimeException failure) {
                    finish("failure");
                    throw failure;
                }
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                try {
                    int count = super.read(bytes, offset, length);
                    if (count == -1) finish("success"); else record(count);
                    return count;
                } catch (IOException | RuntimeException failure) {
                    finish("failure");
                    throw failure;
                }
            }

            @Override
            public void close() throws IOException {
                try { super.close(); }
                catch (IOException | RuntimeException failure) {
                    finish("failure");
                    throw failure;
                } finally {
                    // Reaching EOF records success; a close before EOF is an interrupted transfer.
                    finish("failure");
                }
            }
        };
    }
}
