package com.enterprise.apidrift.engine;

import com.enterprise.apidrift.service.VendorHealthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for SSRF validation in EgressFetchService.
 * Full fetch integration requires WebClient mocking (integration test territory).
 */
class EgressFetchServiceTest {

    private EgressFetchService service;

    @BeforeEach
    void setUp() {
        // Minimal setup — only testing SSRF validation which doesn't need WebClient
        service = new EgressFetchService(null, new VendorHealthService());
    }

    @Test
    @DisplayName("localhost blocked")
    void localhostBlocked() {
        assertThatThrownBy(() -> service.validateTarget(URI.create("http://localhost:8080/spec.yaml")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("localhost");
    }

    @Test
    @DisplayName("127.0.0.1 blocked")
    void loopbackIpBlocked() {
        assertThatThrownBy(() -> service.validateTarget(URI.create("http://127.0.0.1/spec.yaml")))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("0.0.0.0 blocked")
    void zeroIpBlocked() {
        assertThatThrownBy(() -> service.validateTarget(URI.create("http://0.0.0.0/spec.yaml")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("0.0.0.0");
    }

    @Test
    @DisplayName("10.x.x.x (RFC 1918) blocked via site-local check")
    void private10Blocked() {
        assertThatThrownBy(() -> service.validateTarget(URI.create("http://10.0.0.1/spec.yaml")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("private/RFC 1918");
    }

    @Test
    @DisplayName("172.16.x.x (RFC 1918) blocked via site-local check")
    void private172Blocked() {
        assertThatThrownBy(() -> service.validateTarget(URI.create("http://172.16.0.1/spec.yaml")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("private/RFC 1918");
    }

    @Test
    @DisplayName("172.20.x.x (RFC 1918) blocked via site-local check")
    void private172MidBlocked() {
        assertThatThrownBy(() -> service.validateTarget(URI.create("http://172.20.5.5/spec.yaml")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("private/RFC 1918");
    }

    @Test
    @DisplayName("172.31.x.x (RFC 1918) blocked via site-local check")
    void private172EdgeBlocked() {
        assertThatThrownBy(() -> service.validateTarget(URI.create("http://172.31.255.255/spec.yaml")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("private/RFC 1918");
    }

    @Test
    @DisplayName("192.168.x.x (RFC 1918) blocked via site-local check")
    void private192Blocked() {
        assertThatThrownBy(() -> service.validateTarget(URI.create("http://192.168.1.1/spec.yaml")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("private/RFC 1918");
    }

    @Test
    @DisplayName("169.254.x.x (link-local) blocked")
    void linkLocalBlocked() {
        assertThatThrownBy(() -> service.validateTarget(URI.create("http://169.254.169.254/spec.yaml")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("169.254");
    }

    @Test
    @DisplayName("Example.com (public hostname, DNS resolves) passes validation")
    void publicHostnamePasses() {
        // Validates without throwing — DNS resolution succeeds, IP is public
        service.validateTarget(URI.create("http://example.com/spec.yaml"));
    }

    @Test
    @DisplayName("Null host throws SecurityException")
    void nullHostThrows() {
        assertThatThrownBy(() -> service.validateTarget(URI.create("http:///spec.yaml")))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("[::1] IPv6 loopback blocked")
    void ipv6LoopbackBlocked() {
        assertThatThrownBy(() -> service.validateTarget(URI.create("http://[::1]/spec.yaml")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("[::1]");
    }
}
