package com.minidrive.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "minidrive.storage")
public class StorageNodeProperties {
    private List<Node> nodes = new ArrayList<>();
    public List<Node> getNodes() { return nodes; }
    public void setNodes(List<Node> nodes) { this.nodes = nodes; }
    public static class Node {
        private String id, endpoint, accessKey, secretKey, bucket;
        private boolean enabled = true;
        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getEndpoint() { return endpoint; } public void setEndpoint(String v) { endpoint = v; }
        public String getAccessKey() { return accessKey; } public void setAccessKey(String v) { accessKey = v; }
        public String getSecretKey() { return secretKey; } public void setSecretKey(String v) { secretKey = v; }
        public String getBucket() { return bucket; } public void setBucket(String v) { bucket = v; }
        public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { enabled = v; }
    }
}
