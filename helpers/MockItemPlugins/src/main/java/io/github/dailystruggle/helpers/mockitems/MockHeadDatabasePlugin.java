package io.github.dailystruggle.helpers.mockitems;

import org.bukkit.plugin.java.JavaPlugin;

public class MockHeadDatabasePlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        getLogger().info("[MockHeadDatabase] Stub HeadDatabase plugin loaded for LeafRTP testing.");
    }
}
