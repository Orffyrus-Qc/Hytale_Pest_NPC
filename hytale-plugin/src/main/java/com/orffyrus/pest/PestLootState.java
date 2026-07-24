package com.orffyrus.pest;

/**
 * After a fight/hunt kill window, prioritize looting ground drops.
 */
public final class PestLootState {

    private static volatile long untilMillis = 0L;
    private static volatile String reason = "";

    private PestLootState() { }

    public static void enable(String why, long durationMs) {
        untilMillis = System.currentTimeMillis() + Math.max(3_000L, durationMs);
        reason = why == null ? "loot" : why;
    }

    /** Default 10s loot window after combat. */
    public static void enableAfterCombat() {
        enable("after_combat", 10_000L);
    }

    public static boolean isActive() {
        return untilMillis > 0L && System.currentTimeMillis() <= untilMillis;
    }

    public static String reason() {
        return reason;
    }

    public static void clear() {
        untilMillis = 0L;
        reason = "";
    }
}
