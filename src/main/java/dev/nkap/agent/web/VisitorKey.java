package dev.nkap.agent.web;

import java.util.ArrayList;
import java.util.List;

/**
 * What the service takes a visitor to be: the socket address of the peer. Only when that peer is the one proxy the
 * operator named ({@code NKAP_AGENT_TRUSTED_PROXY}) is it the last {@code X-Forwarded-For} entry instead, which is the
 * address the proxy itself added; entries to the left of it are the client's to write and are never read. No other
 * header is ever looked at. An entry that is not an address leaves the visitor as the proxy.
 *
 * <p>The result is only ever hashed (see {@code LiveBudget}); it is not logged or returned.
 */
final class VisitorKey {

    private VisitorKey() {
    }

    static String of(String peerAddress, List<String> forwardedFor, String trustedProxy) {
        if (trustedProxy == null || trustedProxy.isBlank() || !trustedProxy.equals(peerAddress) || forwardedFor == null) {
            return peerAddress;
        }
        List<String> entries = new ArrayList<>();
        for (String line : forwardedFor) {
            for (String part : line.split(",", -1)) {
                entries.add(part.strip());
            }
        }
        if (entries.isEmpty()) {
            return peerAddress;
        }
        String last = entries.get(entries.size() - 1);
        return last.matches("[0-9a-fA-F:.]{2,45}") ? last : peerAddress;
    }
}
