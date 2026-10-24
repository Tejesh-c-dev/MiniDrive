package com.minidrive.security;

import java.io.IOException;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Enforces IP policies before controller, credential, share-token, or storage work. */
@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private static final Logger log=LoggerFactory.getLogger(RateLimitFilter.class);
    private final RateLimitService limiter;
    private final ObjectMapper mapper;
    public RateLimitFilter(RateLimitService limiter, ObjectMapper mapper) { this.limiter=limiter; this.mapper=mapper; }

    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path=req.getRequestURI();
        boolean registration="POST".equals(req.getMethod()) && "/api/auth/register".equals(path);
        boolean login="POST".equals(req.getMethod()) && "/api/auth/login".equals(path);
        boolean share="GET".equals(req.getMethod()) && path.matches("/api/share/[^/]+(?:/download)?");
        if (!registration && !login && !share) { chain.doFilter(req,res); return; }
        String policy=registration?"registration":login?"login":"share";
        try {
            String ip=limiter.clientIp(req);
            if (registration) limiter.registration(ip);
            else if (login) limiter.loginIp(ip);
            else limiter.share(ip);
            req.setAttribute("minidrive.rate-limit.ip", ip);
            chain.doFilter(req,res);
        } catch (RateLimitExceededException e) {
            res.setHeader("Retry-After", Integer.toString(e.getRetryAfter()));
            write(res,429,"Too many requests");
        } catch (RateLimitUnavailableException e) {
            log.warn("Rate-limit store unavailable; rejecting protected request policy={} cause={}",
                    policy, e.getCause() == null ? "unknown" : e.getCause().getClass().getSimpleName());
            write(res,503,"Service temporarily unavailable");
        }
    }
    private void write(HttpServletResponse res,int status,String message) throws IOException {
        res.setStatus(status); res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), Map.of("error",message));
    }
}
