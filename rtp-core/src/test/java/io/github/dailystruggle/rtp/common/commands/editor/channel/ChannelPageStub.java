package io.github.dailystruggle.rtp.common.commands.editor.channel;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Java stand-in for the page's {@code EditorChannelClient}: same envelope, same handshake, its own
 * RSA key. Verifies every plugin frame against the snapshot's plugin key, like the page does.
 */
public final class ChannelPageStub {

    private static final Gson GSON = new Gson();

    public final EditorKeys keys = EditorKeys.generate();
    public final List<JsonObject> received = new ArrayList<>();
    public final List<String> rejected = new ArrayList<>();
    private final InMemoryTransport end;
    private final String channelId;
    private final PublicKey pluginKey;
    public String challenge;
    public String state;
    public String nonce;
    public long seq;
    public long pluginSeq;
    public String hid = "stub1";

    public ChannelPageStub(InMemoryTransport end, String channelId, PublicKey pluginKey) {
        this.end = end;
        this.channelId = channelId;
        this.pluginKey = pluginKey;
        end.start(this::onFrame);
    }

    public String fingerprint() {
        return keys.fingerprint();
    }

    private void onFrame(String text) {
        JsonObject env = JsonParser.parseString(text).getAsJsonObject();
        String msg = env.get("msg").getAsString();
        byte[] sig = Base64.getDecoder().decode(env.get("signature").getAsString());
        if (!EditorKeys.verify(pluginKey, msg.getBytes(StandardCharsets.UTF_8), sig)) {
            rejected.add("bad signature");
            return;
        }
        JsonObject m = JsonParser.parseString(msg).getAsJsonObject();
        long s = m.get("seq").getAsLong();
        if (!channelId.equals(m.get("channel").getAsString()) || s <= pluginSeq) {
            rejected.add("foreign or replayed");
            return;
        }
        pluginSeq = s;
        if (m.has("to") && !fingerprint().equals(m.get("to").getAsString())) return;
        received.add(m);
        if ("hello-reply".equals(m.get("type").getAsString())) {
            if (m.has("hid") && !hid.equals(m.get("hid").getAsString())) return;
            if (m.has("challenge")) {
                challenge = m.get("challenge").getAsString();
                seq = 0;
            }
            state = m.get("state").getAsString();
            nonce = m.has("nonce") ? m.get("nonce").getAsString() : null;
        }
    }

    public void hello() {
        JsonObject m = new JsonObject();
        m.addProperty("type", "hello");
        m.addProperty("channel", channelId);
        m.addProperty("from", fingerprint());
        m.addProperty("hid", hid);
        m.addProperty("publicKey", keys.publicKeyBase64());
        sendSigned(m.toString(), keys);
    }

    /** {type, ...fields} with the header the page adds; returns the signed inner message. */
    public String send(String type, Map<String, ?> fields) {
        JsonObject m = fields == null ? new JsonObject() : GSON.toJsonTree(fields).getAsJsonObject();
        m.addProperty("type", type);
        m.addProperty("channel", channelId);
        m.addProperty("seq", ++seq);
        m.addProperty("from", fingerprint());
        m.addProperty("challenge", challenge);
        String msg = m.toString();
        sendSigned(msg, keys);
        return msg;
    }

    public void sendSigned(String msg, EditorKeys signer) {
        end.send(envelope(msg, Base64.getEncoder().encodeToString(signer.sign(msg.getBytes(StandardCharsets.UTF_8)))));
    }

    public void sendRaw(String frame) {
        end.send(frame);
    }

    public static String envelope(String msg, String signatureB64) {
        JsonObject env = new JsonObject();
        env.addProperty("msg", msg);
        env.addProperty("signature", signatureB64);
        return env.toString();
    }

    public JsonObject last(String type) {
        for (int i = received.size() - 1; i >= 0; i--) {
            if (type.equals(received.get(i).get("type").getAsString())) return received.get(i);
        }
        return null;
    }

    public long count(String type) {
        return received.stream().filter(m -> type.equals(m.get("type").getAsString())).count();
    }
}
