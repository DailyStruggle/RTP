package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class ParsePermissions {
  private static final Map<UUID, Map<String, Map.Entry<Long, Boolean>>> lastBool =
      new ConcurrentHashMap<>();
  private static final Map<UUID, Map<String, Map.Entry<Long, Integer>>> lastInt =
      new ConcurrentHashMap<>();

  public static boolean hasPerm(UUID senderId, String permissionPrefix, String... permissions) {
    RTPCommandSender sender = RTP.serverAccessor.getSender(senderId);
    if (sender == null) return false;
    return hasPerm(sender, permissionPrefix, permissions);
  }

  public static boolean hasPerm(
      RTPCommandSender sender, String permissionPrefix, String... permissions) {
    long current = System.currentTimeMillis();
    Map<String, Map.Entry<Long, Boolean>> entryMap = lastBool.get(sender.uuid());
    if (entryMap != null) {
      Map.Entry<Long, Boolean> entry = entryMap.get(permissionPrefix);
      if (entry != null) {
        long dt = current - entry.getKey();
        if (dt < 5000 && dt > 0) return entry.getValue();
      }
    } else {
      entryMap = new ConcurrentHashMap<>();
      lastBool.put(sender.uuid(), entryMap);
    }

    permissionPrefix = permissionPrefix.toLowerCase();
    boolean hasPerm = false;
    // Check sender effective permissions directly to avoid op-default short circuits.
    Set<String> effective = sender.getEffectivePermissions();
    outer:
    for (String perm : permissions) {
      String full = (permissionPrefix + perm).toLowerCase();
      for (String granted : effective) {
        if (granted.equalsIgnoreCase(full)) {
          hasPerm = true;
          break outer;
        }
      }
    }
    entryMap.put(permissionPrefix, new AbstractMap.SimpleEntry<>(current, hasPerm));
    return hasPerm;
  }

  public static int getInt(UUID senderId, String permissionPrefix) {
    RTPCommandSender sender = RTP.serverAccessor.getSender(senderId);
    if (sender == null) return -1;
    return getInt(sender, permissionPrefix);
  }

  public static int getInt(RTPCommandSender sender, String permissionPrefix) {
    long current = System.currentTimeMillis();
    Map<String, Map.Entry<Long, Integer>> entryMap = lastInt.get(sender.uuid());
    if (entryMap != null) {
      Map.Entry<Long, Integer> entry = entryMap.get(permissionPrefix);
      if (entry != null) {
        long dt = current - entry.getKey();
        if (dt < 5000 && dt > 0) return entry.getValue();
      }
    } else {
      entryMap = new ConcurrentHashMap<>();
      lastInt.put(sender.uuid(), entryMap);
    }

    Set<String> perms = sender.getEffectivePermissions();
    int res = -1;
    for (String perm : perms) {
      if (perm.startsWith(permissionPrefix)) {
        String[] val = perm.replace(permissionPrefix, "").split("\\.");
        if (val.length < 1 || val[0] == null || val[0].isEmpty()) continue;
        int number;
        try {
          number = Integer.parseInt(val[0]);
        } catch (NumberFormatException exception) {
          RTP.log(Level.WARNING, "[rtp] invalid permission: " + perm);
          continue;
        }
        entryMap.put(permissionPrefix, new AbstractMap.SimpleEntry<>(current, number));
        if( number < res || res < 0 ) res = number;
      }
    }
    return res;
  }
}
