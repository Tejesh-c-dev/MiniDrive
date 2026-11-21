package com.minidrive.service;

import com.minidrive.config.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

@Component
public class ClientIpResolver {
    private final List<Network> trustedProxies;

    public ClientIpResolver(RateLimitProperties properties) {
        this.trustedProxies = properties.getTrustedProxies().stream()
                .map(Network::parse).toList();
    }

    public String resolve(HttpServletRequest request) {
        String peer = numericAddress(request.getRemoteAddr());
        if (peer == null || !isTrusted(peer)) return peer == null ? "unknown" : peer;

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return peer;

        String[] parts = forwarded.split(",", -1);
        List<String> chain = new ArrayList<>(parts.length + 1);
        for (String part : parts) {
            String parsed = numericAddress(part.trim());
            if (parsed == null) return peer;
            chain.add(parsed);
        }
        chain.add(peer);
        for (int i = chain.size() - 1; i >= 0; i--) {
            String address = chain.get(i);
            if (!isTrusted(address)) return address;
        }
        return chain.get(0);
    }

    private boolean isTrusted(String address) {
        return trustedProxies.stream().anyMatch(network -> network.contains(address));
    }

    private static String numericAddress(String input) {
        if (input == null || input.isBlank()) return null;
        String value = input.trim();
        if (!(value.matches("[0-9.]+") || value.matches("[0-9a-fA-F:]+"))) return null;
        try {
            return InetAddress.getByName(value).getHostAddress();
        } catch (Exception ignored) {
            return null;
        }
    }

    private record Network(byte[] address, int prefix) {
        static Network parse(String value) {
            String[] pieces = value.trim().split("/", -1);
            String ip = numericAddress(pieces[0]);
            if (ip == null) throw new IllegalArgumentException("Invalid trusted proxy network");
            try {
                byte[] address = InetAddress.getByName(ip).getAddress();
                int prefix = pieces.length == 1 ? address.length * 8 : Integer.parseInt(pieces[1]);
                if (pieces.length > 2 || prefix < 0 || prefix > address.length * 8) {
                    throw new IllegalArgumentException("Invalid trusted proxy network");
                }
                return new Network(address, prefix);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid trusted proxy network", e);
            }
        }

        boolean contains(String candidate) {
            try {
                byte[] bytes = InetAddress.getByName(candidate).getAddress();
                if (bytes.length != address.length) return false;
                int fullBytes = prefix / 8;
                int remainingBits = prefix % 8;
                for (int i = 0; i < fullBytes; i++) if (bytes[i] != address[i]) return false;
                if (remainingBits == 0) return true;
                int mask = 0xff << (8 - remainingBits);
                return (bytes[fullBytes] & mask) == (address[fullBytes] & mask);
            } catch (Exception ignored) {
                return false;
            }
        }
    }
}
