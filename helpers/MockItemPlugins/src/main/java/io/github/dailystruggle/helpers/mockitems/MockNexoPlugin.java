package io.github.dailystruggle.helpers.mockitems;

import org.bukkit.plugin.java.JavaPlugin;

public class MockNexoPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        getLogger().info("[MockNexo] Stub Nexo plugin loaded for LeafRTP testing.");
    }
}
