package com.minidrive.storage;

import com.minidrive.exception.StorageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.io.InputStream;
import java.util.Map;

@Service
public class RoutingStorageService implements StorageService {
    private final JdbcTemplate jdbc;
    private final Map<String,NodeStorageClient> nodes;
    private final StorageNodeSelector selector;
    public RoutingStorageService(JdbcTemplate jdbc, @Qualifier("storageNodeClients") Map<String,NodeStorageClient> nodes, StorageNodeSelector selector) {
        this.jdbc=jdbc; this.nodes=nodes; this.selector=selector;
    }
    private String existing(String key) {
        return jdbc.query("select storage_node_id from object_locations where object_key = ?", rs -> rs.next()?rs.getString(1):null, key);
    }
    private NodeStorageClient client(String id) {
        NodeStorageClient client=nodes.get(id);
        if(client==null) throw new StorageException("Storage node required for an object is not configured: "+id);
        return client;
    }
    @Override public void upload(String key, InputStream in, long size, String type) {
        String location=existing(key);
        if(location==null) {
            String selected=selector.select(key,nodes.keySet());
            jdbc.update("insert into object_locations(object_key,storage_node_id) values (?,?) on conflict (object_key) do nothing",key,selected);
            location=existing(key);
        }
        client(location).upload(key,in,size,type);
    }
    @Override public InputStream download(String key) {
        String location=existing(key); if(location==null) throw new StorageException("No storage location registered for object "+key);
        return client(location).download(key);
    }
    @Override public void delete(String key) {
        String location=existing(key); if(location==null) return;
        client(location).delete(key);
        jdbc.update("delete from object_locations where object_key=? and storage_node_id=?",key,location);
    }
}
