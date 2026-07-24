package com.orffyrus.pest;

import com.hypixel.hytale.logger.HytaleLogger;
import org.joml.Vector3i;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tracks player block places. When the owner places {@link #PLACE_THRESHOLD}+ blocks
 * inside a {@link #WINDOW_MS} rolling window, Pest becomes eligible to build his home
 * (~100 blocks from the player build cluster).
 *
 * <p>Eligibility is <b>latched</b> until a base is built (or session reset) so a slow
 * build / failed site search does not lose the trigger when the 300s window slides.
 */
public final class PlayerBuildTracker {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Need this many places in the window to trigger (user: 21 → threshold 20). */
    public static final int PLACE_THRESHOLD = 20;
    public static final long WINDOW_MS = 300_000L;
    public static final double PLAYER_BASE_RADIUS = 20.0;

    private record Place(long t, double x, double y, double z) { }

    private static final Deque<Place> PLACES = new ArrayDeque<>();
    /** Sticky: once true, stays true until base built or reset. */
    private static volatile boolean homeEligibleLatch;
    private static volatile double playerBaseX;
    private static volatile double playerBaseY;
    private static volatile double playerBaseZ;
    private static volatile boolean hasPlayerBase;
    private static volatile long lastAnnounceMs;
    private static volatile int totalPlacesEver;

    private PlayerBuildTracker() { }

    public static void reset() {
        PLACES.clear();
        homeEligibleLatch = false;
        hasPlayerBase = false;
        playerBaseX = playerBaseY = playerBaseZ = 0;
        totalPlacesEver = 0;
        lastAnnounceMs = 0L;
    }

    /** Clear latch after successful home build. */
    public static void onHomeBuilt() {
        homeEligibleLatch = false;
        // keep place history for player base centroid optional
    }

    public static void recordPlace(Vector3i block) {
        if (block == null) {
            return;
        }
        recordPlace(block.x + 0.5, block.y + 0.5, block.z + 0.5);
    }

    public static void recordPlace(double x, double y, double z) {
        long now = System.currentTimeMillis();
        PLACES.addLast(new Place(now, x, y, z));
        totalPlacesEver++;
        prune(now);
        recomputePlayerBase();

        int n = PLACES.size();
        LOGGER.atFine().log("Player place #" + totalPlacesEver + " window=" + n
                + " latch=" + homeEligibleLatch);

        if (!homeEligibleLatch && n >= PLACE_THRESHOLD) {
            homeEligibleLatch = true;
            LOGGER.atInfo().log("HOME TRIGGER: " + n + " places in "
                    + (WINDOW_MS / 1000) + "s → Pest home eligible @ playerBase "
                    + String.format("%.0f,%.0f,%.0f", playerBaseX, playerBaseY, playerBaseZ));
            announceOnce("[Pest] You placed " + n + " blocks — building my home ~100 away…");
            kickBuild();
        } else if (!homeEligibleLatch && n > 0 && n % 5 == 0) {
            // Progress feedback so player knows counting works
            announceOnce("[Pest] Build progress: " + n + "/" + PLACE_THRESHOLD
                    + " blocks (then I build home).");
        }
    }

    /** Manual latch for testing / command. */
    public static void forceEligible() {
        homeEligibleLatch = true;
        if (!hasPlayerBase && PestEntityTracker.hasPosition()) {
            playerBaseX = PestEntityTracker.x();
            playerBaseY = PestEntityTracker.y();
            playerBaseZ = PestEntityTracker.z();
            hasPlayerBase = true;
        }
        kickBuild();
    }

    private static void kickBuild() {
        if (PestBaseState.isBuilt() || PestBaseState.isBuilding()) {
            return;
        }
        try {
            PestBaseBuilder.scheduleBuildNearpest(false);
        } catch (Exception e) {
            LOGGER.atWarning().log("kickBuild: " + e);
        }
    }

    private static void announceOnce(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastAnnounceMs < 4_000L) {
            return;
        }
        lastAnnounceMs = now;
        try {
            for (var p : com.hypixel.hytale.server.core.universe.Universe.get().getPlayers()) {
                if (p != null) {
                    p.sendMessage(com.hypixel.hytale.server.core.Message.raw(msg));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void prune(long now) {
        while (!PLACES.isEmpty() && now - PLACES.peekFirst().t > WINDOW_MS) {
            PLACES.removeFirst();
        }
        // Do NOT clear homeEligibleLatch when window empties — latch sticks until home built
    }

    private static void recomputePlayerBase() {
        if (PLACES.isEmpty()) {
            return;
        }
        double sx = 0, sy = 0, sz = 0;
        int n = 0;
        for (Place p : PLACES) {
            sx += p.x;
            sy += p.y;
            sz += p.z;
            n++;
        }
        playerBaseX = sx / n;
        playerBaseY = sy / n;
        playerBaseZ = sz / n;
        hasPlayerBase = true;
    }

    public static boolean hasPlayerBase() {
        return hasPlayerBase;
    }

    public static double playerBaseX() {
        return playerBaseX;
    }

    public static double playerBaseY() {
        return playerBaseY;
    }

    public static double playerBaseZ() {
        return playerBaseZ;
    }

    public static int placeCountInWindow() {
        prune(System.currentTimeMillis());
        return PLACES.size();
    }

    public static int totalPlacesEver() {
        return totalPlacesEver;
    }

    /** Latched eligibility (survives window expiry until home built). */
    public static boolean isHomeBuildTriggered() {
        if (PestBaseState.isBuilt()) {
            return false;
        }
        return homeEligibleLatch;
    }

    public static String describe() {
        return "places=" + placeCountInWindow() + "/" + PLACE_THRESHOLD
                + " total=" + totalPlacesEver
                + " latch=" + homeEligibleLatch
                + (hasPlayerBase
                ? String.format(" playerBase=%.0f,%.0f,%.0f", playerBaseX, playerBaseY, playerBaseZ)
                : " playerBase=?");
    }
}
