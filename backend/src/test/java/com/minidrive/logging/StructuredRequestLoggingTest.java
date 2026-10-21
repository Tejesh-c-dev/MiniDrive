package com.minidrive.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class StructuredRequestLoggingTest {

    @Test
    void emitsStructuredCompletionFieldsWithoutCredentialsOrQueryParameters() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(CorrelationIdFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
            request.setDispatcherType(DispatcherType.REQUEST);
            request.addHeader(CorrelationIdFilter.HEADER_NAME, "log-test_12");
            request.addHeader("Authorization", "Bearer sensitive-bearer-token");
            request.addParameter("shareToken", "sensitive-query-token");
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/auth/login");
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.setStatus(401);

            new CorrelationIdFilter().doFilter(request, response, (req, res) -> { });

            assertEquals(1, appender.list.size());
            ILoggingEvent event = appender.list.get(0);
            assertEquals("HTTP request completed", event.getFormattedMessage());
            Map<String, String> fields = event.getKeyValuePairs().stream()
                    .collect(Collectors.toMap(pair -> pair.key, pair -> String.valueOf(pair.value)));
            assertEquals("log-test_12", event.getMDCPropertyMap().get("correlation_id"));
            assertEquals("POST", fields.get("http_method"));
            assertEquals("/api/auth/login", fields.get("route"));
            assertEquals("401", fields.get("response_status"));
            assertTrue(fields.containsKey("duration_ms"));
            assertFalse(fields.containsValue("sensitive-bearer-token"));
            assertFalse(fields.containsValue("sensitive-query-token"));
            assertFalse(fields.containsKey("Authorization"));
            assertFalse(fields.containsKey("shareToken"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
