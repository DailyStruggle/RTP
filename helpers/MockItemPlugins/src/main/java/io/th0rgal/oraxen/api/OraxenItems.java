package io.th0rgal.oraxen.api;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Mock stub of the OraxenItems API for testing LeafRTP integrations.
 */
public final class OraxenItems {
    private OraxenItems() {}

    public static ItemStack getItemById(String id) {
        if (id == null || id.isBlank()) return null;
        ItemStack item = new ItemStack(Material.GOLDEN_SWORD);
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§6[Oraxen] §f" + id);
                item.setItemMeta(meta);
            }
        } catch (Throwable ignored) {}
        return item;
    }
}
