package io.forgetdm.provision;

import io.forgetdm.common.ApiException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SyntheticApiSafety {
    private SyntheticApiSafety() {}

    static URI validate(String rawUrl, String allowlist) {
        if (rawUrl == null || rawUrl.isBlank()) throw ApiException.bad("API generator needs a URL in param1");
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (Exception e) {
            throw ApiException.bad("API generator URL is invalid: " + e.getMessage());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https"))
            throw ApiException.bad("API generator only supports http/https URLs");
        if (uri.getHost() == null || uri.getHost().isBlank())
            throw ApiException.bad("API generator URL must include a host");
        if (uri.getUserInfo() != null || uri.getFragment() != null)
            throw ApiException.bad("API generator URL must not include credentials or fragments");
        if (!allowed(uri, allowlist))
            throw ApiException.bad("API generator host '" + uri.getHost() + "' is not allowed. Set FORGETDM_SYNTH_API_ALLOWLIST to permitted hosts.");
        return uri;
    }

    static String configuredAllowlist() {
        String prop = System.getProperty("forgetdm.synthetic.api.allowlist");
        if (prop != null && !prop.isBlank()) return prop;
        String env = System.getenv("FORGETDM_SYNTH_API_ALLOWLIST");
        return env == null ? "" : env;
    }

    static boolean allowed(URI uri, String allowlist) {
        if (allowlist == null || allowlist.isBlank()) return false;
        String host = normalizeHost(uri.getHost());
        int port = effectivePort(uri);
        for (Rule rule : parseRules(allowlist)) {
            if (rule.matches(host, port)) return true;
        }
        return false;
    }

    private static List<Rule> parseRules(String allowlist) {
        List<Rule> rules = new ArrayList<>();
        for (String raw : allowlist.split("[,;]")) {
            String token = raw.trim();
            if (token.isBlank()) continue;
            try {
                boolean suffixToken = token.startsWith("*.") || token.startsWith(".");
                String uriToken = suffixToken
                        ? (token.startsWith("*.") ? token.substring(2) : token.substring(1))
                        : token;
                URI uri = uriToken.contains("://") ? URI.create(uriToken) : URI.create("http://" + uriToken);
                String host = uri.getHost();
                if (host == null || host.isBlank()) continue;
                String normalized = normalizeHost(host);
                boolean suffix = suffixToken || normalized.startsWith("*.") || normalized.startsWith(".");
                if (normalized.startsWith("*.")) normalized = normalized.substring(2);
                else if (normalized.startsWith(".")) {
                    suffix = true;
                    normalized = normalized.substring(1);
                }
                rules.add(new Rule(normalized, uri.getPort(), suffix));
            } catch (Exception ignore) {
                // Ignore malformed allowlist entries; a bad entry should not accidentally allow traffic.
            }
        }
        return rules;
    }

    private static String normalizeHost(String host) {
        String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        return h.startsWith("[") && h.endsWith("]") ? h.substring(1, h.length() - 1) : h;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ("http".equals(scheme)) return 80;
        if ("https".equals(scheme)) return 443;
        return -1;
    }

    private record Rule(String host, int port, boolean suffix) {
        boolean matches(String actualHost, int actualPort) {
            if (port >= 0 && port != actualPort) return false;
            if (suffix) return actualHost.equals(host) || actualHost.endsWith("." + host);
            return actualHost.equals(host);
        }
    }
}
