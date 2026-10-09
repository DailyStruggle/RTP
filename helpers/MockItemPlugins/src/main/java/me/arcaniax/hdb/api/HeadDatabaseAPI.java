package me.arcaniax.hdb.api;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Mock stub of the HeadDatabaseAPI for testing LeafRTP integrations.
 */
public class HeadDatabaseAPI {
    public HeadDatabaseAPI() {}

    public ItemStack getItemHead(String id) {
        if (id == null || id.isBlank()) return null;
        Material headMat = Material.matchMaterial("PLAYER_HEAD");
        if (headMat == null) headMat = Material.matchMaterial("SKULL_ITEM");
        ItemStack item = new ItemStack(headMat != null ? headMat : Material.DIRT);
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§e[HeadDatabase] §f" + id);
                item.setItemMeta(meta);
            }
        } catch (Throwable ignored) {}
        return item;
    }
}
