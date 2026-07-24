package com.orffyrus.pest;

import java.util.Locale;
import java.util.regex.Pattern;

public final class PestChatRouter {

    private static final Pattern ADDRESS = Pattern.compile(
            "(?i)(?:^|\\b)pest(?:\\b|[,:!?]|$)"
    );

    private PestChatRouter() { }

    public static boolean addressesPest(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        return ADDRESS.matcher(content.trim()).find();
    }

    public static String stripAddress(String content) {
        if (content == null) {
            return "";
        }
        String t = content.trim();
        String stripped = t.replaceFirst("(?i)^\\s*pest\\s*[,:!?.\\-]*\\s*", "");
        if (stripped.isBlank()) {
            return t;
        }
        return stripped;
    }

    public static boolean isPestRole(String roleOrName) {
        if (roleOrName == null) {
            return false;
        }
        String n = roleOrName.toLowerCase(Locale.ROOT);
        return n.equals("pest");
    }
}
