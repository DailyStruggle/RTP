package io.github.dailystruggle.helpers.mockitems;

import org.bukkit.plugin.java.JavaPlugin;

public class MockOraxenPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        getLogger().info("[MockOraxen] Stub Oraxen plugin loaded for LeafRTP testing.");
    }
}
