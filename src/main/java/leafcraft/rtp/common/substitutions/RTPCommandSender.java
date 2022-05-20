package leafcraft.rtp.common.substitutions;

public interface RTPCommandSender {
    boolean hasPermission(String permission);
    void sendMessage(String message);
}
