package com.orffyrus.pest;

/**
 * Tracks whether Pest has built a basic shelter this session, plus build cooldowns
 * so the brain cannot spam rebuilds every state tick.
 */
public final class PestBaseState {

    private static final long BUILD_COOLDOWN_MS = 45_000L;
    private static final long BUILDING_LOCK_MS = 8_000L;

    private static volatile boolean built;
    private static volatile boolean bedPlaced;
    private static volatile boolean building;
    private static volatile long lastBuildAttemptMs;
    private static volatile int originX;
    private static volatile int originY;
    private static volatile int originZ;
    private static volatile int bedX;
    private static volatile int bedY;
    private static volatile int bedZ;

    private PestBaseState() { }

    public static boolean isBuilt() {
        return built;
    }

    public static boolean isBedPlaced() {
        return bedPlaced;
    }

    public static boolean isBuilding() {
        if (!building) {
            return false;
        }
        // Auto-clear stuck lock
        if (System.currentTimeMillis() - lastBuildAttemptMs > BUILDING_LOCK_MS) {
            building = false;
            return false;
        }
        return true;
    }

    public static boolean needsBase() {
        return !built;
    }

    public static boolean canStartBuild() {
        if (building) {
            return false;
        }
        if (built && bedPlaced) {
            return false;
        }
        long now = System.currentTimeMillis();
        return now - lastBuildAttemptMs >= BUILD_COOLDOWN_MS || lastBuildAttemptMs == 0L;
    }

    public static void markBuildingStarted() {
        building = true;
        lastBuildAttemptMs = System.currentTimeMillis();
    }

    public static void markBuildingFinished(boolean success) {
        building = false;
        lastBuildAttemptMs = System.currentTimeMillis();
        if (!success) {
            // Allow retry sooner on failure
            lastBuildAttemptMs = System.currentTimeMillis() - (BUILD_COOLDOWN_MS - 8_000L);
        }
    }

    public static void markBuilt(int ox, int oy, int oz) {
        built = true;
        originX = ox;
        originY = oy;
        originZ = oz;
        building = false;
    }

    public static void markBedPlaced() {
        bedPlaced = true;
    }

    public static void markBedPlaced(int x, int y, int z) {
        bedPlaced = true;
        bedX = x;
        bedY = y;
        bedZ = z;
    }

    public static int originX() {
        return originX;
    }

    public static int originY() {
        return originY;
    }

    public static int originZ() {
        return originZ;
    }

    public static int bedX() {
        return bedX;
    }

    public static int bedY() {
        return bedY;
    }

    public static int bedZ() {
        return bedZ;
    }

    public static void reset() {
        built = false;
        bedPlaced = false;
        building = false;
        lastBuildAttemptMs = 0L;
        originX = originY = originZ = 0;
        bedX = bedY = bedZ = 0;
    }

    public static String describe() {
        if (building) {
            return "building…";
        }
        if (!built) {
            return "no base yet";
        }
        String s = "base@" + originX + "," + originY + "," + originZ;
        if (bedPlaced) {
            s += " bed@" + bedX + "," + bedY + "," + bedZ;
        } else {
            s += " bed=pending";
        }
        return s;
    }
}
