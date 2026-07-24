package com.orffyrus.pest;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import org.joml.Vector3d;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Spawns companion role {@value #PEST_ROLE} next to Adventure/Creative players. */
public final class PestSpawner {

    public static final String PEST_ROLE = "Pest";
    public static final String PEST_DISPLAY_NAME = "Pest";

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Set<UUID> SPAWNED_FOR = ConcurrentHashMap.newKeySet();

    public static volatile boolean AUTO_SPAWN_IN_CREATIVE = true;

    public void onPlayerReady(PlayerReadyEvent event) {
        try {
            Player player = event.getPlayer();
            if (player == null) {
                return;
            }
            GameMode mode = player.getGameMode();
            if (mode != GameMode.Adventure
                    && !(AUTO_SPAWN_IN_CREATIVE && mode == GameMode.Creative)) {
                return;
            }
            PlayerRef playerRef = player.getPlayerRef();
            if (playerRef == null) {
                return;
            }
            spawnForPlayer(playerRef, false, "auto (" + mode + ")", null);
        } catch (Exception e) {
            LOGGER.atWarning().log("PestSpawner.onPlayerReady failed: " + e);
        }
    }

    public static void spawnForPlayer(PlayerRef playerRef, boolean force, String reason,
                                      Consumer<String> onDone) {
        if (playerRef == null) {
            if (onDone != null) {
                onDone.accept("No player.");
            }
            return;
        }
        UUID playerUuid = playerRef.getUuid();
        if (force) {
            SPAWNED_FOR.remove(playerUuid);
        }
        if (!SPAWNED_FOR.add(playerUuid)) {
            // Session flagged - if entity is gone, clear and re-spawn instead of dead-end
            UUID existing = PestEntityTracker.entityId();
            boolean alive = false;
            try {
                World w = Universe.get().getWorld(playerRef.getWorldUuid());
                if (w == null) {
                    w = Universe.get().getDefaultWorld();
                }
                if (w != null && existing != null && w.getEntityRef(existing) != null) {
                    alive = true;
                }
            } catch (Exception ignored) {
            }
            if (!alive) {
                LOGGER.atInfo().log("Pest session flag set but entity missing - re-spawning");
                SPAWNED_FOR.remove(playerUuid);
                SPAWNED_FOR.add(playerUuid);
                // fall through to spawn
            } else {
                CompanionState.markCompanion(PEST_ROLE, playerUuid);
                String msg = "Pest already active. Use /pest spawn force to replace. brain="
                        + (PestPlugin.BRIDGE != null && PestPlugin.BRIDGE.isConnected()
                        ? "connected" : "OFFLINE");
                if (onDone != null) {
                    onDone.accept(msg);
                }
                return;
            }
        }

        Transform transform = playerRef.getTransform();
        if (transform == null || transform.getPosition() == null) {
            SPAWNED_FOR.remove(playerUuid);
            if (onDone != null) {
                onDone.accept("Could not read your position.");
            }
            return;
        }
        Vector3d pos = transform.getPosition();

        World world = Universe.get().getWorld(playerRef.getWorldUuid());
        if (world == null) {
            world = Universe.get().getDefaultWorld();
        }
        if (world == null) {
            SPAWNED_FOR.remove(playerUuid);
            if (onDone != null) {
                onDone.accept("No world available.");
            }
            return;
        }

        final World spawnWorld = world;
        final Vector3d spawnAt = new Vector3d(pos.x + 2.0, pos.y, pos.z + 1.0);
        final Rotation3f rotation = new Rotation3f(0f, 0f, 0f);
        final String username = playerRef.getUsername();

        spawnWorld.execute(() -> {
            try {
                EntityStore entityStore = spawnWorld.getEntityStore();
                if (entityStore == null) {
                    SPAWNED_FOR.remove(playerUuid);
                    notify(playerUuid, onDone, "Entity store unavailable.");
                    return;
                }
                Store<EntityStore> store = entityStore.getStore();
                NPCPlugin npcPlugin = NPCPlugin.get();
                if (npcPlugin == null || !npcPlugin.hasRoleName(PEST_ROLE)) {
                    SPAWNED_FOR.remove(playerUuid);
                    notify(playerUuid, onDone,
                            "Pest role not loaded. Is PestAiNpc jar installed with IncludesAssetPack?");
                    return;
                }

                // Avoid stacked clones on force/auto re-spawn
                int despawned = PestEntityTracker.despawnAll(spawnWorld, store);
                if (despawned > 0) {
                    LOGGER.atInfo().log("Cleared " + despawned + " old Pest before spawn (" + reason + ")");
                }
                // Fresh spawn may rebuild shelter (force always resets base flag)
                if (reason != null && reason.toLowerCase().contains("force")) {
                    PestBaseState.reset();
                }
                // Start 15‑min home timer + idle-hunt / follow rules
                PestBehaviorRules.onSessionStart();

                var pair = npcPlugin.spawnNPC(store, PEST_ROLE, null, spawnAt, rotation);
                if (pair == null) {
                    SPAWNED_FOR.remove(playerUuid);
                    notify(playerUuid, onDone, "spawnNPC failed (null). Try /npc spawn Pest");
                    return;
                }

                CompanionState.markCompanion(PEST_ROLE, playerUuid);

                // Player-like inventory: tools, weapon, placeable blocks
                String gear = "gear skipped";
                try {
                    var entityRef = pair.left();
                    if (entityRef != null) {
                        gear = PestGear.equipStarter(entityRef, store);
                        PestInvuln.syncWithBaseState(entityRef, store);
                        // If respawning at bed, move near bed anchor
                        if (reason != null && reason.toLowerCase().contains("bed")
                                && PestBaseState.isBuilt()) {
                            // position already near player; bed is on base
                        }
                    }
                } catch (Exception gearEx) {
                    gear = "gear error: " + gearEx.getMessage();
                    LOGGER.atWarning().log("Pest gear equip: " + gearEx);
                }

                LOGGER.atInfo().log("Spawned Pest for " + username + " reason=" + reason + " " + gear);

                PlayerRef fresh = Universe.get().getPlayer(playerUuid);
                if (fresh != null) {
                    fresh.sendMessage(Message.raw(
                            "[Pest] Ready. Invulnerable until my home is up. "
                                    + "Place 20+ blocks in 300s → I build ~100 away. "
                                    + "Gear: " + gear));
                }
                if (onDone != null) {
                    onDone.accept("Spawned Pest (" + reason + "). " + gear);
                }
            } catch (Exception e) {
                LOGGER.atWarning().log("Failed to spawn Pest for " + username + ": " + e);
                SPAWNED_FOR.remove(playerUuid);
                notify(playerUuid, onDone, "Spawn failed: " + e.getMessage());
            }
        });
    }

    private static void notify(UUID playerUuid, Consumer<String> onDone, String msg) {
        if (onDone != null) {
            onDone.accept(msg);
        } else {
            PlayerRef fresh = Universe.get().getPlayer(playerUuid);
            if (fresh != null) {
                fresh.sendMessage(Message.raw(msg));
            }
        }
    }

    public static String statusLine(UUID playerUuid) {
        boolean flagged = SPAWNED_FOR.contains(playerUuid);
        boolean companion = CompanionState.isCompanion(PEST_ROLE)
                && playerUuid.equals(CompanionState.getOwner(PEST_ROLE));
        boolean bridge = PestPlugin.BRIDGE != null && PestPlugin.BRIDGE.isConnected();
        String mode = PestPlugin.BRIDGE != null ? PestPlugin.BRIDGE.lastMode() : "?";
        UUID ent = PestEntityTracker.entityId();
        String inv = PestEntityTracker.inventorySummary();
        return "Pest status | session=" + (flagged ? "yes" : "no")
                + " | ownerMarked=" + (companion ? "yes" : "no")
                + " | entity=" + (ent != null ? "tracked" : "none")
                + " | brain=" + (bridge ? "connected" : "OFFLINE")
                + " | mode=" + mode
                + " | hunt=" + PestHuntState.isActive()
                + " | loot=" + PestLootState.isActive()
                + " | inv=" + (inv.isBlank() ? "?" : inv.length() > 60 ? inv.substring(0, 60) + "..." : inv);
    }

    public static boolean hasSessionSpawn(UUID playerUuid) {
        return SPAWNED_FOR.contains(playerUuid);
    }
}
