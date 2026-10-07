package io.github.dailystruggle.helpers.mockitems;

import com.nexomc.nexo.api.NexoItems;
import dev.lone.itemsadder.api.CustomStack;
import io.th0rgal.oraxen.api.OraxenItems;
import me.arcaniax.hdb.api.HeadDatabaseAPI;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Mock Item Stubs Verification Tests")
class MockItemStubsTest {

    private static ItemMeta meta;

    @BeforeAll
    static void setUpBukkit() {
        if (Bukkit.getServer() == null) {
            Server server = mock(Server.class);
            when(server.getLogger()).thenReturn(Logger.getLogger("MockServer"));
            when(server.getName()).thenReturn("MockServer");
            when(server.getVersion()).thenReturn("1.20.1");
            when(server.getBukkitVersion()).thenReturn("1.20.1-R0.1-SNAPSHOT");

            ItemFactory factory = mock(ItemFactory.class);
            meta = mock(ItemMeta.class);
            when(server.getItemFactory()).thenReturn(factory);
            when(factory.getItemMeta(any(Material.class))).thenReturn(meta);
            when(factory.isApplicable(any(), any(ItemStack.class))).thenReturn(true);
            when(factory.equals(any(), any())).thenReturn(true);
            when(factory.asMetaFor(any(), any(ItemStack.class))).thenReturn(meta);
            Bukkit.setServer(server);
        }
    }

    @Test
    @DisplayName("ItemsAdder CustomStack stub returns expected item with display name")
    void testItemsAdderStub() {
        CustomStack stack = CustomStack.getInstance("test_sword");
        assertNotNull(stack);
        assertEquals("test_sword", stack.getId());

        ItemStack item = stack.getItemStack();
        assertNotNull(item);
        assertEquals(Material.DIAMOND_SWORD, item.getType());
        verify(meta, atLeastOnce()).setDisplayName("§b[ItemsAdder] §ftest_sword");

        assertNull(CustomStack.getInstance(null));
        assertNull(CustomStack.getInstance(""));
    }

    @Test
    @DisplayName("OraxenItems stub returns expected item with display name")
    void testOraxenStub() {
        ItemStack item = OraxenItems.getItemById("ruby_pickaxe");
        assertNotNull(item);
        assertEquals(Material.GOLDEN_SWORD, item.getType());
        verify(meta, atLeastOnce()).setDisplayName("§6[Oraxen] §fruby_pickaxe");

        assertNull(OraxenItems.getItemById(null));
        assertNull(OraxenItems.getItemById(""));
    }

    @Test
    @DisplayName("NexoItems stub returns expected item with display name")
    void testNexoStub() {
        ItemStack item = NexoItems.itemFromId("void_blade");
        assertNotNull(item);
        assertEquals(Material.NETHERITE_SWORD, item.getType());
        verify(meta, atLeastOnce()).setDisplayName("§d[Nexo] §fvoid_blade");

        assertNull(NexoItems.itemFromId(null));
        assertNull(NexoItems.itemFromId(""));
    }

    @Test
    @DisplayName("HeadDatabaseAPI stub returns expected head item")
    void testHeadDatabaseStub() {
        HeadDatabaseAPI api = new HeadDatabaseAPI();
        ItemStack item = api.getItemHead("12345");
        assertNotNull(item);
        assertTrue(item.getType() == Material.PLAYER_HEAD || item.getType().name().contains("SKULL") || item.getType() == Material.DIRT);
        verify(meta, atLeastOnce()).setDisplayName("§e[HeadDatabase] §f12345");

        assertNull(api.getItemHead(null));
        assertNull(api.getItemHead(""));
    }
}
