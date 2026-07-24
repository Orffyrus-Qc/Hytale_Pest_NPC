package com.orffyrus.pest;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;

/**
 * Builds Pest's home: larger closed room, pathable doorway, bed inside.
 * Siting: ~100 blocks from player base, empty footprint, floor one block above ground.
 *
 * Doorway is <b>1 wide × 2 tall</b>; {@link #BLOCK_DOOR} is placed in the
 * actual doorway opening (bottom cell), headroom above stays clear.
 */
public final class PestBaseBuilder {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final String BLOCK_WOOD = "Wood_Softwood_Planks";
    public static final String BLOCK_WOOD_ALT = "Wood_Lightwood_Planks";
    public static final String BLOCK_DOOR = "Furniture_Crude_Door";
    public static final String BLOCK_BED = "Furniture_Crude_Bed";

    public static final int SIZE = 9;
    public static final int WALL_HEIGHT = 3;
    /** Pathable opening height (2 blocks tall). */
    public static final int DOORWAY_CLEAR_HEIGHT = 2;
    /** Pathable doorway width (1 block). */
    public static final int DOORWAY_WIDTH = 1;
    /** Pest must stand this many blocks from the build site before placing home. */
    public static final int PRE_BUILD_STAND_DISTANCE = 6;

    private PestBaseBuilder() { }

    public static void scheduleBuildNearpest() {
        scheduleBuildNearpest(false);
    }

    public static void scheduleBuildNearpest(boolean force) {
        if (!force && PestBaseState.isBuilt() && PestBaseState.isBedPlaced()) {
            return;
        }
        if (PestBaseState.isBuilding()) {
            return;
        }
        if (!force && !PestBehaviorRules.canAutoBuildHome()) {
            LOGGER.atFine().log("scheduleBuild skipped: not eligible places="
                    + PlayerBuildTracker.placeCountInWindow()
                    + " " + PlayerBuildTracker.describe());
            return;
        }
        if (!force && !PestBaseState.canStartBuild() && PestBaseState.isBuilt()) {
            return;
        }

        UUID ownerId = CompanionState.getOwner(PestSpawner.PEST_ROLE);
        PlayerRef pref = ownerId != null ? Universe.get().getPlayer(ownerId) : null;
        if (pref == null) {
            for (PlayerRef p : Universe.get().getPlayers()) {
                if (p != null) {
                    pref = p;
                    break;
                }
            }
        }

        double ax;
        double ay;
        double az;
        if (PestEntityTracker.hasPosition()) {
            ax = PestEntityTracker.x();
            ay = PestEntityTracker.y();
            az = PestEntityTracker.z();
        } else if (pref != null && pref.getTransform() != null
                && pref.getTransform().getPosition() != null) {
            var p = pref.getTransform().getPosition();
            ax = p.x;
            ay = p.y;
            az = p.z;
        } else if (PlayerBuildTracker.hasPlayerBase()) {
            ax = PlayerBuildTracker.playerBaseX();
            ay = PlayerBuildTracker.playerBaseY();
            az = PlayerBuildTracker.playerBaseZ();
        } else {
            announce("[Pest] Cannot build — no position. /pest spawn force first.");
            return;
        }

        World world = null;
        if (pref != null) {
            world = Universe.get().getWorld(pref.getWorldUuid());
        }
        if (world == null) {
            world = Universe.get().getDefaultWorld();
        }
        if (world == null) {
            announce("[Pest] Cannot build — no world.");
            return;
        }

        // Prefer ~100 from player base; also prepare near-player fallback
        int[] site100 = PestBehaviorRules.pickHomeSiteNearPlayerBase(ax, ay, az);
        final int preferOx = site100[0];
        final int preferHintY = site100[1];
        final int preferOz = site100[2];
        final int nearOx = (int) Math.floor(ax) + 8;
        final int nearOz = (int) Math.floor(az) + 8;
        final int nearHintY = (int) Math.floor(ay);
        final boolean forced = force;

        PestBaseState.markBuildingStarted();
        announce("[Pest] Going to the build site (stand " + PRE_BUILD_STAND_DISTANCE
                + " blocks away), then building…");
        final World w = world;
        w.execute(() -> {
            try {
                // Try far site first, then near player — never give up without placing
                int[] found = findEmptyFootprint(w, preferOx, preferHintY, preferOz, 24);
                if (found == null || found[1] < 1) {
                    found = findEmptyFootprint(w, nearOx, nearHintY, nearOz, 20);
                }
                if (found == null) {
                    int gy = findGroundY(w, nearOx + SIZE / 2, nearHintY, nearOz + SIZE / 2);
                    found = new int[]{nearOx, Math.max(1, gy), nearOz};
                }
                int ox = found[0];
                int floorY = found[1] + 1; // one block higher than ground
                int oz = found[2];

                // Stand 6 blocks south of doorway (outside the footprint) before building
                if (!movepestNearBuildSite(w, ox, floorY, oz)) {
                    LOGGER.atWarning().log("Could not move Pest near site; building anyway");
                    announce("[Pest] Could not walk over — placing home from here.");
                } else {
                    announce("[Pest] At the site (" + PRE_BUILD_STAND_DISTANCE
                            + " blocks out). Building home…");
                }

                BuildResult result = buildRoom(w, ox, floorY, oz, true);
                if (result.ok) {
                    PestBaseState.markBuilt(ox, floorY, oz);
                    PestBaseState.markBuildingFinished(true);
                    PlayerBuildTracker.onHomeBuilt();
                    if (result.bedPlaced) {
                        PestBaseState.markBedPlaced(result.bedX, result.bedY, result.bedZ);
                        PestBedSpawn.scheduleSetSpawnAtBed(
                                result.bedX + 0.5, result.bedY + 0.1, result.bedZ + 0.5);
                    }
                    PestInvuln.clearAfterBase();
                    announce("[Pest] Home built " + SIZE + "x" + SIZE
                            + " @ " + ox + "," + floorY + "," + oz
                            + (result.door
                            ? " with door @ " + result.doorX + "," + result.doorY + "," + result.doorZ
                            : " (DOOR MISSING — " + result.error + ")")
                            + ". Stay until you go 200 away.");
                    LOGGER.atInfo().log("Home ok ox=" + ox + " floorY=" + floorY + " oz=" + oz
                            + " door=" + result.door + " forced=" + forced);
                } else {
                    PestBaseState.markBuildingFinished(false);
                    announce("[Pest] Home build failed: " + result.error
                            + " — try /pest base force");
                }
            } catch (Exception e) {
                PestBaseState.markBuildingFinished(false);
                LOGGER.atWarning().log("Base build exception: " + e);
                announce("[Pest] Home build error: " + e.getMessage());
            }
        });
    }

