package com.minidrive.config;

import com.minidrive.storage.NodeStorageClient;
import com.minidrive.storage.StorageNodeProperties;
import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(StorageNodeProperties.class)
public class StorageConfig {
    @Bean
    Map<String, NodeStorageClient> storageNodeClients(StorageNodeProperties properties,
            @Value("${minio.endpoint:http://localhost:9000}") String endpoint,
            @Value("${minio.access-key:minioadmin}") String accessKey,
            @Value("${minio.secret-key:minioadmin}") String secretKey,
            @Value("${minio.bucket:minidrive-files}") String bucket) {
        Map<String, NodeStorageClient> clients = new LinkedHashMap<>();
        var configuredNodesValue = properties.getNodes();
        if (configuredNodesValue == null || configuredNodesValue.isEmpty()) {
            StorageNodeProperties.Node primary = new StorageNodeProperties.Node();
            primary.setId("primary"); primary.setEndpoint(endpoint); primary.setAccessKey(accessKey);
            primary.setSecretKey(secretKey); primary.setBucket(bucket); primary.setEnabled(true);
            configuredNodesValue = java.util.List.of(primary);
        }
        final var configuredNodes = configuredNodesValue;
        for (StorageNodeProperties.Node node : configuredNodes) {
            if (node.getId() == null || !node.getId().matches("[A-Za-z0-9_-]{1,64}"))
                throw new IllegalStateException("Invalid storage node ID");
            if (clients.containsKey(node.getId()) || configuredNodes.stream()
                    .filter(other -> other != node).anyMatch(other -> node.getId().equals(other.getId())))
                throw new IllegalStateException("Duplicate storage node ID: " + node.getId());
            if (!node.isEnabled()) continue;
            if (node.getEndpoint() == null || node.getBucket() == null || node.getBucket().isBlank()
                    || node.getAccessKey() == null || node.getAccessKey().isBlank()
                    || node.getSecretKey() == null || node.getSecretKey().isBlank())
                throw new IllegalStateException("Incomplete configuration for storage node " + node.getId());
            MinioClient client = MinioClient.builder().endpoint(node.getEndpoint())
                    .credentials(node.getAccessKey(), node.getSecretKey()).build();
            clients.put(node.getId(), new NodeStorageClient(node.getId(), client, node.getBucket()));
        }
        if (clients.isEmpty()) throw new IllegalStateException("At least one storage node must be enabled");
        return Map.copyOf(clients);
    }
}
