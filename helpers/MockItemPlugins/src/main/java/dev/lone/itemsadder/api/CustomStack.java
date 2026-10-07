package dev.lone.itemsadder.api;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Mock stub of the ItemsAdder CustomStack API for testing LeafRTP integrations.
 */
public class CustomStack {
    private final String id;

    private CustomStack(String id) {
        this.id = id;
    }

    public static CustomStack getInstance(String id) {
        if (id == null || id.isBlank()) return null;
        return new CustomStack(id);
    }

    public ItemStack getItemStack() {
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§b[ItemsAdder] §f" + id);
                item.setItemMeta(meta);
            }
        } catch (Throwable ignored) {}
        return item;
    }

    public String getId() {
        return id;
    }
}
