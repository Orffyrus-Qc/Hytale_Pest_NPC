package com.orffyrus.pest;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.ActionBase;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.IPositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import org.joml.Vector3d;

/** Records nearest locked mob as prey for hide/drop hunting. */
public class NoteNearbyPreyAction extends ActionBase {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public NoteNearbyPreyAction(NoteNearbyPreyActionBuilder builder, BuilderSupport support) {
        super(builder);
    }

    @Override
    public boolean execute(Ref<EntityStore> ref, Role role, InfoProvider info,
                            double delta, Store<EntityStore> store) {
        super.execute(ref, role, info, delta, store);

        IPositionProvider target = info.getPositionProvider();
        if (target == null || !target.hasPosition()) {
            return false;
        }

        TransformComponent selfTransform = store.getComponent(ref, TransformComponent.getComponentType());
        if (selfTransform == null) {
            return false;
        }
        Vector3d selfPos = selfTransform.getPosition();

        double dx = target.getX() - selfPos.x;
        double dz = target.getZ() - selfPos.z;
        double distance = Math.sqrt(dx * dx + dz * dz);

        String npcId = role.getRoleName();
        PreyMemory.record(npcId, distance);
        LOGGER.atFine().log(npcId + " noted prey ~" + Math.round(distance) + " blocks");
        return true;
    }
}
