package com.orffyrus.pest;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-NPC last hostile snapshot. Only near threats (default ≤14 blocks) count
 * as active for brain "defend" priority so distant NoteNearbyThreat pings
 * do not block base-building forever.
 */
public final class ThreatMemory {

    private static final long STALE_AFTER_MILLIS = 8_000L;
    /** Max distance for brain to treat as real combat threat. */
    public static final double ACTIVE_RANGE = 14.0;

    private record Snapshot(String description, double distance, long observedAtMillis) { }

    private static final ConcurrentHashMap<String, Snapshot> LAST_SEEN = new ConcurrentHashMap<>();

    private ThreatMemory() { }

    public static void record(String npcId, double distance) {
        int rounded = (int) Math.round(distance);
        LAST_SEEN.put(npcId, new Snapshot(
                "A hostile creature is nearby, about " + rounded + " blocks away.",
                distance,
                System.currentTimeMillis()));
    }

    /** True only if a non-stale threat is within {@link #ACTIVE_RANGE}. */
    public static boolean isActiveNear(String npcId) {
        Snapshot s = LAST_SEEN.get(npcId);
        if (s == null) {
            return false;
        }
        if (System.currentTimeMillis() - s.observedAtMillis() > STALE_AFTER_MILLIS) {
            LAST_SEEN.remove(npcId);
            return false;
        }
        return s.distance() <= ACTIVE_RANGE;
    }

    public static double lastDistance(String npcId) {
        Snapshot s = LAST_SEEN.get(npcId);
        if (s == null) {
            return 999;
        }
        if (System.currentTimeMillis() - s.observedAtMillis() > STALE_AFTER_MILLIS) {
            return 999;
        }
        return s.distance();
    }

    /** Returns the last-noted threat description, or "" if none/stale/too far for defend. */
    public static String describe(String npcId) {
        if (!isActiveNear(npcId)) {
            return "";
        }
        Snapshot s = LAST_SEEN.get(npcId);
        return s == null ? "" : s.description();
    }

    public static void clear(String npcId) {
        LAST_SEEN.remove(npcId);
    }
}
