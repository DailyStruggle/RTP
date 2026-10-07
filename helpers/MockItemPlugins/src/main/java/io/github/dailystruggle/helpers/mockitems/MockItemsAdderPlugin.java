package io.github.dailystruggle.helpers.mockitems;

import org.bukkit.plugin.java.JavaPlugin;

public class MockItemsAdderPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        getLogger().info("[MockItemsAdder] Stub ItemsAdder plugin loaded for LeafRTP testing.");
    }
}
