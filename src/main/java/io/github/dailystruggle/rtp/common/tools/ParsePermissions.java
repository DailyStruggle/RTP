package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public class ParsePermissions {
    static public boolean hasPerm(UUID senderId, String permissionPrefix, String... permissions) {
        RTPCommandSender sender = RTP.serverAccessor.getSender(senderId);
        if(sender == null) return false;
        return hasPerm(sender,permissionPrefix,permissions);
    }

    static public boolean hasPerm(RTPCommandSender sender, String permissionPrefix, String... permissions) {
        Set<String> perms = sender.getEffectivePermissions();
        boolean hasPerm = false;
        for(String perm : perms) {
            if(!perm.startsWith(permissionPrefix)) continue;
            if(perm.equalsIgnoreCase(permissionPrefix+"*")) return true;
            for(String permission : permissions) {
                if(perm.equalsIgnoreCase(permissionPrefix + permission.toLowerCase())) return true;
            }
        }
        return hasPerm;
    }

    static public int getInt(UUID senderId, String permissionPrefix) {
        RTPCommandSender sender = RTP.serverAccessor.getSender(senderId);
        if(sender == null) return -1;
        return getInt(sender,permissionPrefix);
    }

    static public int getInt(RTPCommandSender sender, String permissionPrefix) {
        Set<String> perms = sender.getEffectivePermissions();
        for(String perm : perms) {
            if(perm.startsWith(permissionPrefix)) {
                String[] val = perm.replace(permissionPrefix,"").split("\\.");
                if(val.length<1 || val[0]==null || val[0].equals("")) continue;
                int number;
                try {
                    number = Integer.parseInt(val[0]);
                } catch (NumberFormatException exception) {
                    RTP.log(Level.WARNING, "[rtp] invalid permission: " + perm);
                    continue;
                }
                return number;
            }
        }
        return -1;
    }
}
