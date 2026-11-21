package com.universium.webcraft.core;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

final class WebCraftOrigins {
    private final Set<String> configured;

    WebCraftOrigins(List<? extends String> origins) {
        configured = origins.stream().map(value -> {
            String origin = canonical(value);
            if (origin == null) {
                throw new IllegalArgumentException("websocketOrigins requires HTTP(S) origins without a path");
            }
            return origin;
        }).collect(Collectors.toUnmodifiableSet());
    }

    boolean permits(List<String> origins, List<String> hosts) {
        if (origins.isEmpty()) return true;
        if (origins.size() != 1) return false;
        String origin = canonical(origins.getFirst());
        return origin != null && (configured.contains(origin)
                || hosts.size() == 1 && Objects.equals(origin, canonical("http://" + hosts.getFirst())));
    }

    private static String canonical(String value) {
        try {
            var uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !uri.getRawPath().isEmpty()
                    || uri.getPort() == 0 || uri.getPort() > 65535) return null;
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            int port = uri.getPort();
            int defaultPort = scheme.equals("http") ? 80 : 443;
            return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                    + (port == -1 || port == defaultPort ? "" : ":" + port);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
