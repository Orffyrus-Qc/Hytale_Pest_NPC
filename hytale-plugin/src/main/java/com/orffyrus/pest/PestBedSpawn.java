package com.orffyrus.pest;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerConfigData;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerWorldData;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * After Pest places Furniture_Crude_Bed, set the owner's active respawn point
 * at that location (auto "use bed for spawn" without UI).
 */
public final class PestBedSpawn {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String POINT_NAME = "Pest Camp";

    private PestBedSpawn() { }

    /**
     * Schedule spawn-point set slightly after place so the block exists in-world.
     */
    public static void scheduleSetSpawnAtBed(double x, double y, double z) {
        UUID ownerId = CompanionState.getOwner(PestSpawner.PEST_ROLE);
        if (ownerId == null) {
            LOGGER.atWarning().log("Bed place: no companion owner to set spawn for");
            return;
        }
        final double bx = x;
        final double by = y;
        final double bz = z;
        final UUID oid = ownerId;
        new java.util.Timer(true).schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                try {
                    setOwnerSpawn(oid, bx, by, bz);
                } catch (Exception e) {
                    LOGGER.atWarning().log("Auto bed spawn failed: " + e);
                }
            }
        }, 800L);
    }

    public static void setOwnerSpawn(UUID ownerId, double x, double y, double z) {
        PlayerRef pref = Universe.get().getPlayer(ownerId);
        if (pref == null) {
            LOGGER.atWarning().log("Auto bed spawn: owner offline");
            return;
        }
        World world = Universe.get().getWorld(pref.getWorldUuid());
        if (world == null) {
            world = Universe.get().getDefaultWorld();
        }
        if (world == null) {
            return;
        }
        final World w = world;
        w.execute(() -> {
            try {
                EntityStore es = w.getEntityStore();
                if (es == null) {
                    return;
                }
                Store<EntityStore> store = es.getStore();
                Ref<EntityStore> prefRef = w.getEntityRef(ownerId);
                if (prefRef == null) {
                    // try player entity via PlayerRef uuid
                    LOGGER.atWarning().log("Auto bed spawn: no entity ref for owner");
                    return;
                }
                Player player = store.getComponent(prefRef, Player.getComponentType());
                if (player == null) {
                    LOGGER.atWarning().log("Auto bed spawn: Player component missing");
                    return;
                }
                PlayerConfigData config = player.getPlayerConfigData();
                if (config == null) {
                    LOGGER.atWarning().log("Auto bed spawn: no PlayerConfigData");
                    return;
                }
                String worldName = w.getName();
                PlayerWorldData worldData = config.getPerWorldData(worldName);
                if (worldData == null) {
                    // try empty key / create path - if null, cannot set
                    LOGGER.atWarning().log("Auto bed spawn: no per-world data for " + worldName);
                    pref.sendMessage(Message.raw(
                            "[Pest] Placed bed, but could not write spawn data. Use the bed once yourself."));
                    return;
                }

                int ix = (int) Math.floor(x);
                int iy = (int) Math.floor(y);
                int iz = (int) Math.floor(z);
                Vector3i blockPos = new Vector3i(ix, iy, iz);
                Vector3d respawnPos = new Vector3d(ix + 0.5, iy + 1.0, iz + 0.5);
                PlayerRespawnPointData point = new PlayerRespawnPointData(blockPos, respawnPos, POINT_NAME);

                PlayerRespawnPointData[] existing = worldData.getRespawnPoints();
                List<PlayerRespawnPointData> list = new ArrayList<>();
                if (existing != null) {
                    for (PlayerRespawnPointData p : existing) {
                        if (p == null) {
                            continue;
                        }
                        // Replace previous Pest Camp entry
                        if (POINT_NAME.equals(p.getName())) {
                            continue;
                        }
                        list.add(p);
                    }
                }
                // Active = first / newest at front
                list.add(0, point);
                worldData.setRespawnPoints(list.toArray(new PlayerRespawnPointData[0]));
                player.markNeedsSave();

                pref.sendMessage(Message.raw(
                        "[Pest] Sleeping bag placed. Your respawn point is set to \""
                                + POINT_NAME + "\" at "
                                + ix + "," + iy + "," + iz + "."));
                LOGGER.atInfo().log("Set player respawn '" + POINT_NAME + "' at "
                        + ix + "," + iy + "," + iz + " for " + pref.getUsername());
            } catch (Exception e) {
                LOGGER.atWarning().log("setOwnerSpawn error: " + e);
                try {
                    PlayerRef p2 = Universe.get().getPlayer(ownerId);
                    if (p2 != null) {
                        p2.sendMessage(Message.raw(
                                "[Pest] Bed placed. If spawn did not update, use the bed once."));
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }
}
