package com.orffyrus.pest;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes chat that addresses "Pest" to the Docker brain, and records demos.
 */
public class PestChatListener {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Set<String> EXIT_WORDS = Set.of("bye", "goodbye", "exit", "leave", "stop");
    private static final long CONVERSATION_TIMEOUT_MILLIS = 5 * 60 * 1000L;

    private final BrainBridge bridge;
    private final Map<UUID, Long> active = new ConcurrentHashMap<>();

    public PestChatListener(BrainBridge bridge) {
        this.bridge = bridge;
    }

    public CompletableFuture<PlayerChatEvent> onChat(CompletableFuture<PlayerChatEvent> future) {
        return future.thenApply(this::handle);
    }

    private PlayerChatEvent handle(PlayerChatEvent event) {
        if (event.isCancelled()) {
            return event;
        }
        PlayerRef sender = event.getSender();
        if (sender == null) {
            return event;
        }
        String content = event.getContent();
        if (content == null || content.isBlank()) {
            return event;
        }

        UUID playerUuid = sender.getUuid();
        boolean addressing = PestChatRouter.addressesPest(content);
        Long last = active.get(playerUuid);
        boolean inConvo = last != null
                && System.currentTimeMillis() - last < CONVERSATION_TIMEOUT_MILLIS;

        if (!addressing && !inConvo) {
            // Still learn: free-form player chat as soft demo of "chat" action
            return event;
        }

        event.setCancelled(true);
        String text = addressing ? PestChatRouter.stripAddress(content) : content.trim();

        if (EXIT_WORDS.contains(text.toLowerCase(Locale.ROOT))) {
            active.remove(playerUuid);
            sender.sendMessage(Message.raw("(You end the conversation with Pest)"));
            return event;
        }

        // Mark companion + conversation
        CompanionState.markCompanion(PestSpawner.PEST_ROLE, playerUuid);
        active.put(playerUuid, System.currentTimeMillis());

        // Game questions → Docker brain + Hytale wiki (do not steal for hunt/build intents)
        if (isKnowledgeQuestion(text)) {
            bridge.sendPlayerDemo("chat", null, true, "[]");
            sender.sendMessage(Message.raw("(Pest is checking the Hytale wiki…)"));
            bridge.sendChat(text + " | situation: wiki_question");
            LOGGER.atFine().log("Pest wiki Q -> brain: " + text);
            return event;
        }

        // Local intents (work even if brain is slow / offline)
        String local = handleLocalIntent(text, sender);
        if (local != null) {
            bridge.sendPlayerDemo("chat", null, true, "[]");
            sender.sendMessage(Message.raw("[Pest] " + local));
            return event;
        }

        // Manual move escape hatch (works offline from LLM)
        ManualMoveState.Kind move = parseManualMove(text);
        if (move != null) {
            ManualMoveState.request(PestSpawner.PEST_ROLE, move);
            bridge.sendPlayerDemo("explore", null, true, "[\"W\"]");
            sender.sendMessage(Message.raw("(You tell Pest to " + move.name().toLowerCase(Locale.ROOT) + ".)"));
            return event;
        }

        // Record chat as player demonstration of communication
        bridge.sendPlayerDemo("chat", null, true, "[]");

        String threat = ThreatMemory.describe(PestSpawner.PEST_ROLE);
        String base = PestBaseState.describe();
        String situation = "You are Pest, the player's adventure companion. "
                + "Base building: closed wood room + door, then bed inside for spawn. "
                + "Base status: " + base + ".";
        if (!threat.isEmpty()) {
            situation = situation + " " + threat;
        }
        final String ask = text + " | situation: " + situation;

        sender.sendMessage(Message.raw("(Pest is thinking…)"));
        bridge.sendChat(ask);
        LOGGER.atFine().log("Pest chat -> brain: " + text);
        return event;
    }

    /**
     * True for game-knowledge questions that should hit the Hytale wiki,
     * not local action shortcuts (e.g. "what can I hunt?" must not start a hunt).
     */
    static boolean isKnowledgeQuestion(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String c = content.toLowerCase(Locale.ROOT).trim();
        // Pure chit-chat — do not send to wiki (prevents random unrelated pages)
        if (c.equals("ok") || c.equals("okay") || c.equals("k") || c.equals("kk")
                || c.equals("thanks") || c.equals("thank you") || c.equals("thx") || c.equals("ty")
                || c.equals("cool") || c.equals("nice") || c.equals("great") || c.equals("sure")
                || c.equals("yes") || c.equals("yeah") || c.equals("yep") || c.equals("no")
                || c.equals("got it") || c.equals("gotcha") || c.equals("lol")) {
            return false;
        }
        if (c.contains("?")) {
            return true;
        }
        if (c.startsWith("what ") || c.startsWith("what's ") || c.startsWith("whats ")
                || c.startsWith("which ") || c.startsWith("who ") || c.startsWith("where ")
                || c.startsWith("when ") || c.startsWith("why ") || c.startsWith("how ")
                || c.startsWith("can you tell") || c.startsWith("tell me ")
                || c.startsWith("explain ") || c.startsWith("describe ")
                || c.startsWith("do you know")) {
            // Companion status is local, not wiki
            if (c.contains("what are you doing") || c.contains("where is base")
                    || c.contains("where is my base") || c.contains("where is your base")
                    || c.equals("status") || c.startsWith("status ")) {
                return false;
            }
            // "tell me more" is a wiki follow-up, not a command
            return true;
        }
        return false;
    }

