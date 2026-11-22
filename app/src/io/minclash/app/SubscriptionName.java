package io.minclash.app;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SubscriptionName {
    private static final Pattern PARAMETER = Pattern.compile(
        ";\\s*([\\w*-]+)\\s*=\\s*(?:\"((?:[^\"\\\\]|\\\\.)*+)\"|([^;\"]*))");
    private static final Pattern QUOTED_PAIR = Pattern.compile("\\\\(.)");
    private static final Pattern YAML_EXTENSION = Pattern.compile("(?i)\\.ya?ml$");
    private static final Pattern UNSAFE = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}\\u202A-\\u202E\\u2066-\\u2069\\uFFFD/\\\\]");
    private static final Set<String> GENERIC_FILENAMES = Set.of("clash", "mihomo", "config", "profile", "subscription", "subscribe");

    static String fromHeaders(String title, String disposition, String host) {
        String name = profileTitle(title);
        if (name != null) return name;
        if (disposition == null || disposition.length() > 4096) return host;

        String filename = null;
        Matcher parameter = PARAMETER.matcher(disposition);
        while (parameter.find()) {
            String quoted = parameter.group(2);
            String value = quoted == null ? parameter.group(3).trim() : QUOTED_PAIR.matcher(quoted).replaceAll("$1");
            if (parameter.group(1).equalsIgnoreCase("filename*")) {
                name = filename(decodeExtended(value));
                if (name != null) return name;
            } else if (parameter.group(1).equalsIgnoreCase("filename") && filename == null) {
                filename = filename(value);
            }
        }
        return filename == null ? host : filename;
    }

    private static String profileTitle(String title) {
        if (title == null || title.length() > 1024) return null;
        String value = title.trim();
        if (value.regionMatches(true, 0, "base64:", 0, 7)) {
            try {
                value = new String(Base64.getDecoder().decode(value.substring(7).trim()), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException malformed) {
                return null;
            }
        }
        return displayName(value);
    }

    private static String decodeExtended(String value) {
        String[] parts = value.split("'", 3);
        if (parts.length != 3) return null;
        String charset = parts[0];
        if (!charset.equalsIgnoreCase("UTF-8") && !charset.equalsIgnoreCase("ISO-8859-1")) return null;
        try {
            return URLDecoder.decode(parts[2].replace("+", "%2B"), charset);
        } catch (IllegalArgumentException | UnsupportedEncodingException malformed) {
            return null;
        }
    }

    private static String filename(String value) {
        if (value == null) return null;
        String name = displayName(YAML_EXTENSION.matcher(value.trim()).replaceFirst(""));
        return name == null || GENERIC_FILENAMES.contains(name.toLowerCase(Locale.ROOT)) ? null : name;
    }

    private static String displayName(String value) {
        String name = value.trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.equalsIgnoreCase("null")
            || name.codePointCount(0, name.length()) > 120 || UNSAFE.matcher(value).find()) return null;
        return name;
    }

    private SubscriptionName() {}
}
