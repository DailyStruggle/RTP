package io.github.dailystruggle.rtp.common.commands.base;

import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.api.menu.MenuRendererProvider;
import io.github.dailystruggle.server.api.PlatformFamily;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.logging.Level;

/**
 * Platform-neutral discovery of {@link MenuRenderer} instances via {@link ServiceLoader}.
 * Extracted to {@code commands.base} to prevent cyclic dependencies between commands (ADR-115).
 */
public final class MenuRendererDiscovery {

    private MenuRendererDiscovery() {}

    /**
     * Resolves the {@link MenuRenderer} from configured renderer IDs.
     * Discovers via {@link MenuRendererProvider}; returns {@code null} if unavailable.
     *
     * @return resolved {@link MenuRenderer}, or {@code null}
     */
    public static @Nullable MenuRenderer discoverRenderer() {
        List<String> ids = configuredRendererIds();
        Map<String, MenuRendererProvider> byId = discoverRendererProviders();

        for (String id : ids) {
            MenuRendererProvider provider = byId.get(id);
            if (provider == null) {
                io.github.dailystruggle.rtp.common.bit.PlatformHookBitComponent.recordMenuRendererDegraded(
                        id, "no-provider-on-platform");
                RTPRuntime.log(
                        Level.WARNING,
                        "menu.renderer '" + id + "' has no provider on this platform");
                continue;
            }
            try {
                MenuRenderer r = provider.create();
                if (r != null) return r;
            } catch (RuntimeException | LinkageError ex) {
                io.github.dailystruggle.rtp.common.bit.PlatformHookBitComponent.recordMenuRendererDegraded(
                        id, ex.getClass().getSimpleName() + ": " + ex.getMessage());
                RTPRuntime.log(
                        Level.WARNING,
                        "failed to instantiate menu.renderer '" + id + "': " + ex,
                        ex);
            }
        }
        io.github.dailystruggle.rtp.common.bit.PlatformHookBitComponent.recordMenuRendererDegraded(
                ids.toString(), "renderer-list-exhausted");
        RTPRuntime.log(
                Level.WARNING,
                "menu.renderer list exhausted (" + ids + "); /rtp menu open-page disabled");
        return null;
    }

    private static List<String> configuredRendererIds() {
        Object raw = null;
        try {
            @SuppressWarnings("unchecked")
            ConfigParser<ConfigKeys> menuConfig =
                    (ConfigParser<ConfigKeys>) ActiveConfigs.get().getParser(ConfigKeys.class);
            if (menuConfig != null) {
                Object menuBlock = menuConfig.getConfigValue(ConfigKeys.menu, null);
                if (menuBlock instanceof Map<?, ?> map) {
                    raw = map.get("renderer");
                } else {
                    raw = menuBlock;
                }
            }
        } catch (RuntimeException ignored) {
            // config not loaded yet: fall back to the default renderer below
        }
        List<String> ids = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) ids.add(String.valueOf(o).trim().toLowerCase(Locale.ROOT));
            }
        } else if (raw instanceof String s && !s.isBlank()) {
            ids.add(s.trim().toLowerCase(Locale.ROOT));
        }
        if (ids.isEmpty()) {
            ids.add("book");
        }
        return ids;
    }

    private static Map<String, MenuRendererProvider> discoverRendererProviders() {
        Map<String, MenuRendererProvider> byId = new LinkedHashMap<>();
        PlatformFamily family = currentPlatformFamily();
        ServiceLoader<MenuRendererProvider> loader =
                ServiceLoader.load(
                        MenuRendererProvider.class, MenuRendererProvider.class.getClassLoader());
        for (MenuRendererProvider provider : loadProvidersSafely(loader, "menu renderer provider")) {
            String id = matchesPlatform(provider.platformFamily(), family) ? providerId(provider) : null;
            if (id != null) {
                byId.putIfAbsent(id.trim().toLowerCase(Locale.ROOT), provider);
            }
        }
        return byId;
    }

    private static @Nullable String providerId(MenuRendererProvider provider) {
        try {
            return provider.id();
        } catch (RuntimeException ex) {
            RTPRuntime.log(Level.WARNING, "menu renderer provider id() threw: " + ex.getMessage(), ex);
            return null;
        }
    }

    public static <T> List<T> loadProvidersSafely(ServiceLoader<T> loader, String what) {
        List<T> providers = new ArrayList<>();
        Iterator<T> it = loader.iterator();
        boolean more = true;
        while (more) {
            try {
                more = it.hasNext();
                if (more) providers.add(it.next());
            } catch (java.util.ServiceConfigurationError | LinkageError err) {
                RTPRuntime.log(
                        Level.FINE,
                        "skipping unloadable " + what + ": " + err);
            }
        }
        return providers;
    }

    public static @Nullable PlatformFamily currentPlatformFamily() {
        try {
            if (RTPRuntime.serverAccessor() == null) return null;
            return RTPRuntime.serverAccessor().getPlatformFamily();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static boolean matchesPlatform(
            @Nullable PlatformFamily providerFamily, @Nullable PlatformFamily running) {
        if (providerFamily == null) return true;
        if (running == null || running == PlatformFamily.UNKNOWN) return true;
        return providerFamily == running;
    }
}
