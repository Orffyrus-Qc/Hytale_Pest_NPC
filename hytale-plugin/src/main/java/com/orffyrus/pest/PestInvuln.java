package com.orffyrus.pest;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;

/**
 * Invulnerable until Pest finishes a base + bed; then remove the component so he can die
 * and respawn at his bed.
 */
public final class PestInvuln {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private PestInvuln() { }

    public static void applyUntilBase(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (ref == null || store == null) {
            return;
        }
        try {
            if (store.getComponent(ref, Invulnerable.getComponentType()) == null) {
                store.addComponent(ref, Invulnerable.getComponentType(), Invulnerable.INSTANCE);
                LOGGER.atInfo().log("Pest invulnerable until base is placed");
            }
        } catch (Exception e) {
            LOGGER.atFine().log("apply invuln: " + e);
        }
    }

    public static void clearAfterBase() {
        UUID id = PestEntityTracker.entityId();
        if (id == null) {
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
                        Store<EntityStore> store = es.getStore();
                        if (store.getComponent(ref, Invulnerable.getComponentType()) != null) {
                            store.removeComponent(ref, Invulnerable.getComponentType());
                            LOGGER.atInfo().log("Pest invulnerability removed — base complete");
                        }
                    } catch (Exception e) {
                        LOGGER.atFine().log("clear invuln: " + e);
                    }
                });
                return;
            }
        } catch (Exception e) {
            LOGGER.atFine().log("clearAfterBase: " + e);
        }
    }

    /** Re-apply if still pre-base (called on spawn). */
    public static void syncWithBaseState(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (PestBehaviorRules.isInvulnerablePhase()) {
            applyUntilBase(ref, store);
        } else {
            try {
                if (store.getComponent(ref, Invulnerable.getComponentType()) != null) {
                    store.removeComponent(ref, Invulnerable.getComponentType());
                }
            } catch (Exception ignored) {
            }
        }
    }
}
