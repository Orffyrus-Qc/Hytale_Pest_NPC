package com.orffyrus.pest;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.SensorBase;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;

/** True while brain enabled resource-hunt mode ({@link PestHuntState}). */
public class IsHuntingSensor extends SensorBase {

    public IsHuntingSensor(IsHuntingSensorBuilder builder, BuilderSupport support) {
        super(builder);
    }

    @Override
    public boolean matches(Ref<EntityStore> ref, Role role, double delta, Store<EntityStore> store) {
        if (!super.matches(ref, role, delta, store)) {
            return false;
        }
        return PestHuntState.isActive();
    }

    @Override
    public InfoProvider getSensorInfo() {
        return null;
    }
}
