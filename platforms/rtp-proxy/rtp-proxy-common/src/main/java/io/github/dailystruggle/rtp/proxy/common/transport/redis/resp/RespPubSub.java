package io.github.dailystruggle.rtp.proxy.common.transport.redis.resp;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles Redis pub/sub channel subscriptions over a dedicated connection.
 */
public abstract class RespPubSub implements Closeable {

    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private volatile RespConnection clientConnection;

    public abstract void onMessage(String channel, String message);

    public void onSubscribe(String channel, int subscribedChannels) {}

    public void onUnsubscribe(String channel, int subscribedChannels) {}

    /**
     * Subscribes to the given channels on this dedicated connection.
     * Blocks until connection is closed or unsubscribe is called.
     */
    @SuppressWarnings("unchecked")
    public void proceed(RespConnection connection, String... channels) throws IOException {
        this.clientConnection = connection;
        this.subscribed.set(true);

        String[] cmd = new String[channels.length + 1];
        cmd[0] = "SUBSCRIBE";
        System.arraycopy(channels, 0, cmd, 1, channels.length);

        connection.executeCommand(cmd);

        while (subscribed.get() && !connection.isBroken()) {
            Object reply;
            try {
                reply = connection.executeCommand();
            } catch (IOException e) {
                if (!subscribed.get()) break;
                throw e;
            }

            if (reply instanceof List<?> list && list.size() >= 3) {
                String type = RespProtocol.toUtf8((byte[]) list.get(0));
                String channel = RespProtocol.toUtf8((byte[]) list.get(1));

                if ("message".equalsIgnoreCase(type)) {
                    String msg = RespProtocol.toUtf8((byte[]) list.get(2));
                    try {
                        onMessage(channel, msg);
                    } catch (Throwable ignored) {}
                } else if ("subscribe".equalsIgnoreCase(type)) {
                    int count = (list.get(2) instanceof Long l) ? l.intValue() : 1;
                    onSubscribe(channel, count);
                } else if ("unsubscribe".equalsIgnoreCase(type)) {
                    int count = (list.get(2) instanceof Long l) ? l.intValue() : 0;
                    onUnsubscribe(channel, count);
                    if (count == 0) {
                        break;
                    }
                }
            }
        }
    }

    public void unsubscribe(String... channels) {
        subscribed.set(false);
        if (clientConnection != null) {
            try {
                if (channels == null || channels.length == 0) {
                    clientConnection.executeCommand("UNSUBSCRIBE");
                } else {
                    String[] cmd = new String[channels.length + 1];
                    cmd[0] = "UNSUBSCRIBE";
                    System.arraycopy(channels, 0, cmd, 1, channels.length);
                    clientConnection.executeCommand(cmd);
                }
            } catch (Exception ignored) {}
        }
    }

    public boolean isSubscribed() {
        return subscribed.get();
    }

    @Override
    public void close() {
        unsubscribe();
        if (clientConnection != null) {
            clientConnection.close();
        }
    }
}