    /**
     * Move Pest to stand {@link #PRE_BUILD_STAND_DISTANCE} blocks outside the
     * south doorway of the planned home (must run on world thread).
     */
    private static boolean movepestNearBuildSite(World world, int ox, int floorY, int oz) {
        try {
            EntityStore es = world.getEntityStore();
            if (es == null) {
                return false;
            }
            // Doorway is on south wall center; stand further south (outside)
            int doorX = ox + SIZE / 2;
            int doorZ = oz;
            double standX = doorX + 0.5;
            double standZ = doorZ - PRE_BUILD_STAND_DISTANCE + 0.5;
            double standY = floorY + 1.0; // feet on/above floor height outside
            // Prefer ground under stand point
            int gy = findGroundY(world, doorX, floorY, (int) Math.floor(standZ));
            if (gy > 0) {
                standY = gy + 1.0;
            }
            return PestEntityTracker.moveTo(world, es.getStore(), standX, standY, standZ);
        } catch (Exception e) {
            LOGGER.atWarning().log("movepestNearBuildSite: " + e);
            return false;
        }
    }

    /**
     * Search near preferred SW corner for a usable footprint.
     * Always returns a candidate when ground can be resolved (never blocks forever).
     */
    static int[] findEmptyFootprint(World world, int preferOx, int hintY, int preferOz, int radius) {
        int bestScore = Integer.MIN_VALUE;
        int[] best = null;
        for (int dx = -radius; dx <= radius; dx += 3) {
            for (int dz = -radius; dz <= radius; dz += 3) {
                int ox = preferOx + dx;
                int oz = preferOz + dz;
                int gy = findGroundY(world, ox + SIZE / 2, hintY, oz + SIZE / 2);
                if (gy < 1 || gy > 310) {
                    continue;
                }
                int score = scoreFootprint(world, ox, gy, oz);
                if (score > bestScore) {
                    bestScore = score;
                    best = new int[]{ox, gy, oz};
                }
                if (score >= SIZE * SIZE) {
                    return best;
                }
            }
        }
        if (best != null) {
            return best;
        }
        // Absolute fallback
        int gy = findGroundY(world, preferOx + SIZE / 2, hintY, preferOz + SIZE / 2);
        return new int[]{preferOx, Math.max(1, gy), preferOz};
    }

