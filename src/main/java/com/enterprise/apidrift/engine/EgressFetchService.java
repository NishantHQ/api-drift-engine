package com.enterprise.apidrift.engine;

import com.enterprise.apidrift.config.EgressProxyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/**
 * Fetches remote OpenAPI specs through a strict egress proxy with SSRF prevention.
 * Blocks loopback, link-local, and private RFC 1918 IP ranges.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EgressFetchService {

    private final WebClient egressWebClient;

    private static final List<String> BLOCKED_HOSTS = Arrays.asList(
            "localhost", "127.0.0.1", "0.0.0.0", "[::1]"
    );

    /**
     * Fetches the raw OpenAPI spec from the given URL.
     *
     * @param url       the remote spec URL
     * @param authHeader optional auth header value (e.g. "Bearer token")
     * @return raw spec content as String (JSON or YAML)
     * @throws SecurityException if the target IP is blocked
     * @throws RuntimeException on fetch failure
     */
    public String fetchSpec(String url, String authHeader) {
        URI uri = URI.create(url);
        validateTarget(uri);

        WebClient.RequestHeadersSpec<?> request = egressWebClient
                .get()
                .uri(uri);

        if (authHeader != null && !authHeader.isBlank()) {
            request.header("Authorization", authHeader);
        }
        request.header("Accept", "application/json, application/x-yaml, text/yaml, */*");

        try {
            String body = request
                    .retrieve()
                    .onStatus(status -> status.isError(), response ->
                            Mono.error(new RuntimeException(
                                    "Failed to fetch spec: HTTP " + response.statusCode())))
                    .bodyToMono(String.class)
                    .block();

            log.info("Successfully fetched spec from {} ({} bytes)", url,
                    body != null ? body.length() : 0);
            return body;
        } catch (WebClientResponseException e) {
            log.error("HTTP error fetching spec from {}: {}", url, e.getMessage());
            throw new RuntimeException("Failed to fetch spec from " + url + ": " + e.getMessage(), e);
        }
    }

    /**
     * Validates the target URI for SSRF prevention.
     * Blocks: loopback, link-local (169.254.x.x), and RFC 1918 private ranges.
     */
    void validateTarget(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SecurityException("Invalid target URI: no host specified");
        }

        // Block known-bad hostnames
        String lowerHost = host.toLowerCase();
        for (String blocked : BLOCKED_HOSTS) {
            if (lowerHost.equals(blocked)) {
                throw new SecurityException("SSRF blocked: host " + host + " is forbidden");
            }
        }

        try {
            InetAddress address = InetAddress.getByName(host);
            byte[] octets = address.getAddress();

            if (address.isLoopbackAddress()) {
                throw new SecurityException("SSRF blocked: " + host + " is loopback");
            }
            if (address.isLinkLocalAddress()) {
                throw new SecurityException("SSRF blocked: " + host + " is link-local");
            }
            if (address.isSiteLocalAddress()) {
                throw new SecurityException("SSRF blocked: " + host + " is private/RFC 1918");
            }

            // Additional IPv4 private range check
            if (octets.length == 4) {
                int first = octets[0] & 0xFF;
                int second = octets[1] & 0xFF;

                // 10.0.0.0/8
                if (first == 10) {
                    throw new SecurityException("SSRF blocked: " + host + " in 10.0.0.0/8");
                }
                // 172.16.0.0/12
                if (first == 172 && second >= 16 && second <= 31) {
                    throw new SecurityException("SSRF blocked: " + host + " in 172.16.0.0/12");
                }
                // 192.168.0.0/16
                if (first == 192 && second == 168) {
                    throw new SecurityException("SSRF blocked: " + host + " in 192.168.0.0/16");
                }
                // 169.254.0.0/16 (AWS metadata, etc.)
                if (first == 169 && second == 254) {
                    throw new SecurityException("SSRF blocked: " + host + " in 169.254.0.0/16");
                }
            }

            log.debug("SSRF validation passed for {} -> {}", host, address.getHostAddress());
        } catch (UnknownHostException e) {
            // Allow — hostname resolution failure is not an SSRF concern
            log.debug("Could not resolve host {} for SSRF pre-check; proceeding", host);
        }
    }
}
