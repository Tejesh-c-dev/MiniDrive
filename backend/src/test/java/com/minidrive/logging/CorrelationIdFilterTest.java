package com.minidrive.logging;

import jakarta.servlet.DispatcherType;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void preservesValidIdAndSetsResponseHeaderAndRequestAttribute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/files");
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "client-req_42.abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            assertEquals("client-req_42.abc", org.slf4j.MDC.get("correlation_id"));
        });

        assertEquals("client-req_42.abc", response.getHeader(CorrelationIdFilter.HEADER_NAME));
        assertEquals("client-req_42.abc", request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE));
        assertNull(org.slf4j.MDC.get("correlation_id"));
    }

    @Test
    void generatesUuidForMissingMalformedAndOversizedIds() throws Exception {
        for (String invalid : new String[] {null, "bad\nid", "unsafe/value", "x".repeat(65)}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
            if (invalid != null) {
                request.addHeader(CorrelationIdFilter.HEADER_NAME, invalid);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {
                String id = (String) req.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
                assertDoesNotThrow(() -> UUID.fromString(id));
            });
            assertEquals(request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE),
                    response.getHeader(CorrelationIdFilter.HEADER_NAME));
        }
    }

    @Test
    void clearsMdcWhenTheDownstreamChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/files");
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "exception-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(IllegalStateException.class, () -> filter.doFilter(request, response,
                (req, res) -> {
                    assertNotNull(org.slf4j.MDC.get("correlation_id"));
                    throw new IllegalStateException("expected");
                }));
        assertEquals("exception-123", response.getHeader(CorrelationIdFilter.HEADER_NAME));
        assertNull(org.slf4j.MDC.get("correlation_id"));
    }

    @Test
    void preservesIdAndReinstallsMdcAcrossAsyncRedispatches() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/files/1/download");
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "async-123");
        request.setAsyncStarted(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
                assertEquals("async-123", org.slf4j.MDC.get("correlation_id")));
        assertNull(org.slf4j.MDC.get("correlation_id"));

        request.setDispatcherType(DispatcherType.ASYNC);
        request.setAsyncStarted(false);
        Logger logger = (Logger) LoggerFactory.getLogger(CorrelationIdFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            filter.doFilter(request, response, (req, res) ->
                    assertEquals("async-123", org.slf4j.MDC.get("correlation_id")));
            assertEquals(1, appender.list.size(), "async redispatch should produce one completion record");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertEquals("async-123", response.getHeader(CorrelationIdFilter.HEADER_NAME));
        assertNull(org.slf4j.MDC.get("correlation_id"));
    }
}
