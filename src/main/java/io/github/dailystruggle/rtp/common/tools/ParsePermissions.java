package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class ParsePermissions {
    private static final Map<UUID,Map<String,Map.Entry<Long,Boolean>>> lastBool = new ConcurrentHashMap<>();
    private static final Map<UUID,Map<String,Map.Entry<Long,Integer>>> lastInt = new ConcurrentHashMap<>();

    static public boolean hasPerm(UUID senderId, String permissionPrefix, String... permissions) {
        RTPCommandSender sender = RTP.serverAccessor.getSender(senderId);
        if(sender == null) return false;
        return hasPerm(sender,permissionPrefix,permissions);
    }

    static public boolean hasPerm(RTPCommandSender sender, String permissionPrefix, String... permissions) {
        long current = System.currentTimeMillis();
        Map<String, Map.Entry<Long, Boolean>> entryMap = lastBool.get(sender.uuid());
        if(entryMap!=null) {
            Map.Entry<Long, Boolean> entry = entryMap.get(permissionPrefix);
            if(entry !=null) {
                long dt = current-entry.getKey();
                if(dt<5000 && dt >0) return entry.getValue();
            }
        }
        else {
            entryMap = new ConcurrentHashMap<>();
            lastBool.put(sender.uuid(), entryMap);
        }

        permissionPrefix = permissionPrefix.toLowerCase();
        String finalPermissionPrefix = permissionPrefix;
        Set<String> perms = Arrays.stream(permissions).map(s -> finalPermissionPrefix+s.toLowerCase()).collect(Collectors.toSet());
        boolean hasPerm = false;
        if(sender.hasPermission(permissionPrefix+"*")) hasPerm = true;
        else {
            for (String perm : perms) {
                perm = perm.toLowerCase();
                if (sender.hasPermission(perm)) {
                    hasPerm = true;
                    break;
                }
            }
        }
        entryMap.put(permissionPrefix,new AbstractMap.SimpleEntry<>(current,hasPerm));
        return hasPerm;
    }

    static public int getInt(UUID senderId, String permissionPrefix) {
        RTPCommandSender sender = RTP.serverAccessor.getSender(senderId);
        if(sender == null) return -1;
        return getInt(sender,permissionPrefix);
    }

    static public int getInt(RTPCommandSender sender, String permissionPrefix) {
        long current = System.currentTimeMillis();
        Map<String, Map.Entry<Long, Integer>> entryMap = lastInt.get(sender.uuid());
        if(entryMap!=null) {
            Map.Entry<Long, Integer> entry = entryMap.get(permissionPrefix);
            if(entry !=null) {
                long dt = current-entry.getKey();
                if(dt<5000 && dt >0) return entry.getValue();
            }
        }
        else {
            entryMap = new ConcurrentHashMap<>();
            lastInt.put(sender.uuid(), entryMap);
        }

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
                entryMap.put(permissionPrefix, new AbstractMap.SimpleEntry<>(current,number));
                return number;
            }
        }
        return -1;
    }
}
