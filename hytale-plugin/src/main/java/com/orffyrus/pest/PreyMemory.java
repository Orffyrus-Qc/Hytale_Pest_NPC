package com.orffyrus.pest;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Last non-owner NPC noticed for hunting (hide / meat / drops).
 */
public final class PreyMemory {

    private static final long STALE_AFTER_MILLIS = 15_000L;

    private record Snapshot(String description, double distance, long observedAtMillis) { }

    private static final ConcurrentHashMap<String, Snapshot> LAST = new ConcurrentHashMap<>();

    private PreyMemory() { }

    public static void record(String npcId, double distance) {
        int rounded = (int) Math.round(distance);
        LAST.put(npcId, new Snapshot(
                "Prey/wildlife about " + rounded + " blocks away (hunt for hide/drops).",
                distance,
                System.currentTimeMillis()));
    }

    public static String describe(String npcId) {
        Snapshot s = LAST.get(npcId);
        if (s == null) {
            return "";
        }
        if (System.currentTimeMillis() - s.observedAtMillis() > STALE_AFTER_MILLIS) {
            LAST.remove(npcId);
            return "";
        }
        return s.description();
    }

    public static boolean hasPrey(String npcId) {
        return !describe(npcId).isEmpty();
    }

    public static double lastDistance(String npcId) {
        Snapshot s = LAST.get(npcId);
        if (s == null) {
            return 99;
        }
        return s.distance();
    }
}
