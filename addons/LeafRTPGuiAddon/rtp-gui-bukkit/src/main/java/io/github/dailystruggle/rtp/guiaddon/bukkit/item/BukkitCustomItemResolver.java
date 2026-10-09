package io.github.dailystruggle.rtp.guiaddon.bukkit.item;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.search.FuzzySearchEngine;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves menu icon specifications to Bukkit {@link ItemStack}s.
 * Supports third-party item plugins (ItemsAdder, Oraxen, Nexo, HeadDatabase),
 * player skins, base64 skull textures, and CustomModelData via reflection
 * and soft-dependencies (ADR-026).
 */
public final class BukkitCustomItemResolver {

  private static final Set<String> WARNED_PLUGINS = ConcurrentHashMap.newKeySet();
  private static final Map<String, Material> MATERIAL_MAP;

  static {
    Map<String, Material> map = new LinkedHashMap<>();
    for (Material m : Material.values()) {
      map.put(m.name(), m);
    }
    MATERIAL_MAP = Collections.unmodifiableMap(map);
  }

  private BukkitCustomItemResolver() {}

  /**
   * Checks if the given specification string represents a custom item or prefix route.
   */
  public static boolean isCustomItem(String spec) {
    if (spec == null || spec.isBlank()) return false;
    String s = spec.trim().toLowerCase(Locale.ROOT);
    return s.startsWith("ia:")
        || s.startsWith("itemsadder:")
        || s.startsWith("oraxen:")
        || s.startsWith("nexo:")
        || s.startsWith("hdb:")
        || s.startsWith("headdatabase:")
        || s.startsWith("head:")
        || s.startsWith("playerhead:")
        || s.startsWith("base64:")
        || s.contains("#")
        || (s.contains(":") && !s.startsWith("minecraft:"));
  }

  /**
   * Resolves an item specification string into an {@link ItemStack}.
   * If resolution fails or the requested provider is missing, falls back to {@code fallback}.
   *
   * @param spec item string specification
   * @param fallback default material fallback if resolution fails
   * @return resolved ItemStack, or fallback ItemStack
   */
  public static ItemStack resolve(String spec, Material fallback) {
    if (fallback == null) fallback = Material.COMPASS;
    if (spec == null || spec.isBlank()) {
      return createSafeItem(fallback);
    }

    String trimmed = spec.trim();
    String lower = trimmed.toLowerCase(Locale.ROOT);

    try {
      if (lower.startsWith("ia:") || lower.startsWith("itemsadder:")) {
        int idx = trimmed.indexOf(':');
        String id = trimmed.substring(idx + 1).trim();
        ItemStack item = resolveItemsAdder(id);
        if (item != null) return item;
      } else if (lower.startsWith("oraxen:")) {
        String id = trimmed.substring("oraxen:".length()).trim();
        ItemStack item = resolveOraxen(id);
        if (item != null) return item;
      } else if (lower.startsWith("nexo:")) {
        String id = trimmed.substring("nexo:".length()).trim();
        ItemStack item = resolveNexo(id);
        if (item != null) return item;
      } else if (lower.startsWith("hdb:") || lower.startsWith("headdatabase:")) {
        int idx = trimmed.indexOf(':');
        String id = trimmed.substring(idx + 1).trim();
        ItemStack item = resolveHeadDatabase(id);
        if (item != null) return item;
      } else if (lower.startsWith("head:") || lower.startsWith("playerhead:")) {
        int idx = trimmed.indexOf(':');
        String owner = trimmed.substring(idx + 1).trim();
        ItemStack item = resolvePlayerHead(owner);
        if (item != null) return item;
      } else if (lower.startsWith("base64:")) {
        String base64 = trimmed.substring("base64:".length()).trim();
        ItemStack item = resolveBase64Head(base64);
        if (item != null) return item;
      } else if (trimmed.contains("#") || (trimmed.contains(":") && !lower.startsWith("minecraft:"))) {
        ItemStack item = resolveCustomModelData(trimmed);
        if (item != null) return item;
      }
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[RTP-GUI] Error resolving item spec '" + spec + "': " + t.getMessage());
    }

    // Vanilla fallback
    String vanillaName = trimmed;
    if (lower.startsWith("minecraft:")) {
      vanillaName = trimmed.substring("minecraft:".length());
    }
    Material matched = Material.matchMaterial(vanillaName.toUpperCase(Locale.ROOT));
    if (matched != null) {
      return createSafeItem(matched);
    }

    var lookup = FuzzySearchEngine.resolveCandidate(vanillaName, MATERIAL_MAP);
    if (lookup.isExact() && lookup.match() != null) {
      return createSafeItem(lookup.match());
    } else if (lookup.isPerceptible() && lookup.match() != null) {
      RTP.log(
          Level.WARNING,
          "[RTP-GUI] Menu icon material '"
              + vanillaName
              + "' was not recognized, but closely matches '"
              + lookup.matchedKey()
              + "'. Autocorrecting to '"
              + lookup.matchedKey()
              + "'.");
      return createSafeItem(lookup.match());
    } else {
      RTP.log(
          Level.WARNING,
          "[RTP-GUI] Unrecognized item material '"
              + vanillaName
              + "', falling back to "
              + fallback.name());
    }

    return createSafeItem(fallback);
  }

