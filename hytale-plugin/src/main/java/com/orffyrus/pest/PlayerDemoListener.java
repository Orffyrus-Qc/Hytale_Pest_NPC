package com.orffyrus.pest;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import org.joml.Vector3i;

/**
 * Learns from player world edits + tracks places for Pest home trigger
 * (&gt;20 places in 300s → build 100 blocks from player base).
 */
public class PlayerDemoListener {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final BrainBridge bridge;

    public PlayerDemoListener(BrainBridge bridge) {
        this.bridge = bridge;
    }

    public void onBreak(BreakBlockEvent event) {
        try {
            if (bridge != null && bridge.isConnected()) {
                bridge.sendPlayerDemo("mine_block", "block", true, "[\"LMB\"]");
            }
        } catch (Exception e) {
            LOGGER.atFine().log("demo break: " + e);
        }
    }

    public void onPlace(PlaceBlockEvent event) {
        try {
            Vector3i target = event != null ? event.getTargetBlock() : null;
            if (target != null) {
                PlayerBuildTracker.recordPlace(target);
            } else {
                PlayerBuildTracker.recordPlace(
                        PestEntityTracker.hasPosition() ? PestEntityTracker.x() : 0,
                        PestEntityTracker.hasPosition() ? PestEntityTracker.y() : 64,
                        PestEntityTracker.hasPosition() ? PestEntityTracker.z() : 0);
            }
            if (bridge != null && bridge.isConnected()) {
                bridge.sendPlayerDemo("place_block", "block", true, "[\"RMB\"]");
            }
        } catch (Exception e) {
            LOGGER.atFine().log("demo place: " + e);
        }
    }
}
