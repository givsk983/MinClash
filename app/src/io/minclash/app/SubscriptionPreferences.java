package io.minclash.app;

import android.content.SharedPreferences;
import java.util.Set;
import java.util.TreeSet;

final class SubscriptionPreferences {
    static final String STYLE = "subscription_card_style";
    static final String SHOW_PERCENT = "subscription_show_percent";
    static final String REMINDERS = "subscription_expiry_reminders";
    static final String DAYS = "subscription_reminder_days";
    static final String QUOTA = "quota", DUAL = "dual";
    static final Set<String> DEFAULT_DAYS = java.util.Collections.unmodifiableSet(
        new java.util.HashSet<>(java.util.Arrays.asList("7", "3", "1")));

    static String style(SharedPreferences preferences) {
        return DUAL.equals(preferences.getString(STYLE, QUOTA)) ? DUAL : QUOTA;
    }
    static boolean enabled(SharedPreferences preferences) { return preferences.getBoolean(REMINDERS, true); }
    static Set<Integer> days(SharedPreferences preferences) {
        Set<Integer> result = new TreeSet<>();
        for (String value : preferences.getStringSet(DAYS, DEFAULT_DAYS)) {
            try {
                int day = Integer.parseInt(value);
                if (day >= 1 && day <= 30) result.add(day);
            } catch (NumberFormatException invalid) { /* Ignore invalid saved values. */ }
        }
        return result;
    }
    static String daysLabel(SharedPreferences preferences) {
        return days(preferences).stream().sorted(java.util.Comparator.reverseOrder()).map(String::valueOf)
            .collect(java.util.stream.Collectors.joining("、"));
    }
    static Set<String> parseDays(String input) {
        Set<String> result = new java.util.LinkedHashSet<>();
        if (input.trim().isEmpty()) return result;
        for (String part : input.trim().split("[,，、\\s]+")) {
            if (!part.matches("[0-9]{1,2}")) throw new IllegalArgumentException();
            int day = Integer.parseInt(part);
            if (day < 1 || day > 30) throw new IllegalArgumentException();
            result.add(String.valueOf(day));
        }
        return result;
    }
}
