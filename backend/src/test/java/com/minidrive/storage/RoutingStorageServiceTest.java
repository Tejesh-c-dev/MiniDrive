package com.minidrive.storage;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class RoutingStorageServiceTest {
    @Test void uploadRegistersSelectedNodeAndRepeatReusesIt() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        NodeStorageClient primary=mock(NodeStorageClient.class), secondary=mock(NodeStorageClient.class);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any(Object[].class)))
                .thenReturn(null).thenReturn("secondary");
        RoutingStorageService storage=new RoutingStorageService(jdbc,
                Map.of("primary",primary,"secondary",secondary), (key,ids)->"secondary");
        storage.upload("key",new ByteArrayInputStream(new byte[]{1}),1,"application/octet-stream");
        storage.upload("key",new ByteArrayInputStream(new byte[]{2}),1,"application/octet-stream");
        verify(jdbc).update(contains("insert into object_locations"),eq("key"),eq("secondary"));
        verify(secondary,times(2)).upload(eq("key"),any(),eq(1L),anyString());
    }

    @Test void downloadAndDeleteUseRecordedNodeAndDeleteFailureKeepsRegistry() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        NodeStorageClient primary=mock(NodeStorageClient.class), secondary=mock(NodeStorageClient.class);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any(Object[].class)))
                .thenReturn("primary");
        RoutingStorageService storage=new RoutingStorageService(jdbc,
                Map.of("primary",primary,"secondary",secondary), (key,ids)->"secondary");
        storage.download("key");
        verify(primary).download("key");
        doThrow(new RuntimeException("offline")).when(primary).delete("key");
        assertThrows(RuntimeException.class,()->storage.delete("key"));
        verify(jdbc,never()).update(startsWith("delete from object_locations"),any(),any());
    }

    @Test void selectorIsDeterministicAndDistributesAcrossNodes() {
        StableStorageNodeSelector selector=new StableStorageNodeSelector();
        assertEquals(selector.select("one",List.of("primary","secondary")), selector.select("one",List.of("secondary","primary")));
        long distinct=java.util.stream.IntStream.range(0,100).mapToObj(i->selector.select("object-"+i,List.of("primary","secondary"))).distinct().count();
        assertEquals(2,distinct);
    }
}
