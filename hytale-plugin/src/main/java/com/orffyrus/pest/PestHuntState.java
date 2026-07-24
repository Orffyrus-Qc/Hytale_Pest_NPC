package com.orffyrus.pest;

/**
 * When true, Pest may lock and attack wildlife/NPCs for resource drops
 * (e.g. light hide), not only self-defense hostiles.
 */
public final class PestHuntState {

    private static final long DEFAULT_MS = 20_000L;
    private static volatile long untilMillis = 0L;
    private static volatile String reason = "";

    private PestHuntState() { }

    public static void enable(String why) {
        enable(why, DEFAULT_MS);
    }

    public static void enable(String why, long durationMs) {
        untilMillis = System.currentTimeMillis() + Math.max(3_000L, durationMs);
        reason = why == null ? "hunt" : why;
    }

    public static void clear() {
        untilMillis = 0L;
        reason = "";
    }

    public static boolean isActive() {
        if (System.currentTimeMillis() > untilMillis) {
            return false;
        }
        return untilMillis > 0L;
    }

    public static String reason() {
        return reason;
    }
}
