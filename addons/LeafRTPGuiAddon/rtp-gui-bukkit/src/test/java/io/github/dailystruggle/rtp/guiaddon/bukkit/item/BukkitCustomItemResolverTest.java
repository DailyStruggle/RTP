package io.github.dailystruggle.rtp.guiaddon.bukkit.item;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Bukkit Custom Item Resolver Tests")
class BukkitCustomItemResolverTest {

  private static PluginManager mockPluginManager;

  @BeforeAll
  static void initServer() throws Exception {
    Server server = mock(Server.class);
    when(server.getLogger()).thenReturn(Logger.getLogger("MockServer"));
    mockPluginManager = mock(PluginManager.class);
    when(server.getPluginManager()).thenReturn(mockPluginManager);

    Field serverField = Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(null, server);
  }

  @AfterEach
  void resetPluginManager() {
    reset(mockPluginManager);
  }

  @Test
  @DisplayName("isCustomItem identifies custom prefixes and custom model data")
  void testIsCustomItem() {
    assertTrue(BukkitCustomItemResolver.isCustomItem("ia:custom:ruby_sword"));
    assertTrue(BukkitCustomItemResolver.isCustomItem("itemsadder:ruby"));
    assertTrue(BukkitCustomItemResolver.isCustomItem("oraxen:cave_shield"));
    assertTrue(BukkitCustomItemResolver.isCustomItem("nexo:amethyst_staff"));
    assertTrue(BukkitCustomItemResolver.isCustomItem("hdb:12345"));
    assertTrue(BukkitCustomItemResolver.isCustomItem("headdatabase:999"));
    assertTrue(BukkitCustomItemResolver.isCustomItem("head:notch"));
    assertTrue(BukkitCustomItemResolver.isCustomItem("playerhead:steve"));
    assertTrue(BukkitCustomItemResolver.isCustomItem("base64:eyJ0ZXh0dXJlcyI6..."));
    assertTrue(BukkitCustomItemResolver.isCustomItem("DIAMOND_SWORD:1005"));
    assertTrue(BukkitCustomItemResolver.isCustomItem("GOLDEN_HOE#42"));

    assertFalse(BukkitCustomItemResolver.isCustomItem("COMPASS"));
    assertFalse(BukkitCustomItemResolver.isCustomItem("EMERALD"));
    assertFalse(BukkitCustomItemResolver.isCustomItem("minecraft:diamond"));
    assertFalse(BukkitCustomItemResolver.isCustomItem(""));
    assertFalse(BukkitCustomItemResolver.isCustomItem("   "));
    assertFalse(BukkitCustomItemResolver.isCustomItem(null));
  }

  @Test
  @DisplayName("resolve returns vanilla material when matched")
  void testResolveVanillaMaterial() {
    ItemStack item1 = BukkitCustomItemResolver.resolve("EMERALD", Material.COMPASS);
    assertNotNull(item1);
    assertEquals(Material.EMERALD, item1.getType());

    ItemStack item2 = BukkitCustomItemResolver.resolve("minecraft:diamond", Material.COMPASS);
    assertNotNull(item2);
    assertEquals(Material.DIAMOND, item2.getType());

    ItemStack item3 = BukkitCustomItemResolver.resolve("  nether_star  ", Material.COMPASS);
    assertNotNull(item3);
    assertEquals(Material.NETHER_STAR, item3.getType());
  }

  @Test
  @DisplayName("resolve falls back safely on null, empty, or unknown material")
  void testResolveFallback() {
    ItemStack fallback1 = BukkitCustomItemResolver.resolve(null, Material.BARRIER);
    assertNotNull(fallback1);
    assertEquals(Material.BARRIER, fallback1.getType());

    ItemStack fallback2 = BukkitCustomItemResolver.resolve("", Material.PAPER);
    assertNotNull(fallback2);
    assertEquals(Material.PAPER, fallback2.getType());

    ItemStack fallback3 = BukkitCustomItemResolver.resolve("NON_EXISTENT_MATERIAL_XYZ", Material.COMPASS);
    assertNotNull(fallback3);
    assertEquals(Material.COMPASS, fallback3.getType());
  }