    /**
     * Higher = emptier air column above ground for the footprint.
     */
    static int scoreFootprint(World world, int ox, int groundY, int oz) {
        int score = 0;
        // Floor will be groundY+1 — need air from groundY+1 through groundY+WALL_HEIGHT+1
        int floorY = groundY + 1;
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                int bx = ox + x;
                int bz = oz + z;
                // Support block under floor
                try {
                    BlockType under = world.getBlockType(bx, groundY, bz);
                    if (under == null || isEmptyBlock(under)) {
                        score -= 3;
                    } else {
                        score += 1;
                    }
                } catch (Exception e) {
                    score -= 2;
                }
                for (int h = 0; h <= WALL_HEIGHT + 1; h++) {
                    try {
                        BlockType bt = world.getBlockType(bx, floorY + h, bz);
                        if (bt == null || isEmptyBlock(bt)) {
                            score += 2;
                        } else {
                            score -= 4; // occupied
                        }
                    } catch (Exception e) {
                        score -= 1;
                    }
                }
            }
        }
        return score;
    }

    static int findGroundY(World world, int x, int hintY, int z) {
        try {
            long idx = ChunkUtil.indexChunkFromBlock(x, z);
            WorldChunk chunk = world.getChunkIfLoaded(idx);
            if (chunk != null) {
                int lx = x - ChunkUtil.minBlock(ChunkUtil.chunkCoordinate(x));
                int lz = z - ChunkUtil.minBlock(ChunkUtil.chunkCoordinate(z));
                if (lx < 0) {
                    lx = Math.floorMod(x, 32);
                }
                if (lz < 0) {
                    lz = Math.floorMod(z, 32);
                }
                try {
                    short h = chunk.getHeight(lx, lz);
                    if (h > 0 && h < 320) {
                        return h;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }

        int y = Math.max(1, hintY);
        for (int d = 0; d < 32; d++) {
            int ty = y - d;
            if (ty < 1) {
                break;
            }
            try {
                BlockType bt = world.getBlockType(x, ty, z);
                if (bt != null && !isEmptyBlock(bt)) {
                    return ty;
                }
            } catch (Exception ignored) {
            }
        }
        return hintY;
    }

    private static boolean isEmptyBlock(BlockType bt) {
        if (bt == null) {
            return true;
        }
        try {
            String id = bt.getId();
            if (id == null || id.isEmpty()) {
                return true;
            }
            String low = id.toLowerCase();
            return low.equals("empty") || low.contains("air") || low.equals("unknown");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @param oy floor level (already ground+1)
     */
    public static BuildResult buildRoom(World world, int ox, int oy, int oz, boolean placeBed) {
        BuildResult r = new BuildResult();
        if (world == null) {
            r.error = "null world";
            return r;
        }
        try {
            String wood = resolveWoodId();
            r.woodId = wood;
            int max = SIZE - 1;

            // South wall: 1-wide × 2-tall doorway centered — door in the opening
            int doorLocalX0 = SIZE / 2; // single cell center (e.g. 4 for size 9)
            int doorLocalX1 = doorLocalX0; // width 1
            int doorZ = oz;
            int doorX = ox + doorLocalX0;
            int doorY = oy + 1; // bottom of doorway (on floor level)
            r.doorX = doorX;
            r.doorY = doorY;
            r.doorZ = doorZ;

            int bedX = ox + SIZE / 2;
            int bedZ = oz + SIZE / 2 + 1;
            if (bedZ >= oz + max) {
                bedZ = oz + SIZE / 2;
            }
            int bedY = oy;

            // Clear volume + floor
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    int bx = ox + x;
                    int bz = oz + z;
                    if (safeSet(world, bx, oy - 1, bz, wood)) {
                        // foundation under floor
                    }
                    if (safeSet(world, bx, oy, bz, wood)) {
                        r.floor++;
                    }
                    for (int h = 1; h <= WALL_HEIGHT + 1; h++) {
                        clearBlock(world, bx, oy + h, bz);
                    }
                }
            }

            // Walls
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    boolean perimeter = x == 0 || x == max || z == 0 || z == max;
                    if (!perimeter) {
                        continue;
                    }
                    boolean inDoorway = (z == 0 && x >= doorLocalX0 && x <= doorLocalX1);
                    for (int h = 1; h <= WALL_HEIGHT; h++) {
                        int bx = ox + x;
                        int by = oy + h;
                        int bz = oz + z;
                        if (inDoorway && h <= DOORWAY_CLEAR_HEIGHT) {
                            // Pathable opening — leave air (no wall, no door in path)
                            clearBlock(world, bx, by, bz);
                            // Also clear floor edge to walk in? keep floor
                            continue;
                        }
                        if (safeSet(world, bx, by, bz, wood)) {
                            r.walls++;
                        }
                    }
                }
            }

            // Clear doorway + approach (door placed last so nothing overwrites it)
            for (int lx = doorLocalX0; lx <= doorLocalX1; lx++) {
                for (int h = 1; h <= DOORWAY_CLEAR_HEIGHT; h++) {
                    clearBlock(world, ox + lx, oy + h, doorZ);
                }
                clearBlock(world, ox + lx, oy + 1, doorZ - 1);
                clearBlock(world, ox + lx, oy + 2, doorZ - 1);
            }

            // Roof
            int roofY = oy + WALL_HEIGHT;
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    if (safeSet(world, ox + x, roofY, oz + z, wood)) {
                        r.roof++;
                    }
                }
            }

            // Interior clear (do NOT touch south wall z=0 / doorway)
            for (int x = 1; x < max; x++) {
                for (int z = 1; z < max; z++) {
                    for (int h = 1; h < WALL_HEIGHT; h++) {
                        clearBlock(world, ox + x, oy + h, oz + z);
                    }
                }
            }

            if (placeBed) {
                clearBlock(world, bedX, bedY + 1, bedZ);
                if (safeSet(world, bedX, bedY + 1, bedZ, BLOCK_BED)) {
                    r.bedPlaced = true;
                    r.bedX = bedX;
                    r.bedY = bedY + 1;
                    r.bedZ = bedZ;
                } else if (safeSet(world, bedX, bedY, bedZ, BLOCK_BED)) {
                    r.bedPlaced = true;
                    r.bedX = bedX;
                    r.bedY = bedY;
                    r.bedZ = bedZ;
                } else {
                    r.error = (r.error == null ? "" : r.error + "; ") + "bed place failed";
                }
            }

            // LAST: put door in the doorway so later clears cannot wipe it
            // Ensure jambs (left/right wood) exist for connected-door rules
            if (doorLocalX0 > 0) {
                safeSet(world, ox + doorLocalX0 - 1, doorY, doorZ, wood);
                if (DOORWAY_CLEAR_HEIGHT >= 2) {
                    safeSet(world, ox + doorLocalX0 - 1, doorY + 1, doorZ, wood);
                }
            }
            if (doorLocalX0 < max) {
                safeSet(world, ox + doorLocalX0 + 1, doorY, doorZ, wood);
                if (DOORWAY_CLEAR_HEIGHT >= 2) {
                    safeSet(world, ox + doorLocalX0 + 1, doorY + 1, doorZ, wood);
                }
            }
            // Lintel above doorway
            safeSet(world, doorX, oy + DOORWAY_CLEAR_HEIGHT + 1, doorZ, wood);

            r.door = placeDoorInOpening(world, doorX, doorY, doorZ);
            if (r.door) {
                // Keep headroom cell empty if door is only 1 block tall
                if (DOORWAY_CLEAR_HEIGHT >= 2) {
                    // only clear above door if that cell is not the door itself
                    try {
                        BlockType above = world.getBlockType(doorX, doorY + 1, doorZ);
                        if (above != null && !isDoorBlock(above)) {
                            // leave air headroom only if empty or non-door
                            if (isEmptyBlock(above)) {
                                // already air
                            } else if (!isDoorBlock(above)) {
                                clearBlock(world, doorX, doorY + 1, doorZ);
                            }
                        }
                    } catch (Exception ignored) {
                        clearBlock(world, doorX, doorY + 1, doorZ);
                    }
                }
                LOGGER.atInfo().log("Door confirmed in doorway @ "
                        + doorX + "," + doorY + "," + doorZ);
            } else {
                r.error = (r.error == null ? "" : r.error + "; ")
                        + "DOOR FAILED at " + doorX + "," + doorY + "," + doorZ;
                LOGGER.atWarning().log("Door placement failed at "
                        + doorX + "," + doorY + "," + doorZ);
            }

            r.ok = r.walls >= 20 && r.roof >= (SIZE * SIZE / 2) && r.floor >= (SIZE * SIZE / 2);
            if (!r.ok && r.error == null) {
                r.error = "walls=" + r.walls + " roof=" + r.roof + " floor=" + r.floor;
            }
            return r;
        } catch (Exception e) {
            r.error = e.toString();
            return r;
        }
    }

    private static boolean isDoorBlock(BlockType bt) {
        if (bt == null) {
            return false;
        }
        try {
            String id = bt.getId();
            return id != null && id.toLowerCase().contains("door");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Place door in the doorway opening. Verifies the block actually stuck.
     */
    private static boolean placeDoorInOpening(World world, int x, int y, int z) {
        String[] doorIds = {
                BLOCK_DOOR,
                "Furniture_Crude_Door",
                "Furniture_Village_Door",
                "Furniture_Lumberjack_Door"
        };

        for (String doorId : doorIds) {
            // 1) plain setBlock (most reliable for known block ids)
            try {
                world.setBlock(x, y, z, doorId);
                if (verifyDoor(world, x, y, z)) {
                    LOGGER.atInfo().log("Door setBlock ok id=" + doorId + " @ " + x + "," + y + "," + z);
                    return true;
                }
            } catch (Exception e) {
                LOGGER.atFine().log("setBlock door " + doorId + ": " + e);
            }

            // 2) setBlock with rotation indices (NESW)
            for (int rot = 0; rot < 4; rot++) {
                try {
                    world.setBlock(x, y, z, doorId, rot);
                    if (verifyDoor(world, x, y, z)) {
                        LOGGER.atInfo().log("Door setBlock+rot ok id=" + doorId
                                + " rot=" + rot + " @ " + x + "," + y + "," + z);
                        return true;
                    }
                } catch (Exception e) {
                    LOGGER.atFine().log("setBlock door rot " + doorId + " " + rot + ": " + e);
                }
            }

            // 3) chunk.placeBlock with yaw
            try {
                WorldChunk chunk = world.getChunk(ChunkUtil.indexChunkFromBlock(x, z));
                if (chunk != null) {
                    for (Rotation yaw : new Rotation[]{
                            Rotation.None, Rotation.Ninety, Rotation.OneEighty, Rotation.TwoSeventy}) {
                        try {
                            if (chunk.placeBlock(x, y, z, doorId, yaw, Rotation.None, Rotation.None)
                                    && verifyDoor(world, x, y, z)) {
                                LOGGER.atInfo().log("Door placeBlock ok id=" + doorId
                                        + " yaw=" + yaw + " @ " + x + "," + y + "," + z);
                                return true;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.atFine().log("chunk place door " + doorId + ": " + e);
            }

            // 4) BlockType via chunk setBlock
            try {
                BlockType bt = BlockType.getAssetMap().getAsset(doorId);
                WorldChunk chunk = world.getChunk(ChunkUtil.indexChunkFromBlock(x, z));
                if (bt != null && chunk != null) {
                    chunk.setBlock(x, y, z, bt);
                    if (verifyDoor(world, x, y, z)) {
                        LOGGER.atInfo().log("Door BlockType ok id=" + doorId + " @ " + x + "," + y + "," + z);
                        return true;
                    }
                }
            } catch (Exception e) {
                LOGGER.atFine().log("BlockType door " + doorId + ": " + e);
            }
        }
        return false;
    }

    private static boolean verifyDoor(World world, int x, int y, int z) {
        try {
            BlockType bt = world.getBlockType(x, y, z);
            if (bt == null || isEmptyBlock(bt)) {
                return false;
            }
            String id = bt.getId();
            if (id == null) {
                return false;
            }
            String low = id.toLowerCase();
            return low.contains("door");
        } catch (Exception e) {
            return false;
        }
    }

    private static String resolveWoodId() {
        for (String id : new String[]{BLOCK_WOOD, BLOCK_WOOD_ALT, "Wood_Hardwood_Planks"}) {
            try {
                var map = BlockType.getAssetMap();
                if (map != null && map.getIndex(id) >= 0) {
                    return id;
                }
            } catch (Exception ignored) {
            }
        }
        return BLOCK_WOOD;
    }

    private static void clearBlock(World world, int x, int y, int z) {
        try {
            world.setBlock(x, y, z, BlockType.EMPTY_KEY);
        } catch (Exception e1) {
            try {
                world.setBlock(x, y, z, "Empty");
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean safeSet(World world, int x, int y, int z, String blockId) {
        try {
            world.setBlock(x, y, z, blockId);
            return true;
        } catch (Exception e) {
            LOGGER.atFine().log("setBlock fail " + blockId + " @ " + x + "," + y + "," + z + ": " + e);
            return false;
        }
    }

    private static void announce(String text) {
        try {
            for (PlayerRef p : Universe.get().getPlayers()) {
                if (p != null) {
                    p.sendMessage(Message.raw(text));
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static final class BuildResult {
        public boolean ok;
        public int walls;
        public int roof;
        public int floor;
        public boolean door;
        public int doorX;
        public int doorY;
        public int doorZ;
        public boolean bedPlaced;
        public int bedX;
        public int bedY;
        public int bedZ;
        public String woodId;
        public String error;
    }
}
