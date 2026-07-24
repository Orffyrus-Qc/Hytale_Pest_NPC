package com.orffyrus.pest;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Timing / distance rules for Pest:
 * <ul>
 *   <li>Home auto-build when player places &gt;20 blocks within 300s
 *       (see {@link PlayerBuildTracker}); site is ~100 blocks from player base</li>
 *   <li>Hunt when owner stands still for a random 30–300s threshold</li>
 *   <li>Stay at Pest base until player is ≥200 blocks from that base, then follow</li>
 *   <li>Invulnerable until first base is placed</li>
 * </ul>
 */
public final class PestBehaviorRules {

    /** Distance from player-build cluster to place Pest's home. */
    public static final double HOME_OFFSET_FROM_PLAYER_BASE = 100.0;

    /** If player is this far from Pest base, leave home and follow. */
    public static final double LEAVE_BASE_FOLLOW_DISTANCE = 200.0;

    public static final int IDLE_HUNT_MIN_S = 30;
    public static final int IDLE_HUNT_MAX_S = 300;

    private static final double MOVE_EPS = 0.35;

    private static volatile long sessionStartMs = System.currentTimeMillis();
    private static volatile double lastPx = Double.NaN;
    private static volatile double lastPy = Double.NaN;
    private static volatile double lastPz = Double.NaN;
    private static volatile long lastMoveMs = System.currentTimeMillis();
    private static volatile long idleThresholdMs = randomIdleThresholdMs();
    private static volatile boolean idleHuntTriggered;

    private PestBehaviorRules() { }

    public static void onSessionStart() {
        sessionStartMs = System.currentTimeMillis();
        lastPx = lastPy = lastPz = Double.NaN;
        lastMoveMs = sessionStartMs;
        idleThresholdMs = randomIdleThresholdMs();
        idleHuntTriggered = false;
        PlayerBuildTracker.reset();
    }

    private static long randomIdleThresholdMs() {
        int s = ThreadLocalRandom.current().nextInt(IDLE_HUNT_MIN_S, IDLE_HUNT_MAX_S + 1);
        return s * 1000L;
    }

    public static void updateOwnerMotion(double px, double py, double pz) {
        long now = System.currentTimeMillis();
        if (Double.isNaN(lastPx)) {
            lastPx = px;
            lastPy = py;
            lastPz = pz;
            lastMoveMs = now;
            idleThresholdMs = randomIdleThresholdMs();
            idleHuntTriggered = false;
            return;
        }
        double dx = px - lastPx;
        double dy = py - lastPy;
        double dz = pz - lastPz;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist >= MOVE_EPS) {
            lastPx = px;
            lastPy = py;
            lastPz = pz;
            lastMoveMs = now;
            idleThresholdMs = randomIdleThresholdMs();
            idleHuntTriggered = false;
            return;
        }
        long idleFor = now - lastMoveMs;
        if (!idleHuntTriggered && idleFor >= idleThresholdMs) {
            idleHuntTriggered = true;
            // Only hunt if not required to stay home or follow
            if (!shouldStayAtBase(px, py, pz) && !shouldFollowFromBase(px, py, pz)) {
                PestHuntState.enable("owner idle " + (idleThresholdMs / 1000) + "s → hunt", 45_000L);
                PestLootState.enableAfterCombat();
            }
        }
    }

    /** Auto-home allowed when player build trigger fired (or base already mid-build). */
    public static boolean canAutoBuildHome() {
        if (PestBaseState.isBuilt()) {
            return false;
        }
        return PlayerBuildTracker.isHomeBuildTriggered();
    }

    /**
     * True while Pest has a base and the owner is still within
     * {@link #LEAVE_BASE_FOLLOW_DISTANCE} of that base → stay home.
     */
    public static boolean shouldStayAtBase(double ownerX, double ownerY, double ownerZ) {
        if (!PestBaseState.isBuilt()) {
            return false;
        }
        double d = distanceToPestBase(ownerX, ownerY, ownerZ);
        return d < LEAVE_BASE_FOLLOW_DISTANCE;
    }

    /**
     * True when owner is ≥200 blocks from Pest base → leave and follow.
     */
    public static boolean shouldFollowFromBase(double ownerX, double ownerY, double ownerZ) {
        if (!PestBaseState.isBuilt()) {
            // Pre-base: follow if far from Pest body (legacy)
            return false;
        }
        return distanceToPestBase(ownerX, ownerY, ownerZ) >= LEAVE_BASE_FOLLOW_DISTANCE;
    }

    public static double distanceToPestBase(double ownerX, double ownerY, double ownerZ) {
        if (!PestBaseState.isBuilt()) {
            return 0;
        }
        double cx = PestBaseState.originX() + PestBaseBuilder.SIZE / 2.0;
        double cy = PestBaseState.originY();
        double cz = PestBaseState.originZ() + PestBaseBuilder.SIZE / 2.0;
        return distance(ownerX, ownerY, ownerZ, cx, cy, cz);
    }

    public static boolean isInvulnerablePhase() {
        // Invulnerable until a base (with bed) exists
        return !PestBaseState.isBuilt() || !PestBaseState.isBedPlaced();
    }

    public static long idleMs() {
        return System.currentTimeMillis() - lastMoveMs;
    }

    public static long idleThresholdMs() {
        return idleThresholdMs;
    }

    public static boolean isIdleHuntActive() {
        return idleHuntTriggered || PestHuntState.isActive();
    }

    public static long sessionAgeMs() {
        return System.currentTimeMillis() - sessionStartMs;
    }

    public static double distance(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Pick a home origin ~100 blocks from player base, horizontal, random heading.
     * Returns int[3] = {ox, hintY, oz} outer SW corner of footprint.
     */
    public static int[] pickHomeSiteNearPlayerBase(double fallbackX, double fallbackY, double fallbackZ) {
        double cx;
        double cy;
        double cz;
        if (PlayerBuildTracker.hasPlayerBase()) {
            cx = PlayerBuildTracker.playerBaseX();
            cy = PlayerBuildTracker.playerBaseY();
            cz = PlayerBuildTracker.playerBaseZ();
        } else {
            cx = fallbackX;
            cy = fallbackY;
            cz = fallbackZ;
        }
        double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2.0;
        double dist = HOME_OFFSET_FROM_PLAYER_BASE;
        double hx = cx + Math.cos(angle) * dist;
        double hz = cz + Math.sin(angle) * dist;
        // Outer SW corner so center is ~100 from player base
        int ox = (int) Math.floor(hx) - PestBaseBuilder.SIZE / 2;
        int oz = (int) Math.floor(hz) - PestBaseBuilder.SIZE / 2;
        int hintY = (int) Math.floor(cy);
        return new int[]{ox, hintY, oz};
    }

    public static String describe() {
        return PlayerBuildTracker.describe()
                + " invuln=" + isInvulnerablePhase()
                + " idle=" + (idleMs() / 1000) + "/" + (idleThresholdMs / 1000) + "s"
                + " built=" + PestBaseState.isBuilt();
    }
}
