package io.forgetdm.policy;

import io.forgetdm.common.ApiException;

import java.util.regex.Pattern;

public final class PolicyNameRules {
    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 120;
    private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 _.-]*$");

    private PolicyNameRules() {}

    public static String normalize(String value) {
        if (value == null) throw ApiException.bad("Policy name is required");
        String clean = value.trim();
        if (clean.length() < MIN_LENGTH || clean.length() > MAX_LENGTH) {
            throw ApiException.bad("Policy name must be between 8 and 120 characters");
        }
        if (!ALLOWED.matcher(clean).matches()) {
            throw ApiException.bad("Policy name must start with a letter or number and use only letters, numbers, spaces, '.', '_' or '-'");
        }
        return clean;
    }
}
