package dev.nkap.agent.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VisitorKeyTest {

    @Test
    @DisplayName("by default a visitor is the socket address, and a forwarded-for header is ignored")
    void socketAddress() {
        assertThat(VisitorKey.of("198.51.100.7", List.of("203.0.113.9"), null)).isEqualTo("198.51.100.7");
        assertThat(VisitorKey.of("198.51.100.7", List.of("203.0.113.9"), "")).isEqualTo("198.51.100.7");
        assertThat(VisitorKey.of("198.51.100.7", List.of(), "10.0.0.1")).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("only when the peer is the trusted proxy is the last forwarded-for entry the visitor: the entry the proxy itself added")
    void trustedProxy() {
        assertThat(VisitorKey.of("10.0.0.1", List.of("1.1.1.1, 203.0.113.9"), "10.0.0.1")).isEqualTo("203.0.113.9");
        assertThat(VisitorKey.of("10.0.0.1", List.of("1.1.1.1", "2.2.2.2, 203.0.113.9 "), "10.0.0.1")).as("several header lines").isEqualTo("203.0.113.9");
        assertThat(VisitorKey.of("10.0.0.1", List.of("203.0.113.9"), "10.0.0.1")).isEqualTo("203.0.113.9");
        assertThat(VisitorKey.of("10.0.0.99", List.of("203.0.113.9"), "10.0.0.1")).as("a peer that is not the proxy cannot name a visitor")
                .isEqualTo("10.0.0.99");
    }

    @Test
    @DisplayName("a proxy that sent no usable entry leaves the visitor as the proxy's own address, never as text from the client")
    void unusableEntries() {
        for (List<String> bad : List.of(List.<String>of(), List.of(""), List.of("1.1.1.1,"), List.of("1.1.1.1, <script>"),
                List.of("not an address"), List.of("1.1.1.1, " + "9".repeat(60)))) {
            assertThat(VisitorKey.of("10.0.0.1", bad, "10.0.0.1")).as(bad.toString()).isEqualTo("10.0.0.1");
        }
    }

    @Test
    @DisplayName("an IPv6 entry is accepted")
    void ipv6() {
        assertThat(VisitorKey.of("10.0.0.1", List.of("1.1.1.1, 2001:db8::7"), "10.0.0.1")).isEqualTo("2001:db8::7");
    }
}