  @Test
  @DisplayName("resolve autocorrects perceptible material typos")
  void testResolveFuzzyMaterial() {
    // "COMPAS" -> COMPASS (edit dist 1)
    ItemStack item1 = BukkitCustomItemResolver.resolve("COMPAS", Material.DIRT);
    assertNotNull(item1);
    assertEquals(Material.COMPASS, item1.getType());

    // "GLAS" -> GLASS (edit dist 1)
    ItemStack item2 = BukkitCustomItemResolver.resolve("GLAS", Material.DIRT);
    assertNotNull(item2);
    assertEquals(Material.GLASS, item2.getType());

    // "DIAMOND_SWORDD" -> DIAMOND_SWORD (edit dist 1)
    ItemStack item3 = BukkitCustomItemResolver.resolve("DIAMOND_SWORDD", Material.DIRT);
    assertNotNull(item3);
    assertEquals(Material.DIAMOND_SWORD, item3.getType());
  }

  @Test
  @DisplayName("resolve parses CustomModelData syntax and returns valid material")
  void testResolveCustomModelData() {
    ItemStack itemColon = BukkitCustomItemResolver.resolve("DIAMOND_SWORD:1005", Material.COMPASS);
    assertNotNull(itemColon);
    assertEquals(Material.DIAMOND_SWORD, itemColon.getType());

    ItemStack itemHash = BukkitCustomItemResolver.resolve("GOLDEN_HOE#42", Material.COMPASS);
    assertNotNull(itemHash);
    assertEquals(Material.GOLDEN_HOE, itemHash.getType());

    // Malformed numbers fall back safely
    ItemStack itemBadNum = BukkitCustomItemResolver.resolve("DIAMOND_SWORD:notanumber", Material.COMPASS);
    assertNotNull(itemBadNum);
    assertEquals(Material.COMPASS, itemBadNum.getType());
  }

  @Test
  @DisplayName("resolve degrades gracefully when third-party plugin is absent")
  void testResolveDegradesGracefullyWithoutPlugin() {
    // When ItemsAdder is not installed, ia: prefix falls back cleanly to fallback material
    ItemStack iaItem = BukkitCustomItemResolver.resolve("ia:custom:ruby_sword", Material.COMPASS);
    assertNotNull(iaItem);
    assertEquals(Material.COMPASS, iaItem.getType());

    // When Oraxen is not installed, oraxen: prefix falls back cleanly
    ItemStack oraxenItem = BukkitCustomItemResolver.resolve("oraxen:cave_shield", Material.BARRIER);
    assertNotNull(oraxenItem);
    assertEquals(Material.BARRIER, oraxenItem.getType());

    // When Nexo is not installed, nexo: prefix falls back cleanly
    ItemStack nexoItem = BukkitCustomItemResolver.resolve("nexo:amethyst_staff", Material.NETHER_STAR);
    assertNotNull(nexoItem);
    assertEquals(Material.NETHER_STAR, nexoItem.getType());

    // When HeadDatabase is not installed, hdb: prefix falls back cleanly
    ItemStack hdbItem = BukkitCustomItemResolver.resolve("hdb:12345", Material.PLAYER_HEAD);
    assertNotNull(hdbItem);
    assertEquals(Material.PLAYER_HEAD, hdbItem.getType());
  }

  @Test
  @DisplayName("resolve returns mock item when stub plugins are enabled")
  void testResolveWithMockPluginsEnabled() {
    when(mockPluginManager.isPluginEnabled("ItemsAdder")).thenReturn(true);
    when(mockPluginManager.isPluginEnabled("Oraxen")).thenReturn(true);
    when(mockPluginManager.isPluginEnabled("Nexo")).thenReturn(true);
    when(mockPluginManager.isPluginEnabled("HeadDatabase")).thenReturn(true);

    // Test ItemsAdder resolution
    ItemStack iaItem = BukkitCustomItemResolver.resolve("ia:ruby_sword", Material.COMPASS);
    assertNotNull(iaItem);
    assertEquals(Material.DIAMOND_SWORD, iaItem.getType());

    // Test Oraxen resolution
    ItemStack oraxenItem = BukkitCustomItemResolver.resolve("oraxen:amethyst_pickaxe", Material.COMPASS);
    assertNotNull(oraxenItem);
    assertEquals(Material.GOLDEN_SWORD, oraxenItem.getType());

    // Test Nexo resolution
    ItemStack nexoItem = BukkitCustomItemResolver.resolve("nexo:void_blade", Material.COMPASS);
    assertNotNull(nexoItem);
    assertEquals(Material.NETHERITE_SWORD, nexoItem.getType());

    // Test HeadDatabase resolution
    ItemStack hdbItem = BukkitCustomItemResolver.resolve("hdb:9999", Material.COMPASS);
    assertNotNull(hdbItem);
    assertTrue(hdbItem.getType() == Material.PLAYER_HEAD || hdbItem.getType().name().contains("SKULL") || hdbItem.getType() == Material.DIRT);
  }
}
