package com.orffyrus.pest;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import org.joml.Vector3d;

import java.util.UUID;

/**
 * Periodically builds a structured GameState JSON for the Docker brain and
 * applies returned actions (manual move / combat intent flags / base build).
 */
public final class BrainStateTicker {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final long TICK_MS = 500L;

    private final BrainBridge bridge;
    private int tick;
    private long lastBuildActionMs;
    private long lastHomeRetryMs;
    private String lastActionName = "";

    public BrainStateTicker(BrainBridge bridge) {
        this.bridge = bridge;
    }

    public void start() {
        bridge.setActionHandler(this::onAction);
        new java.util.Timer(true).scheduleAtFixedRate(new java.util.TimerTask() {
            public void run() {
                try {
                    tickOnce();
                } catch (Exception e) {
                    LOGGER.atFine().log("BrainStateTicker: " + e);
                }
            }
        }, TICK_MS, TICK_MS);
    }

    private void tickOnce() {
        if (bridge == null || !bridge.isConnected()) {
            return;
        }

        // Keep inventory/position fresh for policy (planks/door detection)
        PestEntityTracker.refreshIfPossible();

        PlayerRef owner = null;
        for (PlayerRef p : Universe.get().getPlayers()) {
            if (p == null) {
                continue;
            }
            UUID id = p.getUuid();
            if (CompanionState.isCompanion(PestSpawner.PEST_ROLE)
                    && id.equals(CompanionState.getOwner(PestSpawner.PEST_ROLE))) {
                owner = p;
                break;
            }
            if (owner == null) {
                owner = p;
            }
        }
        if (owner == null) {
            return;
        }

        Transform t = owner.getTransform();
        Vector3d pos = t != null ? t.getPosition() : null;
        double px = pos != null ? pos.x : 0;
        double py = pos != null ? pos.y : 64;
        double pz = pos != null ? pos.z : 0;

        double nx = PestEntityTracker.hasPosition() ? PestEntityTracker.x() : (px + 1.5);
        double ny = PestEntityTracker.hasPosition() ? PestEntityTracker.y() : py;
        double nz = PestEntityTracker.hasPosition() ? PestEntityTracker.z() : (pz + 0.5);

        // Behavior: idle hunt, player-build home, stay/leave base
        PestBehaviorRules.updateOwnerMotion(px, py, pz);
        double distToPest = PestBehaviorRules.distance(px, py, pz, nx, ny, nz);
        double distToBase = PestBehaviorRules.distanceToPestBase(px, py, pz);
        boolean stayHome = PestBehaviorRules.shouldStayAtBase(px, py, pz);
        boolean leaveFollow = PestBehaviorRules.shouldFollowFromBase(px, py, pz);
        boolean canHome = PestBehaviorRules.canAutoBuildHome();
        boolean idleHunt = PestBehaviorRules.isIdleHuntActive() && !stayHome;
        boolean invuln = PestBehaviorRules.isInvulnerablePhase();

        // Respawn at bed if missing entity while base exists
        if (PestBaseState.isBuilt() && PestEntityTracker.entityId() == null) {
            PestHomeAnchor.maybeRespawnAtBed(owner);
        }

        // Retry home build if latched but not built (place events / site search may fail once)
        if (canHome && !PestBaseState.isBuilt() && !PestBaseState.isBuilding()) {
            long now = System.currentTimeMillis();
            if (now - lastHomeRetryMs > 8_000L) {
                lastHomeRetryMs = now;
                PestBaseBuilder.scheduleBuildNearpest(false);
            }
        }

        boolean hasThreat = ThreatMemory.isActiveNear(PestSpawner.PEST_ROLE) && !stayHome;
        double threatDist = ThreatMemory.lastDistance(PestSpawner.PEST_ROLE);
        String threatsJson = hasThreat
                ? "[{\"id\":\"threat1\",\"kind\":\"hostile\",\"name\":\"Hostile\","
                + "\"pos\":{\"x\":" + (nx + Math.min(threatDist, 10)) + ",\"y\":" + ny + ",\"z\":" + nz + "},"
                + "\"hostile\":true,\"hp\":0.6,\"dist\":" + threatDist + "}]"
                : "[]";

        String preyDesc = PreyMemory.describe(PestSpawner.PEST_ROLE);
        boolean hasPrey = preyDesc != null && !preyDesc.isEmpty();
        double preyDist = PreyMemory.lastDistance(PestSpawner.PEST_ROLE);
        String nearbyJson = hasPrey
                ? "[{\"id\":\"prey1\",\"kind\":\"wildlife\",\"name\":\"Prey\","
                + "\"pos\":{\"x\":" + (nx + Math.min(preyDist, 12)) + ",\"y\":" + ny + ",\"z\":" + nz + "},"
                + "\"hostile\":false,\"hp\":0.7,\"dist\":" + preyDist + "}]"
                : "[]";

        if (hasThreat && "[]".equals(nearbyJson)) {
            nearbyJson = threatsJson;
        }

        String inv = PestEntityTracker.inventorySummary();
        String invJson = "[]";
        if (inv != null && !inv.isBlank()) {
            String[] parts = inv.split("[, ]+");
            StringBuilder ib = new StringBuilder("[");
            int n = 0;
            for (String p : parts) {
                if (p.isBlank() || p.startsWith("hand=")) {
                    continue;
                }
                if (n > 0) {
                    ib.append(',');
                }
                ib.append('"').append(BrainBridge.esc(p)).append('"');
                n++;
                if (n >= 24) {
                    break;
                }
            }
            ib.append(']');
            invJson = ib.toString();
        }

        boolean hasWood = inv != null && (inv.contains("Plank") || inv.contains("Wood_Softwood")
                || inv.contains(PestGear.ITEM_WOOD));
        boolean hasDoor = inv != null && inv.contains("Door");
        boolean hasBed = inv != null && inv.contains("Furniture_Crude_Bed");
        boolean hasHide = inv != null && (inv.contains("Ingredient_Hide") || inv.contains("Hide_Light"));
        boolean hasFibre = inv != null && (inv.contains("Ingredient_Fibre") || inv.contains("Fibre"));

        // Goals: defend > leave-base follow > stay_home > loot > hunt > build (player trigger) > gather
        String goal = "companion";
        if (hasThreat) {
            goal = "defend";
        } else if (leaveFollow) {
            goal = "follow";
        } else if (stayHome) {
            goal = "stay_home";
        } else if (PestLootState.isActive()) {
            goal = "loot";
        } else if (idleHunt || PestHuntState.isActive()) {
            goal = "hunt";
        } else if (PestBaseState.isBuilding()) {
            goal = "build_base";
        } else if (canHome && PestBaseState.needsBase()) {
            goal = "build_base";
        } else if (PestBaseState.isBuilt() && !PestBaseState.isBedPlaced() && hasBed) {
            goal = "place_bed";
        } else if (!hasHide) {
            goal = "gather_hide";
        } else if (!hasFibre) {
            goal = "gather_fibre";
        }

        // When stay_home, pull Pest toward base bed if she wandered
        if (stayHome && PestBaseState.isBuilt()) {
            double bx = PestBaseState.originX() + PestBaseBuilder.SIZE / 2.0;
            double bz = PestBaseState.originZ() + PestBaseBuilder.SIZE / 2.0;
            double dHome = Math.sqrt((nx - bx) * (nx - bx) + (nz - bz) * (nz - bz));
            if (dHome > 12) {
                ManualMoveState.request(PestSpawner.PEST_ROLE, ManualMoveState.Kind.FORWARD);
            }
        }

        double npcHp = invuln ? 1.0 : 1.0;
        tick++;
        String state = "{"
                + "\"world\":\"" + BrainBridge.esc(String.valueOf(owner.getWorldUuid())) + "\","
                + "\"tick\":" + tick + ","
                + "\"player\":{"
                + "\"id\":\"" + owner.getUuid() + "\","
                + "\"name\":\"" + BrainBridge.esc(owner.getUsername()) + "\","
                + "\"pos\":{\"x\":" + px + ",\"y\":" + py + ",\"z\":" + pz + "},"
                + "\"hp\":1.0,"
                + "\"held_item\":null,"
                + "\"game_mode\":null"
                + "},"
                + "\"npc\":{"
                + "\"id\":\"" + PestSpawner.PEST_ROLE + "\","
                + "\"name\":\"" + PestSpawner.PEST_DISPLAY_NAME + "\","
                + "\"pos\":{\"x\":" + nx + ",\"y\":" + ny + ",\"z\":" + nz + "},"
                + "\"hp\":" + npcHp + ","
                + "\"goal\":\"" + BrainBridge.esc(goal) + "\""
                + "},"
                + "\"nearby\":" + nearbyJson + ","
                + "\"threats\":" + threatsJson + ","
                + "\"blocks_of_interest\":[{\"id\":\"mineable\",\"pos\":{\"x\":" + (nx + 1) + ",\"y\":" + ny + ",\"z\":" + nz + "}}],"
                + "\"inventory_hints\":" + invJson + ","
                + "\"raw\":{"
                + "\"hunting\":" + PestHuntState.isActive() + ","
                + "\"looting\":" + PestLootState.isActive() + ","
                + "\"base_built\":" + PestBaseState.isBuilt() + ","
                + "\"base_bed\":" + PestBaseState.isBedPlaced() + ","
                + "\"base_building\":" + PestBaseState.isBuilding() + ","
                + "\"has_wood\":" + hasWood + ","
                + "\"has_door\":" + hasDoor + ","
                + "\"has_bed\":" + hasBed + ","
                + "\"can_auto_home\":" + canHome + ","
                + "\"player_places\":" + PlayerBuildTracker.placeCountInWindow() + ","
                + "\"owner_idle_s\":" + (PestBehaviorRules.idleMs() / 1000L) + ","
                + "\"idle_hunt_threshold_s\":" + (PestBehaviorRules.idleThresholdMs() / 1000L) + ","
                + "\"idle_hunt\":" + idleHunt + ","
                + "\"stay_home\":" + stayHome + ","
                + "\"force_follow\":" + leaveFollow + ","
                + "\"invulnerable\":" + invuln + ","
                + "\"owner_dist\":" + String.format(java.util.Locale.US, "%.1f", distToPest) + ","
                + "\"owner_dist_base\":" + String.format(java.util.Locale.US, "%.1f", distToBase) + ","
                + "\"base\":\"" + BrainBridge.esc(PestBaseState.describe()) + "\","
                + "\"behavior\":\"" + BrainBridge.esc(PestBehaviorRules.describe()) + "\","
                + "\"entity\":\"" + (PestEntityTracker.entityId() != null ? PestEntityTracker.entityId() : "") + "\""
                + "}"
                + "}";

        bridge.sendState(state);
    }

