package io.github.dailystruggle.rtp.bukkit.tools;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;

public class SimpleBinderModule extends AbstractModule {

    private final RTPBukkitPlugin plugin;

    // This is also dependency injection, but without any libraries/frameworks since we can't use those here yet.
    public SimpleBinderModule(RTPBukkitPlugin plugin) {
        this.plugin = plugin;
    }

    public Injector createInjector() {
        return Guice.createInjector(this);
    }

    @Override
    protected void configure() {
        // Here we tell Guice to use our plugin instance everytime we need it
        this.bind(RTPBukkitPlugin.class).toInstance(this.plugin);
    }
}
