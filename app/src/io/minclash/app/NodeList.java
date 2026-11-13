package io.minclash.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps source indices intact when the node picker is filtered or reordered. */
final class NodeList {
    static boolean testable(String name) {
        return !name.equals("DIRECT") && !name.equals("REJECT") && !name.equals("REJECT-DROP")
            && !name.equals("PASS") && !name.equals("COMPATIBLE");
    }

    static String favoritesKey(String subscription) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(subscription.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder("node_favorites_");
            for (byte value : digest) key.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return key.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static List<Integer> order(List<String> names, Set<String> favorites, Map<String, Integer> delays,
                               boolean byDelay, boolean hideFailed) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            Integer delay = delays.get(names.get(i));
            if (!hideFailed || delay == null || delay > 0) indices.add(i);
        }
        Comparator<Integer> comparator = Comparator.comparing(index -> !favorites.contains(names.get(index)));
        if (byDelay) comparator = comparator.thenComparingInt(index -> {
            Integer delay = delays.get(names.get(index));
            return delay == null ? Integer.MAX_VALUE - 1 : delay > 0 ? delay : Integer.MAX_VALUE;
        });
        indices.sort(comparator.thenComparingInt(Integer::intValue));
        return indices;
    }

    private NodeList() {}
}
