package com.orffyrus.pest;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * In-game control for Pest.
 *
 * <pre>
 * /pest status | spawn [force] | regear | base | bed
 * /pest hunt | loot | mode hybrid | creative on|off
 * </pre>
 */
public class PestCommand extends AbstractPlayerCommand {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public PestCommand() {
        super("pest", "Pest companion + Docker brain on port 8766");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(CommandContext context,
                           Store<EntityStore> store,
                           Ref<EntityStore> ref,
                           PlayerRef playerRef,
                           World world) {
        List<String> tokens = tokenize(context);
        String action = tokens.isEmpty() ? "help" : tokens.get(0).toLowerCase(Locale.ROOT);
        String option = tokens.size() > 1 ? tokens.get(1).toLowerCase(Locale.ROOT) : "";

        LOGGER.atInfo().log(
                "PestCommand action=" + action + " option=" + option
                        + " raw='" + safe(context.getInputString()) + "'");

        switch (action) {
            case "status", "info", "st" -> {
                msg(context, PestSpawner.statusLine(playerRef.getUuid()));
                String inv = PestEntityTracker.inventorySummary();
                if (inv != null && !inv.isBlank()) {
                    msg(context, "Pest inventory: " + inv);
                } else {
                    msg(context, "Pest inventory: (unknown — /pest spawn force or /pest regear)");
                }
                msg(context, "Model: Outlander_Marauder | kit: tools+planks+door+bed");
                msg(context, "Base: " + PestBaseState.describe());
                msg(context, "Rules: " + PestBehaviorRules.describe());
                msg(context, "Hunt=" + PestHuntState.isActive()
                        + " loot=" + PestLootState.isActive()
                        + " invuln=" + PestBehaviorRules.isInvulnerablePhase());
                msg(context, "Home: place >20 blocks/300s → build ~100 from your base");
                msg(context, "Stay at home until you are ≥200 from Pest base, then follow");
                boolean brain = PestPlugin.BRIDGE != null && PestPlugin.BRIDGE.isConnected();
                msg(context, "Brain: " + (brain ? "connected :8766 mode="
                        + PestPlugin.BRIDGE.lastMode() : "OFFLINE — start Docker brain"));
            }
            case "gear", "kit", "equip", "regear" -> {
                if ("info".equals(option) || "help".equals(option)) {
                    msg(context, "Tools: pick | axe | sword | Wood_Softwood_Planks | Crude Door | Crude Bed");
                    msg(context, "Base: closed wood room + door, bed inside for spawn");
                    return;
                }
                CompanionState.markCompanion(PestSpawner.PEST_ROLE, playerRef.getUuid());
                String result = PestEntityTracker.regearOnWorld(world);
                msg(context, "Regear: " + result);
                msg(context, "If no entity: /pest spawn force");
            }
            case "base", "build", "shelter", "house", "home" -> {
                CompanionState.markCompanion(PestSpawner.PEST_ROLE, playerRef.getUuid());
                boolean force = "force".equals(option) || "f".equals(option) || "again".equals(option)
                        || "now".equals(option);
                if (force) {
                    PestBaseState.reset();
                    PlayerBuildTracker.forceEligible();
                }
                PestBaseBuilder.scheduleBuildNearpest(true);
                msg(context, "Building home NOW (force): empty site +1Y, pathable doorway, bed.");
            }
            case "places", "blocks" -> {
                msg(context, "Player builds: " + PlayerBuildTracker.describe());
                msg(context, "Need " + PlayerBuildTracker.PLACE_THRESHOLD
                        + " places in 300s (latched until home). Force: /pest base force");
            }
            case "bed", "spawnpoint", "sleep" -> {
                if (PestBaseState.isBuilt()) {
                    PestBedSpawn.scheduleSetSpawnAtBed(
                            PestBaseState.originX() + 2.5,
                            PestBaseState.originY(),
                            PestBaseState.originZ() + 2.5);
                    msg(context, "Setting spawn at base bed / room center (Pest Camp).");
                } else {
                    msg(context, "No base yet. Prefer /pest base first (room+door+bed).");
                    msg(context, "Or place Furniture_Crude_Bed and use it once.");
                }
            }
            case "hunt" -> {
                CompanionState.markCompanion(PestSpawner.PEST_ROLE, playerRef.getUuid());
                PestHuntState.enable("command hunt", 30_000L);
                PestLootState.enableAfterCombat();
                msg(context, "Pest hunt mode 30s — safe prey only (no merchants).");
            }
            case "loot", "pickup" -> {
                PestLootState.enable("command loot", 12_000L);
                ManualMoveState.request(PestSpawner.PEST_ROLE, ManualMoveState.Kind.FORWARD);
                msg(context, "Pest loot window 12s — picking up nearby drops.");
            }
            case "spawn" -> {
                boolean force = "force".equals(option) || "f".equals(option);
                PestSpawner.spawnForPlayer(
                        playerRef,
                        force,
                        force ? "command force" : "command",
                        text -> msg(context, text));
            }
            case "force" -> PestSpawner.spawnForPlayer(
                    playerRef, true, "command force",
                    text -> msg(context, text));
            case "mode" -> {
                if (option.isEmpty()) {
                    String cur = PestPlugin.BRIDGE != null ? PestPlugin.BRIDGE.lastMode() : "?";
                    msg(context, "Current mode: " + cur);
                    msg(context, "Set: /pest mode observe|imitate|hybrid|autonomous");
                    return;
                }
                if (!isMode(option)) {
                    msg(context, "Unknown mode '" + option + "'. Use observe|imitate|hybrid|autonomous");
                    return;
                }
                if (PestPlugin.BRIDGE == null) {
                    msg(context, "Brain bridge not ready.");
                    return;
                }
                if (!PestPlugin.BRIDGE.isConnected()) {
                    msg(context, "Brain offline — mode stored until Docker is up.");
                }
                PestPlugin.BRIDGE.sendMode(option);
                msg(context, "Pest brain mode set to: " + option);
            }
            case "creative" -> {
                if ("on".equals(option) || "true".equals(option) || "1".equals(option)) {
                    PestSpawner.AUTO_SPAWN_IN_CREATIVE = true;
                    msg(context, "Pest auto-spawn in Creative: ON");
                } else if ("off".equals(option) || "false".equals(option) || "0".equals(option)) {
                    PestSpawner.AUTO_SPAWN_IN_CREATIVE = false;
                    msg(context, "Pest auto-spawn in Creative: OFF");
                } else {
                    msg(context, "Creative auto-spawn: "
                            + (PestSpawner.AUTO_SPAWN_IN_CREATIVE ? "ON" : "OFF")
                            + " — /pest creative on|off");
                }
            }
            case "help", "?", "" -> sendHelp(context);
            default -> {
                msg(context, "Unknown /pest subcommand: " + action);
                sendHelp(context);
            }
        }
    }

    private static List<String> tokenize(CommandContext context) {
        String raw = safe(context.getInputString());
        if (raw.toLowerCase(Locale.ROOT).startsWith("pest")) {
            raw = raw.substring(4).trim();
        }
        raw = raw.replace("--action", " ").replace("--option", " ").replace("--args", " ");
        List<String> out = new ArrayList<>();
        for (String part : raw.split("\\s+")) {
            if (part.isBlank() || part.startsWith("--")) {
                continue;
            }
            out.add(part.trim());
        }
        return out;
    }

    private static boolean isMode(String m) {
        return "observe".equals(m) || "imitate".equals(m)
                || "hybrid".equals(m) || "autonomous".equals(m);
    }

    private static void sendHelp(CommandContext context) {
        msg(context, "Pest (Docker brain :8766):");
        msg(context, "  /pest status | spawn force | regear | places");
        msg(context, "  /pest base force | bed | hunt | loot");
        msg(context, "  /pest mode hybrid | creative on|off");
        msg(context, "Chat: Pest, build a base | Pest, hunt | Pest, follow");
        msg(context, "Auto-home: 20+ places/300s (NO 15min). Force: /pest base force");
    }

    private static void msg(CommandContext context, String text) {
        context.sendMessage(Message.raw(text));
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
