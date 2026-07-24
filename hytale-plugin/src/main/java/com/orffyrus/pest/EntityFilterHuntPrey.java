package com.orffyrus.pest;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.EntityFilterBase;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;

import java.util.Locale;
import java.util.Set;

/**
 * Safer hunt target filter: wildlife / hostiles OK; never merchants, friends,
 * companions, or revered NPCs.
 */
public class EntityFilterHuntPrey extends EntityFilterBase {

    private static final Set<String> BLOCKED_NAME_PARTS = Set.of(
            "merchant", "villager", "shop", "trader", "companion",
            "auri", "mori", "pest", "klops", "kweebec_merchant",
            "temple_klops", "temple_kweebec", "guide", "quest",
            "adventurer", "vendor", "innkeeper", "blacksmith", "farmer",
            "settler", "citizen", "npc_friendly", "outlander_merchant"
    );

    public EntityFilterHuntPrey(EntityFilterHuntPreyBuilder builder, BuilderSupport support) {
    }

    @Override
    public boolean matchesEntity(Ref<EntityStore> self, Ref<EntityStore> candidate, Role role, Store<EntityStore> store) {
        if (candidate == null || (self != null && self.equals(candidate))) {
            return false;
        }
        NPCEntity npc = store.getComponent(candidate, NPCEntity.getComponentType());
        if (npc == null) {
            return false;
        }
        Role candidateRole = npc.getRole();
        if (candidateRole == null) {
            return false;
        }

        String roleName = safeLower(npc.getRoleName());
        String typeId = safeLower(npc.getNPCTypeId());
        if (isBlockedName(roleName) || isBlockedName(typeId)) {
            return false;
        }

        Attitude att;
        try {
            att = candidateRole.getWorldSupport().getDefaultPlayerAttitude();
        } catch (Exception e) {
            return false;
        }
        // Never hunt friendlies / worshiped NPCs
        if (att == Attitude.FRIENDLY || att == Attitude.REVERED) {
            return false;
        }
        // HOSTILE, NEUTRAL, IGNORE = valid prey / threats for drops
        return att == Attitude.HOSTILE || att == Attitude.NEUTRAL || att == Attitude.IGNORE;
    }

    private static boolean isBlockedName(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (String part : BLOCKED_NAME_PARTS) {
            if (s.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    @Override
    public int cost() {
        return MINIMAL_COST;
    }
}
