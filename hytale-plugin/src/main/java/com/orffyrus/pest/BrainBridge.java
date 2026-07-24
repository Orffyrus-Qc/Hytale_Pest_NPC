package com.orffyrus.pest;

/**
 * WebSocket client for the independent Docker brain (hytale-pest-npc).
 * Protocol: hytale_ai_npc_v1 — see plugin-bridge/PROTOCOL.md
 * Default: ws://127.0.0.1:8766
 *
 * No Hytale imports (transport only).
 */

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class BrainBridge implements WebSocket.Listener {

    @FunctionalInterface
    public interface ActionHandler {
        void onAction(String name, String targetId, Double x, Double y, Double z,
                      String item, String text, String reason, String source);
    }

    @FunctionalInterface
    public interface ChatReplyHandler {
        void onChatReply(String text, String actionName, String reason);
    }

    private final URI uri;
    private final StringBuilder partial = new StringBuilder();
    private final AtomicReference<WebSocket> ws = new AtomicReference<>();
    private volatile ActionHandler actionHandler;
    private volatile ChatReplyHandler chatReplyHandler;
    private volatile boolean connected;
    private volatile String lastMode = "hybrid";

    public BrainBridge(String url) {
        this.uri = URI.create(url);
    }

    public void setActionHandler(ActionHandler handler) {
        this.actionHandler = handler;
    }

    public void setChatReplyHandler(ChatReplyHandler handler) {
        this.chatReplyHandler = handler;
    }

    public boolean isConnected() {
        return connected && ws.get() != null;
    }

    public String lastMode() {
        return lastMode;
    }

    public void connect() {
        HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(uri, this)
                .whenComplete((socket, err) -> {
                    if (err != null) {
                        connected = false;
                        scheduleReconnect();
                    } else {
                        ws.set(socket);
                        connected = true;
                        send("{\"type\":\"hello\",\"ts\":" + (System.currentTimeMillis() / 1000.0)
                                + ",\"payload\":{\"client\":\"PestAiNpc\",\"protocol\":\"hytale_ai_npc_v1\"}}");
                    }
                });
    }

    private void scheduleReconnect() {
        new java.util.Timer(true).schedule(new java.util.TimerTask() {
            public void run() {
                connect();
            }
        }, 5000);
    }

    private void send(String json) {
        WebSocket s = ws.get();
        if (s != null) {
            s.sendText(json, true);
        }
    }

    public void sendState(String stateJsonObject) {
        // stateJsonObject is the inner GameState object (already JSON)
        send("{\"type\":\"state\",\"ts\":" + (System.currentTimeMillis() / 1000.0)
                + ",\"payload\":{\"state\":" + stateJsonObject + "}}");
    }

    public void sendPlayerDemo(String action, String target, boolean success, String keysJsonArray) {
        send("{\"type\":\"player_action\",\"ts\":" + (System.currentTimeMillis() / 1000.0)
                + ",\"payload\":{\"demo\":{"
                + "\"action\":\"" + esc(action) + "\","
                + "\"target\":" + (target == null ? "null" : "\"" + esc(target) + "\"") + ","
                + "\"success\":" + success + ","
                + "\"keys\":" + (keysJsonArray == null ? "[]" : keysJsonArray)
                + "}}}");
    }

    public void sendActionResult(boolean ok, String diagnosis) {
        send("{\"type\":\"action_result\",\"ts\":" + (System.currentTimeMillis() / 1000.0)
                + ",\"payload\":{\"ok\":" + ok
                + ",\"diagnosis\":\"" + esc(diagnosis == null ? "" : diagnosis) + "\"}}");
    }

    public void sendChat(String text) {
        send("{\"type\":\"chat\",\"ts\":" + (System.currentTimeMillis() / 1000.0)
                + ",\"payload\":{\"text\":\"" + esc(text) + "\"}}");
    }

    public void sendMode(String mode) {
        lastMode = mode;
        send("{\"type\":\"mode\",\"ts\":" + (System.currentTimeMillis() / 1000.0)
                + ",\"payload\":{\"mode\":\"" + esc(mode) + "\"}}");
    }

    public void requestStatus() {
        send("{\"type\":\"status\",\"ts\":" + (System.currentTimeMillis() / 1000.0)
                + ",\"payload\":{}}");
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        connected = true;
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String msg = partial.toString();
            partial.setLength(0);
            handleMessage(msg);
        }
        socket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        connected = false;
        ws.set(null);
        scheduleReconnect();
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        connected = false;
    }

    private void handleMessage(String json) {
        String type = extract(json, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "hello_ack" -> { /* ready */ }
            case "action" -> dispatchAction(json);
            case "chat_reply" -> dispatchChatReply(json);
            case "status" -> {
                String mode = extractNested(json, "mode");
                if (mode != null && !mode.isBlank()) {
                    lastMode = mode;
                }
            }
            case "pong", "error" -> { /* ignore / log elsewhere */ }
            default -> { }
        }
    }

    private void dispatchAction(String json) {
        ActionHandler h = actionHandler;
        if (h == null) {
            return;
        }
        // payload.action.{name,target_id,target_pos,item,text,reason,source}
        String name = extractDeep(json, "name");
        if (name == null || name.isBlank()) {
            // try inside "action" object only
            name = extractFromAction(json, "name");
        }
        String targetId = extractFromAction(json, "target_id");
        String item = extractFromAction(json, "item");
        String text = extractFromAction(json, "text");
        String reason = extractFromAction(json, "reason");
        String source = extractFromAction(json, "source");
        Double x = extractDoubleFromPos(json, "x");
        Double y = extractDoubleFromPos(json, "y");
        Double z = extractDoubleFromPos(json, "z");
        if (name != null) {
            h.onAction(name, targetId, x, y, z, item, text, reason, source);
        }
    }

    private void dispatchChatReply(String json) {
        ChatReplyHandler h = chatReplyHandler;
        if (h == null) {
            return;
        }
        // Prefer payload.text — action.text is often the same field and can be
        // the first "text" key if field order changes, so read payload first.
        String text = extractFromPayload(json, "text");
        if (text == null) {
            text = extract(json, "text");
        }
        String actionName = extractFromAction(json, "name");
        String reason = extractFromAction(json, "reason");
        if (text != null) {
            h.onChatReply(text, actionName, reason);
        }
    }

    /** Extract a string field from the top-level "payload" object only. */
    private static String extractFromPayload(String json, String key) {
        int payloadIdx = json.indexOf("\"payload\"");
        if (payloadIdx < 0) {
            return null;
        }
        String sub = json.substring(payloadIdx);
        int brace = sub.indexOf('{');
        if (brace < 0) {
            return null;
        }
        // Only search until the nested "action" object so we don't pick action.text
        String payloadBody = sub.substring(brace);
        int actionIdx = payloadBody.indexOf("\"action\"");
        if (actionIdx > 0) {
            payloadBody = payloadBody.substring(0, actionIdx);
        }
        return extract(payloadBody, key);
    }

    private static String extractFromAction(String json, String key) {
        // Prefer value after "action":{ ... "key":
        int actionIdx = json.indexOf("\"action\"");
        if (actionIdx < 0) {
            return extract(json, key);
        }
        String sub = json.substring(actionIdx);
        // If action is a nested object, extract key from there first
        int brace = sub.indexOf('{');
        if (brace >= 0) {
            return extract(sub.substring(brace), key);
        }
        return extract(json, key);
    }

    private static Double extractDoubleFromPos(String json, String axis) {
        int posIdx = json.indexOf("\"target_pos\"");
        if (posIdx < 0) {
            return null;
        }
        String sub = json.substring(posIdx);
        int brace = sub.indexOf('{');
        if (brace < 0) {
            return null;
        }
        String val = extract(sub.substring(brace), axis);
        if (val == null || val.isBlank() || "null".equals(val)) {
            return null;
        }
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String extractNested(String json, String key) {
        return extract(json, key);
    }

    private static String extractDeep(String json, String key) {
        return extract(json, key);
    }

    /** Minimal JSON string/number extractor (no dependency). */
    static String extract(String json, String key) {
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i + pat.length());
        if (colon < 0) {
            return null;
        }
        int j = colon + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) {
            j++;
        }
        if (j >= json.length()) {
            return null;
        }
        char c = json.charAt(j);
        if (c == '"') {
            StringBuilder sb = new StringBuilder();
            for (int k = j + 1; k < json.length(); k++) {
                char ch = json.charAt(k);
                if (ch == '\\' && k + 1 < json.length()) {
                    sb.append(json.charAt(k + 1));
                    k++;
                    continue;
                }
                if (ch == '"') {
                    return sb.toString();
                }
                sb.append(ch);
            }
            return null;
        }
        if (c == 'n') {
            return null; // null
        }
        if (c == 't' || c == 'f') {
            // boolean
            if (json.startsWith("true", j)) {
                return "true";
            }
            if (json.startsWith("false", j)) {
                return "false";
            }
        }
        int end = j;
        while (end < json.length()) {
            char ch = json.charAt(end);
            if (ch == ',' || ch == '}' || ch == ']' || Character.isWhitespace(ch)) {
                break;
            }
            end++;
        }
        return json.substring(j, end);
    }

    static String esc(String v) {
        if (v == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