    private void onAction(String name, String targetId, Double x, Double y, Double z,
                          String item, String text, String reason, String source) {
        if (name == null) {
            return;
        }
        String n = name.toLowerCase();
        String it = item == null ? "" : item;
        lastActionName = n;
        switch (n) {
            case "mine_block" -> {
                ManualMoveState.request(PestSpawner.PEST_ROLE, ManualMoveState.Kind.FORWARD);
                bridge.sendActionResult(true, "MINE: equip pick (slot0) + advance | " + reason);
            }
            case "explore", "move_toward" -> {
                String rlow = reason == null ? "" : reason.toLowerCase();
                if (it.contains("Axe") || it.contains("Hatchet") || rlow.contains("fibre")
                        || rlow.contains("wood") || rlow.contains("chop")) {
                    ManualMoveState.request(PestSpawner.PEST_ROLE, ManualMoveState.Kind.FORWARD_JUMP);
                    bridge.sendActionResult(true, "CHOP/GATHER: equip stone axe (slot1) | " + reason);
                } else {
                    ManualMoveState.request(PestSpawner.PEST_ROLE, ManualMoveState.Kind.FORWARD);
                    bridge.sendActionResult(true, "MOVE: equip pick path | " + reason);
                }
            }
            case "build_base" -> {
                long now = System.currentTimeMillis();
                String rlow = reason == null ? "" : reason.toLowerCase();
                boolean forced = rlow.contains("force") || rlow.contains("command")
                        || rlow.contains("chat") || rlow.contains("manual");
                if (!forced && !PestBehaviorRules.canAutoBuildHome()) {
                    bridge.sendActionResult(true,
                            "HOME waits for player >20 places/300s (now "
                                    + PlayerBuildTracker.placeCountInWindow() + ") | " + reason);
                    break;
                }
                if (PestBaseState.isBuilt() && PestBaseState.isBedPlaced()) {
                    bridge.sendActionResult(true,
                            "BASE already built: " + PestBaseState.describe() + " | " + reason);
                } else if (PestBaseState.isBuilding()) {
                    bridge.sendActionResult(true, "BASE build in progress | " + reason);
                } else if (now - lastBuildActionMs < 6_000L) {
                    bridge.sendActionResult(true, "BASE build cooldown | " + reason);
                } else {
                    lastBuildActionMs = now;
                    PestBaseBuilder.scheduleBuildNearpest(forced);
                    bridge.sendActionResult(true,
                            "BUILD HOME: empty site +1Y pathable door + bed | " + reason);
                }
            }
            case "place_block" -> {
                String rlow = reason == null ? "" : reason.toLowerCase();
                boolean wantsBase = it.contains("Plank") || it.contains("Door")
                        || rlow.contains("base") || rlow.contains("room")
                        || rlow.contains("wall") || rlow.contains("shelter")
                        || rlow.contains("closed") || rlow.contains("home");
                if (wantsBase || (PestBaseState.needsBase() && !PestBaseState.isBuilding()
                        && PestBehaviorRules.canAutoBuildHome())) {
                    boolean forced = rlow.contains("force") || rlow.contains("command")
                            || rlow.contains("chat") || rlow.contains("manual");
                    if (!forced && !PestBehaviorRules.canAutoBuildHome()) {
                        bridge.sendActionResult(true,
                                "HOME waits player build trigger (20+/300s) | " + reason);
                        break;
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastBuildActionMs >= 6_000L) {
                        lastBuildActionMs = now;
                        PestBaseBuilder.scheduleBuildNearpest(forced);
                    }
                    bridge.sendActionResult(true, "PLACE→BUILD HOME | " + reason);
                    break;
                }
                ManualMoveState.request(PestSpawner.PEST_ROLE, ManualMoveState.Kind.JUMP);
                double bx = PestEntityTracker.hasPosition() ? PestEntityTracker.x() : 0;
                double by = PestEntityTracker.hasPosition() ? PestEntityTracker.y() : 64;
                double bz = PestEntityTracker.hasPosition() ? PestEntityTracker.z() : 0;
                if (x != null) {
                    bx = x;
                }
                if (y != null) {
                    by = y;
                }
                if (z != null) {
                    bz = z;
                }
                if (x == null && PestEntityTracker.hasPosition()) {
                    if (PestBaseState.isBuilt()) {
                        bx = PestBaseState.originX() + 2.5;
                        by = PestBaseState.originY();
                        bz = PestBaseState.originZ() + 2.5;
                    } else {
                        bx = PestEntityTracker.x() + 1.0;
                        by = PestEntityTracker.y();
                        bz = PestEntityTracker.z();
                    }
                }
                if (it.contains("Bed") || it.isEmpty() || rlow.contains("bed") || rlow.contains("spawn")) {
                    PestBedSpawn.scheduleSetSpawnAtBed(bx, by, bz);
                    PestBaseState.markBedPlaced((int) Math.floor(bx), (int) Math.floor(by), (int) Math.floor(bz));
                    bridge.sendActionResult(true,
                            "PLACE BED + auto-set owner respawn | " + reason);
                } else {
                    bridge.sendActionResult(true, "PLACE BLOCK item=" + it + " | " + reason);
                }
            }
            case "craft_hint" -> {
                String msg = (text != null && !text.isBlank())
                        ? text
                        : "Craft Crude Bed at Fieldcraft: 3 Fibre + 2 Light Hide. Put it inside our base.";
                for (PlayerRef p : Universe.get().getPlayers()) {
                    if (p != null) {
                        p.sendMessage(com.hypixel.hytale.server.core.Message.raw("[Pest] " + msg));
                    }
                }
                bridge.sendActionResult(true, "craft_hint told owner | " + reason);
            }
            case "pickup", "use_item" -> {
                PestLootState.enable("brain_pickup", 8_000L);
                ManualMoveState.request(PestSpawner.PEST_ROLE, ManualMoveState.Kind.FORWARD);
                bridge.sendActionResult(true, "LOOT: wide hoover after combat | " + reason);
            }
            case "flee" -> {
                ManualMoveState.request(PestSpawner.PEST_ROLE, ManualMoveState.Kind.FORWARD_JUMP);
                bridge.sendActionResult(true, "flee with mobility | " + reason);
            }
            case "attack" -> {
                String rlow = reason == null ? "" : reason.toLowerCase();
                if (rlow.contains("hunt") || rlow.contains("hide") || rlow.contains("prey")
                        || rlow.contains("resource") || rlow.contains("drop")) {
                    PestHuntState.enable(reason, 25_000L);
                    PestLootState.enableAfterCombat();
                    bridge.sendActionResult(true,
                            "HUNT: sword + lock safe prey for drops | " + reason);
                } else {
                    if (PreyMemory.hasPrey(PestSpawner.PEST_ROLE)) {
                        PestHuntState.enable("combat-with-prey", 12_000L);
                    }
                    PestLootState.enableAfterCombat();
                    bridge.sendActionResult(true,
                            "FIGHT: stone sword (slot2) via role AI | " + reason);
                }
            }
            case "follow_player", "idle" -> {
                bridge.sendActionResult(true, "role-AI " + n + " | " + reason);
            }
            case "chat" -> {
                if (text != null && !text.isBlank()) {
                    for (PlayerRef p : Universe.get().getPlayers()) {
                        if (p != null) {
                            p.sendMessage(com.hypixel.hytale.server.core.Message.raw("[Pest] " + text));
                        }
                    }
                }
                bridge.sendActionResult(true, "chat");
            }
            default -> bridge.sendActionResult(true, "noop " + n);
        }
        LOGGER.atFine().log("Brain action " + n + " reason=" + reason + " source=" + source
                + " last=" + lastActionName);
    }
}
