package io.forgetdm.security;

import java.util.Optional;

public final class AccessContext {
    private static final ThreadLocal<AccessPrincipal> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<String> TOKEN = new ThreadLocal<>();

    private AccessContext() {}

    public static Optional<AccessPrincipal> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Raw session token of the current request, so in-process self-calls (e.g. the AI assistant's tool calls)
     *  can re-authenticate as the same caller with the same permissions. */
    public static Optional<String> currentToken() {
        return Optional.ofNullable(TOKEN.get());
    }

    static void set(AccessPrincipal principal, String token) {
        CURRENT.set(principal);
        TOKEN.set(token);
    }

    static void clear() {
        CURRENT.remove();
        TOKEN.remove();
    }
}
