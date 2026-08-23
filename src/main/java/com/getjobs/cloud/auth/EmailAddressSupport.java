package com.getjobs.cloud.auth;

import java.util.Locale;

public final class EmailAddressSupport {
    private EmailAddressSupport() {
    }

    public static String normalize(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    public static String mask(String email) {
        String normalized = normalize(email);
        int at = normalized.indexOf('@');
        if (at <= 0 || at == normalized.length() - 1) {
            return "***";
        }
        String local = normalized.substring(0, at);
        String visible = local.length() == 1 ? local : local.substring(0, Math.min(2, local.length()));
        return visible + "***@" + normalized.substring(at + 1);
    }
}
