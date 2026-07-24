package com.orffyrus.pest;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.npc.NPCPlugin;

import javax.annotation.Nonnull;

/**
 * Pest companion - independent of NpcAiStack (8765). Talks to Docker brain on 8766.
 */
public class PestPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Override with -Dpest.brain.url=ws://host:8766 if needed. */
    private static final String BRAIN_URL = System.getProperty(
            "pest.brain.url", "ws://127.0.0.1:8766");

    static BrainBridge BRIDGE;
    static PestChatListener CHAT;

    public PestPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        LOGGER.atInfo().log("Loaded " + this.getName() + " v" + this.getManifest().getVersion());
    }

    @Override
    protected void setup() {
        LOGGER.atInfo().log("Connecting Pest brain bridge -> " + BRAIN_URL);
        BRIDGE = new BrainBridge(BRAIN_URL);
        CHAT = new PestChatListener(BRIDGE);
        BRIDGE.setChatReplyHandler((text, actionName, reason) -> {
            if (CHAT != null) {
                CHAT.deliverReply(text);
            }
        });
        BRIDGE.connect();

        NPCPlugin.get().registerCoreComponentType("NoteNearbyThreat", NoteNearbyThreatActionBuilder::new);
        NPCPlugin.get().registerCoreComponentType("NoteNearbyPrey", NoteNearbyPreyActionBuilder::new);
        NPCPlugin.get().registerCoreComponentType("IsCompanion", IsCompanionSensorBuilder::new);
        NPCPlugin.get().registerCoreComponentType("IsManualMove", IsManualMoveSensorBuilder::new);
        NPCPlugin.get().registerCoreComponentType("IsHunting", IsHuntingSensorBuilder::new);
        NPCPlugin.get().registerCoreComponentType("IsLooting", IsLootingSensorBuilder::new);
        NPCPlugin.get().registerCoreComponentType("IsHostileSpecies", EntityFilterHostileSpeciesBuilder::new);
        NPCPlugin.get().registerCoreComponentType("IsHuntPrey", EntityFilterHuntPreyBuilder::new);
        NPCPlugin.get().registerCoreComponentType("IsOwner", EntityFilterIsOwnerBuilder::new);
        LOGGER.atInfo().log("Registered Pest NPC sensors/actions (hunt filter + base build + bed spawn)");

        this.getEventRegistry().registerAsyncGlobal(PlayerChatEvent.class, CHAT::onChat);
        LOGGER.atInfo().log("Registered PlayerChatEvent -> Pest");

        PestSpawner spawner = new PestSpawner();
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, spawner::onPlayerReady);
        LOGGER.atInfo().log("Registered PlayerReadyEvent -> Pest spawner");

        this.getCommandRegistry().registerCommand(new PestCommand());
        LOGGER.atInfo().log("Registered /pest");

        // Learn from player block edits (EventRegistry — may no-op for ECS events)
        PlayerDemoListener demos = new PlayerDemoListener(BRIDGE);
        try {
            this.getEventRegistry().registerGlobal(BreakBlockEvent.class, demos::onBreak);
            this.getEventRegistry().registerGlobal(PlaceBlockEvent.class, demos::onPlace);
            LOGGER.atInfo().log("Registered break/place EventRegistry listeners");
        } catch (Exception e) {
            LOGGER.atWarning().log("EventRegistry block events: " + e);
        }

        // Real place tracking: PlaceBlockEvent is ECS EntityEventSystem
        try {
            this.getEntityStoreRegistry().registerSystem(new PlaceBlockTrackSystem());
            LOGGER.atInfo().log("Registered PlaceBlockTrackSystem (ECS) — counts player builds for home");
        } catch (Exception e) {
            LOGGER.atWarning().log("Could not register PlaceBlockTrackSystem: " + e);
        }

        new BrainStateTicker(BRIDGE).start();
        LOGGER.atInfo().log("Pest brain state ticker started (2 Hz)");
    }

    @Override
    protected void shutdown() {
        LOGGER.atInfo().log("PestPlugin shutdown");
    }
}
