package com.minidrive.storage;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class RoutingStorageServiceTest {
    @Test void uploadRegistersSelectedNodeAndRepeatReusesIt() {
        SimpleMeterRegistry registry=new SimpleMeterRegistry();
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        NodeStorageClient primary=mock(NodeStorageClient.class), secondary=mock(NodeStorageClient.class);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any(Object[].class)))
                .thenReturn(null).thenReturn("secondary");
        RoutingStorageService storage=new RoutingStorageService(jdbc,
                Map.of("primary",primary,"secondary",secondary), (key,ids)->"secondary",registry);
        storage.upload("key",new ByteArrayInputStream(new byte[]{1}),1,"application/octet-stream");
        storage.upload("key",new ByteArrayInputStream(new byte[]{2}),1,"application/octet-stream");
        verify(jdbc).update(contains("insert into object_locations"),eq("key"),eq("secondary"));
        verify(secondary,times(2)).upload(eq("key"),any(),eq(1L),anyString());
        assertEquals(2.0,registry.counter("minidrive_storage_node_bytes_total","operation","upload","node_id","secondary").count());
        assertEquals(2,registry.get("minidrive_storage_node_operation_seconds").tag("operation","upload").tag("outcome","success").tag("node_id","secondary").timer().count());
    }

    @Test void downloadAndDeleteUseRecordedNodeAndDeleteFailureKeepsRegistry() {
        SimpleMeterRegistry registry=new SimpleMeterRegistry();
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        NodeStorageClient primary=mock(NodeStorageClient.class), secondary=mock(NodeStorageClient.class);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any(Object[].class)))
                .thenReturn("primary");
        when(primary.download("key")).thenReturn(new ByteArrayInputStream(new byte[]{1,2,3}));
        RoutingStorageService storage=new RoutingStorageService(jdbc,
                Map.of("primary",primary,"secondary",secondary), (key,ids)->"secondary",registry);
        try { assertEquals(3,storage.download("key").readAllBytes().length); } catch (java.io.IOException e) { fail(e); }
        verify(primary).download("key");
        doThrow(new RuntimeException("offline")).when(primary).delete("key");
        assertThrows(RuntimeException.class,()->storage.delete("key"));
        assertEquals(1,registry.get("minidrive_storage_node_operation_seconds").tag("operation","download").tag("outcome","success").tag("node_id","primary").timer().count());
        assertEquals(1,registry.get("minidrive_storage_node_operation_seconds").tag("operation","delete").tag("outcome","failure").tag("node_id","primary").timer().count());
        assertEquals(3.0,registry.counter("minidrive_storage_node_bytes_total","operation","download","node_id","primary").count());
        verify(jdbc,never()).update(startsWith("delete from object_locations"),any(),any());
    }

    @Test void selectorIsDeterministicAndDistributesAcrossNodes() {
        StableStorageNodeSelector selector=new StableStorageNodeSelector();
        assertEquals(selector.select("one",List.of("primary","secondary")), selector.select("one",List.of("secondary","primary")));
        long distinct=java.util.stream.IntStream.range(0,100).mapToObj(i->selector.select("object-"+i,List.of("primary","secondary"))).distinct().count();
        assertEquals(2,distinct);
    }

    @Test void downloadReadFailureIsRecordedAtNodeBoundary() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        NodeStorageClient primary=mock(NodeStorageClient.class);
        SimpleMeterRegistry registry=new SimpleMeterRegistry();
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any(Object[].class)))
                .thenReturn("primary");
        when(primary.download("key")).thenReturn(new java.io.InputStream() {
            @Override public int read() throws java.io.IOException { throw new java.io.IOException("offline"); }
        });
        RoutingStorageService storage=new RoutingStorageService(jdbc,Map.of("primary",primary),(key,ids)->"primary",registry);
        assertThrows(java.io.IOException.class,()->storage.download("key").readAllBytes());
        assertEquals(1,registry.get("minidrive_storage_node_operation_seconds")
                .tag("operation","download").tag("outcome","failure").tag("node_id","primary").timer().count());
    }

    @Test void uploadFailureIsRecordedWithoutCountingTransferredBytes() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        NodeStorageClient primary=mock(NodeStorageClient.class);
        SimpleMeterRegistry registry=new SimpleMeterRegistry();
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any(Object[].class)))
                .thenReturn("primary");
        doThrow(new RuntimeException("offline")).when(primary)
                .upload(eq("key"), any(), eq(4L), anyString());
        RoutingStorageService storage=new RoutingStorageService(jdbc,Map.of("primary",primary),(key,ids)->"primary",registry);

        assertThrows(RuntimeException.class,()->storage.upload("key",new ByteArrayInputStream(new byte[4]),4,"application/octet-stream"));
        assertEquals(1,registry.get("minidrive_storage_node_operation_seconds")
                .tag("operation","upload").tag("outcome","failure").tag("node_id","primary").timer().count());
        assertEquals(0.0,registry.counter("minidrive_storage_node_bytes_total","operation","upload","node_id","primary").count());
    }
}