  private static ItemStack createSafeItem(Material material) {
    try {
      return new ItemStack(material);
    } catch (Throwable t) {
      return new ItemStack(Material.COMPASS);
    }
  }

  private static boolean isPluginAvailable(String pluginName) {
    try {
      if (Bukkit.getServer() == null || Bukkit.getPluginManager() == null) {
        return false;
      }
      return Bukkit.getPluginManager().isPluginEnabled(pluginName);
    } catch (Throwable ignored) {
      return false;
    }
  }

  private static void warnOnce(String pluginName) {
    if (WARNED_PLUGINS.add(pluginName)) {
      RTP.log(Level.INFO, "[RTP-GUI] " + pluginName + " is not installed or enabled; menu icons using this prefix will use fallback materials.");
    }
  }

  private static ItemStack resolveItemsAdder(String id) {
    if (!isPluginAvailable("ItemsAdder")) {
      warnOnce("ItemsAdder");
      return null;
    }
    try {
      Class<?> clazz = Class.forName("dev.lone.itemsadder.api.CustomStack");
      Method getInstance = clazz.getMethod("getInstance", String.class);
      Object customStack = getInstance.invoke(null, id);
      if (customStack != null) {
        Method getItemStack = clazz.getMethod("getItemStack");
        return (ItemStack) getItemStack.invoke(customStack);
      }
    } catch (Throwable t) {
      RTP.log(Level.FINE, "[RTP-GUI] ItemsAdder failed to resolve item '" + id + "': " + t.getMessage());
    }
    return null;
  }

  private static ItemStack resolveOraxen(String id) {
    if (!isPluginAvailable("Oraxen")) {
      warnOnce("Oraxen");
      return null;
    }
    try {
      Class<?> clazz = Class.forName("io.th0rgal.oraxen.api.OraxenItems");
      Method getItemById = clazz.getMethod("getItemById", String.class);
      Object result = getItemById.invoke(null, id);
      if (result instanceof ItemStack is) {
        return is;
      } else if (result != null) {
        Method build = result.getClass().getMethod("build");
        return (ItemStack) build.invoke(result);
      }
    } catch (Throwable t) {
      RTP.log(Level.FINE, "[RTP-GUI] Oraxen failed to resolve item '" + id + "': " + t.getMessage());
    }
    return null;
  }

  private static ItemStack resolveNexo(String id) {
    if (!isPluginAvailable("Nexo")) {
      warnOnce("Nexo");
      return null;
    }
    try {
      Class<?> clazz = Class.forName("com.nexomc.nexo.api.NexoItems");
      Method itemFromId = clazz.getMethod("itemFromId", String.class);
      Object result = itemFromId.invoke(null, id);
      if (result instanceof ItemStack is) {
        return is;
      } else if (result != null) {
        Method build = result.getClass().getMethod("build");
        return (ItemStack) build.invoke(result);
      }
    } catch (Throwable t) {
      RTP.log(Level.FINE, "[RTP-GUI] Nexo failed to resolve item '" + id + "': " + t.getMessage());
    }
    return null;
  }

  private static ItemStack resolveHeadDatabase(String id) {
    if (!isPluginAvailable("HeadDatabase")) {
      warnOnce("HeadDatabase");
      return null;
    }
    try {
      Class<?> clazz = Class.forName("me.arcaniax.hdb.api.HeadDatabaseAPI");
      Object api = clazz.getDeclaredConstructor().newInstance();
      Method getItemHead = clazz.getMethod("getItemHead", String.class);
      return (ItemStack) getItemHead.invoke(api, id);
    } catch (Throwable t) {
      RTP.log(Level.FINE, "[RTP-GUI] HeadDatabase failed to resolve head '" + id + "': " + t.getMessage());
    }
    return null;
  }

