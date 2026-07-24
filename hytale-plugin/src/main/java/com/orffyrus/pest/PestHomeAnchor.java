package com.orffyrus.pest;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import org.joml.Vector3d;

/**
 * Keeps Pest near her base while the player is within 200 blocks of it.
 * When the player goes farther, follow mode takes over (policy).
 * If Pest dies / despawns, prefer respawn at bed when owner is near enough.
 */
public final class PestHomeAnchor {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private PestHomeAnchor() { }

    /** World position of bed center (respawn / stay target). */
    public static Vector3d bedAnchor() {
        if (!PestBaseState.isBedPlaced()) {
            if (!PestBaseState.isBuilt()) {
                return null;
            }
            return new Vector3d(
                    PestBaseState.originX() + PestBaseBuilder.SIZE / 2.0 + 0.5,
                    PestBaseState.originY() + 1.0,
                    PestBaseState.originZ() + PestBaseBuilder.SIZE / 2.0 + 0.5);
        }
        return new Vector3d(
                PestBaseState.bedX() + 0.5,
                PestBaseState.bedY() + 0.1,
                PestBaseState.bedZ() + 0.5);
    }

    public static Vector3d baseCenter() {
        if (!PestBaseState.isBuilt()) {
            return null;
        }
        return new Vector3d(
                PestBaseState.originX() + PestBaseBuilder.SIZE / 2.0,
                PestBaseState.originY(),
                PestBaseState.originZ() + PestBaseBuilder.SIZE / 2.0);
    }

    /**
     * If Pest is missing but base exists and owner is within stay range, respawn at bed.
     */
    public static void maybeRespawnAtBed(PlayerRef owner) {
        if (owner == null || !PestBaseState.isBuilt()) {
            return;
        }
        if (PestEntityTracker.entityId() != null && PestEntityTracker.hasPosition()) {
            return; // still alive/tracked
        }
        Vector3d bed = bedAnchor();
        if (bed == null) {
            return;
        }
        try {
            PestSpawner.spawnForPlayer(owner, true, "respawn at bed", msg -> {
                try {
                    owner.sendMessage(Message.raw("[Pest] Respawned at home bed."));
                } catch (Exception ignored) {
                }
            });
            LOGGER.atInfo().log("Pest respawn at bed scheduled");
        } catch (Exception e) {
            LOGGER.atWarning().log("respawn at bed failed: " + e);
        }
    }
}