    /**
     * Instant companion commands without waiting on the LLM.
     * @return reply text, or null to fall through to brain/chat
     */
    static String handleLocalIntent(String content, PlayerRef sender) {
        if (content == null || content.isBlank()) {
            return null;
        }
        // Never treat knowledge questions as action commands
        if (isKnowledgeQuestion(content)) {
            return null;
        }
        String c = content.toLowerCase(Locale.ROOT).trim();

        if (containsAny(c, "build base", "build a base", "build house", "make a base",
                "make base", "shelter", "build room", "build home", "set up camp",
                "make camp", "build camp")) {
            CompanionState.markCompanion(PestSpawner.PEST_ROLE, sender.getUuid());
            // Manual/chat force bypasses the 15‑minute auto-home wait
            PestBaseBuilder.scheduleBuildNearpest(true);
            return "On it now (manual) — larger wood home, door in doorway, bed inside. "
                    + "Auto-home still waits 15 min if you don't ask.";
        }
        if (containsAny(c, "rebuild", "new base", "another base")) {
            CompanionState.markCompanion(PestSpawner.PEST_ROLE, sender.getUuid());
            PestBaseState.reset();
            PestBaseBuilder.scheduleBuildNearpest(true);
            return "Rebuilding a fresh home nearby (manual force).";
        }
        if (containsAny(c, "set spawn", "set bed", "place bed", "sleep here", "respawn here")) {
            if (PestBaseState.isBuilt()) {
                PestBedSpawn.scheduleSetSpawnAtBed(
                        PestBaseState.originX() + 2.5,
                        PestBaseState.originY(),
                        PestBaseState.originZ() + 2.5);
                return "Setting our spawn at the base bed (Pest Camp).";
            }
            return "No base yet — say \"build a base\" first, or use /pest base.";
        }
        if (containsAny(c, "hunt", "kill animals", "get hide", "find prey")) {
            PestHuntState.enable("chat hunt", 30_000L);
            PestLootState.enableAfterCombat();
            return "Hunting safe prey for hides/drops (not merchants).";
        }
        if (containsAny(c, "loot", "pick up", "pickup", "gather drops")) {
            PestLootState.enable("chat loot", 12_000L);
            ManualMoveState.request(PestSpawner.PEST_ROLE, ManualMoveState.Kind.FORWARD);
            return "Looting nearby drops.";
        }
        if (containsAny(c, "status", "what are you doing", "inventory", "where is base")) {
            return "Base: " + PestBaseState.describe()
                    + " | hunt=" + PestHuntState.isActive()
                    + " | inv=" + shortInv();
        }
        if (containsAny(c, "follow me", "come here", "stay close", "follow")) {
            ManualMoveState.request(PestSpawner.PEST_ROLE, ManualMoveState.Kind.FORWARD);
            return "Staying with you.";
        }
        if (containsAny(c, "stop hunting", "stop fight", "stand down", "peace")) {
            PestHuntState.clear();
            ThreatMemory.clear(PestSpawner.PEST_ROLE);
            return "Standing down — no hunt lock.";
        }
        return null;
    }

    private static String shortInv() {
        String inv = PestEntityTracker.inventorySummary();
        if (inv == null || inv.isBlank()) {
            return "?";
        }
        return inv.length() > 80 ? inv.substring(0, 80) + "…" : inv;
    }

    private static boolean containsAny(String hay, String... needles) {
        for (String n : needles) {
            if (hay.contains(n)) {
                return true;
            }
        }
        return false;
    }

    static ManualMoveState.Kind parseManualMove(String content) {
        if (content == null) {
            return null;
        }
        String c = content.toLowerCase(Locale.ROOT);
        // Don't treat "build a base" as walk-forward
        if (c.contains("base") || c.contains("build") || c.contains("hunt")) {
            return null;
        }
        boolean forward = c.contains("forward") || c.contains("walk") || c.contains("go ahead");
        boolean jump = c.contains("jump") || c.contains("hop");
        if (forward && jump) {
            return ManualMoveState.Kind.FORWARD_JUMP;
        }
        if (jump) {
            return ManualMoveState.Kind.JUMP;
        }
        if (forward) {
            return ManualMoveState.Kind.FORWARD;
        }
        return null;
    }

    /** Deliver brain chat reply to the most recent speakers still in conversation. */
    public void deliverReply(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> e : active.entrySet()) {
            if (now - e.getValue() > CONVERSATION_TIMEOUT_MILLIS) {
                continue;
            }
            PlayerRef p = Universe.get().getPlayer(e.getKey());
            if (p != null) {
                p.sendMessage(Message.raw("[Pest] " + text));
            }
        }
    }
}
