package com.orffyrus.pest;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

/**
 * Real place detection: PlaceBlockEvent is an <b>ECS</b> event (not EventRegistry).
 * Register via {@code getEntityStoreRegistry().registerSystem(...)}.
 */
public final class PlaceBlockTrackSystem extends EntityEventSystem<EntityStore, PlaceBlockEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public PlaceBlockTrackSystem() {
        super(PlaceBlockEvent.class);
    }

    @Override
    public void handle(int index,
                       ArchetypeChunk<EntityStore> chunk,
                       Store<EntityStore> store,
                       CommandBuffer<EntityStore> buffer,
                       PlaceBlockEvent event) {
        try {
            if (event == null || event.isCancelled()) {
                return;
            }
            Vector3i target = event.getTargetBlock();
            if (target != null) {
                PlayerBuildTracker.recordPlace(target);
            } else {
                PlayerBuildTracker.recordPlace(
                        PestEntityTracker.hasPosition() ? PestEntityTracker.x() : 0,
                        PestEntityTracker.hasPosition() ? PestEntityTracker.y() : 64,
                        PestEntityTracker.hasPosition() ? PestEntityTracker.z() : 0);
            }
        } catch (Exception e) {
            LOGGER.atFine().log("PlaceBlockTrackSystem: " + e);
        }
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }
}
