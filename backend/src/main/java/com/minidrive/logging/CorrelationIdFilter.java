package com.minidrive.logging;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.EnumSet;
import java.util.UUID;
import java.util.regex.Pattern;

/** Adds a validated request correlation ID to response headers and per-dispatch MDC. */
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String REQUEST_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";

    private static final String START_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".start";
    private static final String LOGGED_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".logged";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String correlationId = correlationId(request);
        request.setAttribute(REQUEST_ATTRIBUTE, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        long startedAt = startTime(request);
        MDC.put("correlation_id", correlationId);

        boolean returnedNormally = false;
        try {
            filterChain.doFilter(request, response);
            returnedNormally = true;
        } finally {
            try {
                if (returnedNormally && !request.isAsyncStarted()
                        && !Boolean.TRUE.equals(request.getAttribute(LOGGED_ATTRIBUTE))) {
                    logCompletion(request, response, correlationId, startedAt);
                    request.setAttribute(LOGGED_ATTRIBUTE, Boolean.TRUE);
                }
            } finally {
                MDC.remove("correlation_id");
            }
        }
    }

    private static String correlationId(HttpServletRequest request) {
        Object existing = request.getAttribute(REQUEST_ATTRIBUTE);
        if (existing instanceof String id && SAFE_ID.matcher(id).matches()) {
            return id;
        }

        String incoming = request.getHeader(HEADER_NAME);
        return incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();
    }

    private static long startTime(HttpServletRequest request) {
        Object existing = request.getAttribute(START_ATTRIBUTE);
        if (existing instanceof Long startedAt) {
            return startedAt;
        }
        long startedAt = System.nanoTime();
        request.setAttribute(START_ATTRIBUTE, startedAt);
        return startedAt;
    }

    private static void logCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            String correlationId,
            long startedAt) {
        Object matchedPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = matchedPattern == null ? "unmatched" : matchedPattern.toString();
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        log.atInfo()
                .addKeyValue("http_method", request.getMethod())
                .addKeyValue("route", route)
                .addKeyValue("response_status", response.getStatus())
                .addKeyValue("duration_ms", elapsedMillis)
                .log("HTTP request completed");
    }

    @Configuration(proxyBeanMethods = false)
    static class Registration {
        @Bean
        FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
            FilterRegistrationBean<CorrelationIdFilter> registration =
                    new FilterRegistrationBean<>(new CorrelationIdFilter());
            registration.setName("correlationIdFilter");
            registration.setDispatcherTypes(EnumSet.of(
                    DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR));
            registration.setAsyncSupported(true);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
            return registration;
        }
    }
}
