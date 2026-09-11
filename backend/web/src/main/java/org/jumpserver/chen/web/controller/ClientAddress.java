package org.jumpserver.chen.web.controller;

import com.google.common.net.InetAddresses;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.Set;

/** Trust forwarded addresses only across explicitly configured proxy IPs. */
public final class ClientAddress {
    private ClientAddress() {}

    public static String resolve(HttpServletRequest request, String configuredProxies) {
        String peer = request.getRemoteAddr();
        Set<String> trusted = new HashSet<>();
        for (String value : configuredProxies.split(",")) {
            value = value.trim();
            if (!value.isEmpty()) trusted.add(normalize(value));
        }
        String address = normalize(peer);
        String forwarded = request.getHeader("x-forwarded-for");
        if (!trusted.contains(address) || forwarded == null || forwarded.length() > 4096) return peer;
        String[] hops = forwarded.split(",", -1);
        for (int i = hops.length - 1; i >= 0 && trusted.contains(address); i--) {
            try { address = normalize(hops[i].trim()); }
            catch (IllegalArgumentException invalid) { return peer; }
        }
        return address;
    }

    private static String normalize(String value) {
        return InetAddresses.toAddrString(InetAddresses.forString(value));
    }
}
