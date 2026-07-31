package com.enterprise.apidrift.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory rate limiter for /api/v1/** endpoints.
 * Limits to 60 requests per minute per client IP.
 * Returns HTTP 429 with Retry-After header when exceeded.
 */
@Slf4j
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_REQUESTS_PER_MINUTE = 60;
    private static final long WINDOW_MS = 60_000;

    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = request.getRemoteAddr();
        long now = System.currentTimeMillis();

        WindowCounter counter = counters.compute(clientIp, (ip, c) -> {
            if (c == null || now - c.windowStart > WINDOW_MS) {
                return new WindowCounter(now, 1);
            }
            c.count++;
            return c;
        });

        if (counter.count > MAX_REQUESTS_PER_MINUTE) {
            log.warn("Rate limit exceeded for IP {} — {} requests in window", clientIp, counter.count);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Too many requests — rate limit exceeded\",\"retryAfterSeconds\":60}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static class WindowCounter {
        final long windowStart;
        int count;

        WindowCounter(long windowStart, int count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }
}
