package com.nexomc.nexo.api;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Mock stub of the NexoItems API for testing LeafRTP integrations.
 */
public final class NexoItems {
    private NexoItems() {}

    public static ItemStack itemFromId(String id) {
        if (id == null || id.isBlank()) return null;
        ItemStack item = new ItemStack(Material.NETHERITE_SWORD);
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§d[Nexo] §f" + id);
                item.setItemMeta(meta);
            }
        } catch (Throwable ignored) {}
        return item;
    }
}
