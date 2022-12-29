package commonTestImpl.substitutions;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public class TestRTPCommandSender implements RTPCommandSender {
    private static final UUID id = UUID.randomUUID();

    @Override
    public UUID uuid() {
        return id;
    }

    @Override
    public boolean hasPermission(String permission) {
        return true;
    }

    @Override
    public void sendMessage(String message) {
        RTP.log(Level.INFO, "received: " + message);
    }

    @Override
    public long cooldown() {
        return 0;
    }

    @Override
    public long delay() {
        return 0;
    }

    @Override
    public String name() {
        return "console";
    }

    @Override
    public Set<String> getEffectivePermissions() {
        return new HashSet<>();
    }

    @Override
    public void performCommand(String command) {

    }

    @Override
    public RTPCommandSender clone() {
        try {
            return (RTPCommandSender) super.clone();
        } catch (CloneNotSupportedException e) {
            e.printStackTrace();
        }
        return null;
    }
}
