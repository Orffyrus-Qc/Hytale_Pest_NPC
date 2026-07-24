package com.orffyrus.pest;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks live Pest entities (position, inventory, despawn on re-spawn).
 */
public final class PestEntityTracker {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final AtomicReference<UUID> ENTITY_ID = new AtomicReference<>();
    private static final Set<UUID> ALL_PEST = ConcurrentHashMap.newKeySet();
    private static volatile String lastInventorySummary = "";
    private static volatile double lastX, lastY, lastZ;
    private static volatile long lastRefreshMs;

    private PestEntityTracker() { }

    public static void remember(Ref<EntityStore> ref, Store<EntityStore> store, Inventory inv) {
        try {
            NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
            if (npc != null && npc.getUuid() != null) {
                UUID id = npc.getUuid();
                ENTITY_ID.set(id);
                ALL_PEST.add(id);
            }
        } catch (Exception ignored) {
        }
        if (inv != null) {
            lastInventorySummary = PestGear.describeInventory(inv);
        }
        captureTransform(store, ref);
    }

    /**
     * Remove previously tracked Pest bodies so force/auto re-spawn does not stack clones.
     */
    public static int despawnAll(World world, Store<EntityStore> store) {
        if (world == null || store == null) {
            return 0;
        }
        int removed = 0;
        Set<UUID> copy = ConcurrentHashMap.newKeySet();
        copy.addAll(ALL_PEST);
        UUID primary = ENTITY_ID.get();
        if (primary != null) {
            copy.add(primary);
        }
        for (UUID id : copy) {
            try {
                Ref<EntityStore> ref = world.getEntityRef(id);
                if (ref == null) {
                    ALL_PEST.remove(id);
                    continue;
                }
                NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
                if (npc != null) {
                    try {
                        npc.setToDespawn();
                    } catch (Exception ignored) {
                    }
                }
                try {
                    store.removeEntity(ref, RemoveReason.REMOVE);
                    removed++;
                } catch (Exception e) {
                    LOGGER.atFine().log("despawn removeEntity: " + e);
                }
                ALL_PEST.remove(id);
            } catch (Exception e) {
                LOGGER.atFine().log("despawnAll id=" + id + ": " + e);
            }
        }
        ENTITY_ID.set(null);
        lastInventorySummary = "";
        if (removed > 0) {
            LOGGER.atInfo().log("Despawned " + removed + " previous Pest entity(ies)");
        }
        return removed;
    }

    public static void updateFromWorld(Store<EntityStore> store, Ref<EntityStore> ref) {
        if (store == null || ref == null) {
            return;
        }
        try {
            NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
            if (npc == null) {
                return;
            }
            Inventory inv = npc.getInventory();
            if (inv != null) {
                lastInventorySummary = PestGear.describeInventory(inv);
            }
            captureTransform(store, ref);
            lastRefreshMs = System.currentTimeMillis();
        } catch (Exception ignored) {
        }
    }

    /**
     * Refresh pos/inventory from the tracked entity if loaded in any world.
     * Called from brain ticker (off world thread) — schedules world.execute when needed.
     */
    public static void refreshIfPossible() {
        UUID id = ENTITY_ID.get();
        if (id == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRefreshMs < 400L) {
            return;
        }
        try {
            for (World world : Universe.get().getWorlds().values()) {
                if (world == null) {
                    continue;
                }
                Ref<EntityStore> ref = world.getEntityRef(id);
                if (ref == null) {
                    continue;
                }
                world.execute(() -> {
                    try {
                        EntityStore es = world.getEntityStore();
                        if (es == null) {
                            return;
                        }
                        updateFromWorld(es.getStore(), ref);
                    } catch (Exception e) {
                        LOGGER.atFine().log("refreshIfPossible: " + e);
                    }
                });
                return;
            }
        } catch (Exception e) {
            LOGGER.atFine().log("refreshIfPossible outer: " + e);
        }
    }

    /**
     * Re-apply starter kit on the live entity (for /pest regear).
     */
    public static String regearOnWorld(World world) {
        UUID id = ENTITY_ID.get();
        if (id == null || world == null) {
            return "no tracked Pest — /pest spawn force first";
        }
        final String[] out = {"pending"};
        try {
            world.execute(() -> {
                try {
                    EntityStore es = world.getEntityStore();
                    if (es == null) {
                        out[0] = "no entity store";
                        return;
                    }
                    Store<EntityStore> store = es.getStore();
                    Ref<EntityStore> ref = world.getEntityRef(id);
                    if (ref == null) {
                        out[0] = "entity not in this world";
                        return;
                    }
                    out[0] = PestGear.equipStarter(ref, store);
                } catch (Exception e) {
                    out[0] = "regear failed: " + e.getMessage();
                }
            });
            // world.execute may be async relative to calling thread; brief wait
            for (int i = 0; i < 20 && "pending".equals(out[0]); i++) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return out[0];
        } catch (Exception e) {
            return "regear error: " + e.getMessage();
        }
    }

    private static void captureTransform(Store<EntityStore> store, Ref<EntityStore> ref) {
        try {
            var transform = store.getComponent(ref,
                    com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
            if (transform != null && transform.getPosition() != null) {
                Vector3d p = transform.getPosition();
                lastX = p.x;
                lastY = p.y;
                lastZ = p.z;
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Move Pest to a world position (teleport). Must run on the world thread.
     * @return true if transform was updated
     */
    public static boolean moveTo(World world, Store<EntityStore> store, double x, double y, double z) {
        if (world == null || store == null) {
            return false;
        }
        UUID id = ENTITY_ID.get();
        if (id == null) {
            return false;
        }
        try {
            Ref<EntityStore> ref = world.getEntityRef(id);
            if (ref == null) {
                return false;
            }
            var transform = store.getComponent(ref,
                    com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
            if (transform == null) {
                return false;
            }
            Vector3d pos = new Vector3d(x, y, z);
            try {
                transform.teleportPosition(pos);
            } catch (Exception e) {
                transform.setPosition(pos);
            }
            lastX = x;
            lastY = y;
            lastZ = z;
            LOGGER.atInfo().log("Pest moved to " + String.format("%.1f,%.1f,%.1f", x, y, z));
            return true;
        } catch (Exception e) {
            LOGGER.atWarning().log("moveTo failed: " + e);
            return false;
        }
    }

    public static UUID entityId() {
        return ENTITY_ID.get();
    }

    public static String inventorySummary() {
        return lastInventorySummary == null ? "" : lastInventorySummary;
    }

    public static double x() {
        return lastX;
    }

    public static double y() {
        return lastY;
    }

    public static double z() {
        return lastZ;
    }

    public static boolean hasPosition() {
        return lastY != 0 || lastX != 0 || lastZ != 0;
    }
}
