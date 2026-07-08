package io.forgetdm.core.util;

/** Luhn (mod-10) utilities — used for credit-card masking and format validation. */
public final class Luhn {
    private Luhn() {}

    public static boolean isValid(String digits) {
        if (digits == null || digits.length() < 12) return false;
        int sum = 0; boolean alt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') return false;
            int d = c - '0';
            if (alt) { d *= 2; if (d > 9) d -= 9; }
            sum += d; alt = !alt;
        }
        return sum % 10 == 0;
    }

    /** Compute the check digit that makes (body + digit) Luhn-valid. */
    public static char checkDigit(String body) {
        int sum = 0; boolean alt = true; // body excludes the check digit position
        for (int i = body.length() - 1; i >= 0; i--) {
            int d = body.charAt(i) - '0';
            if (alt) { d *= 2; if (d > 9) d -= 9; }
            sum += d; alt = !alt;
        }
        return (char) ('0' + ((10 - (sum % 10)) % 10));
    }
}
