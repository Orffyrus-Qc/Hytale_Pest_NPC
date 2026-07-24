package com.orffyrus.pest;

/**
 * Sticky "follow me" lock from chat / brain. Overrides stay-home until cleared
 * or duration expires. Continuous follow is driven by role AI Seek + periodic
 * ManualMove catch-up when the owner is far.
 */
public final class PestFollowState {

    /** Default chat follow lasts 15 minutes (re-say "follow me" to refresh). */
    private static final long DEFAULT_MS = 15 * 60 * 1000L;

    private static volatile long untilMillis = 0L;
    private static volatile String reason = "";

    private PestFollowState() { }

    public static void enable(String why) {
        enable(why, DEFAULT_MS);
    }

    public static void enable(String why, long durationMs) {
        untilMillis = System.currentTimeMillis() + Math.max(5_000L, durationMs);
        reason = why == null ? "follow" : why;
    }

    public static void clear() {
        untilMillis = 0L;
        reason = "";
    }

    public static boolean isActive() {
        if (untilMillis <= 0L) {
            return false;
        }
        if (System.currentTimeMillis() > untilMillis) {
            untilMillis = 0L;
            return false;
        }
        return true;
    }

    public static String reason() {
        return reason;
    }
}