  private static ItemStack resolvePlayerHead(String owner) {
    Material headMat = Material.matchMaterial("PLAYER_HEAD");
    if (headMat == null) headMat = Material.matchMaterial("SKULL_ITEM");
    if (headMat == null) return null;

    ItemStack item = createSafeItem(headMat);
    try {
      if (item.getItemMeta() instanceof SkullMeta skullMeta) {
        skullMeta.setOwner(owner);
        item.setItemMeta(skullMeta);
      }
    } catch (Throwable t) {
      RTP.log(Level.FINE, "[RTP-GUI] Failed to set head owner '" + owner + "': " + t.getMessage());
    }
    return item;
  }

  private static ItemStack resolveBase64Head(String base64) {
    Material headMat = Material.matchMaterial("PLAYER_HEAD");
    if (headMat == null) headMat = Material.matchMaterial("SKULL_ITEM");
    if (headMat == null) return null;

    ItemStack item = createSafeItem(headMat);
    try {
      ItemMeta meta = item.getItemMeta();
      if (meta instanceof SkullMeta skullMeta) {
        applyBase64Texture(skullMeta, base64);
        item.setItemMeta(skullMeta);
      }
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[RTP-GUI] Failed to apply base64 texture to head: " + t.getMessage(), t);
    }
    return item;
  }

  static void applyBase64Texture(SkullMeta meta, String base64) {
    try {
      String json = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
      Matcher m = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
      if (m.find()) {
        if (applyProfileApi(meta, m.group(1))) {
          return;
        }
      }
    } catch (Throwable t) {
      RTP.log(Level.FINE, "[RTP-GUI] Base64 JSON decoding / profile lookup failed: " + t.getMessage());
    }

    // Fall back to legacy reflection for pre-1.18 servers
    try {
      Class<?> profileClass = Class.forName("com.mojang.authlib.GameProfile");
      Constructor<?> constructor = profileClass.getConstructor(UUID.class, String.class);
      Object profile = constructor.newInstance(UUID.randomUUID(), "");

      Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
      Constructor<?> propConstructor = propertyClass.getConstructor(String.class, String.class);
      Object property = propConstructor.newInstance("textures", base64);

      Method getProperties = profileClass.getMethod("getProperties");
      Object propertyMap = getProperties.invoke(profile);
      Method put = propertyMap.getClass().getMethod("put", Object.class, Object.class);
      put.invoke(propertyMap, "textures", property);

      Field profileField = null;
      Class<?> c = meta.getClass();
      while (c != null && profileField == null) {
        try {
          profileField = c.getDeclaredField("profile");
        } catch (NoSuchFieldException e) {
          c = c.getSuperclass();
        }
      }
      if (profileField != null) {
        profileField.setAccessible(true);
        profileField.set(meta, profile);
      } else {
        RTP.log(Level.WARNING, "[RTP-GUI] Failed to find profile field on SkullMeta: " + meta.getClass().getName());
      }
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[RTP-GUI] Failed to apply base64 texture to head via reflection: " + t.getMessage(), t);
    }
  }

  static boolean applyProfileApi(SkullMeta meta, String textureUrl) {
    try {
      PlayerProfile pp = Bukkit.createPlayerProfile(UUID.randomUUID(), "rtp");
      PlayerTextures textures = pp.getTextures();
      textures.setSkin(URI.create(textureUrl).toURL());
      pp.setTextures(textures);
      try {
        meta.setOwnerProfile(pp);
        return true;
      } catch (NoSuchMethodError e) {
        Method m = meta.getClass().getMethod("setPlayerProfile", pp.getClass());
        m.invoke(meta, pp);
        return true;
      }
    } catch (Throwable t) {
      RTP.log(Level.FINE, "[RTP-GUI] Public profile API failed: " + t.getMessage());
      return false;
    }
  }

  private static ItemStack resolveCustomModelData(String spec) {
    char delimiter = spec.contains("#") ? '#' : ':';
    int idx = spec.indexOf(delimiter);
    if (idx <= 0 || idx >= spec.length() - 1) return null;

    String matPart = spec.substring(0, idx).trim();
    String cmdPart = spec.substring(idx + 1).trim();

    int cmd;
    try {
      cmd = Integer.parseInt(cmdPart);
    } catch (NumberFormatException e) {
      return null;
    }

    Material mat = Material.matchMaterial(matPart.toUpperCase(Locale.ROOT));
    if (mat == null) return null;

    ItemStack item = createSafeItem(mat);
    try {
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
        meta.setCustomModelData(cmd);
        item.setItemMeta(meta);
      }
    } catch (Throwable t) {
      RTP.log(Level.FINE, "[RTP-GUI] Failed to set CustomModelData on item: " + t.getMessage());
    }
    return item;
  }
}
