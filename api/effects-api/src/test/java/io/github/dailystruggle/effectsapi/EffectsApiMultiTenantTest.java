package io.github.dailystruggle.effectsapi;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Verifies that {@link EffectsAPI} keys its listener state per owning plugin so
 * two plugins sharing a single un-relocated copy of the class on the same
 * server do not clobber each other.
 *
 * <p>Motivation: effects-api is shaded into the host jar under its real package
 * (un-relocated) so {@code compileOnly} addons can link. That means a second
 * RTP-family plugin (e.g. a future LeafSleep) can resolve the same
 * {@code EffectsAPI} class. The previous single-static design was first-call
 * wins: the second plugin's {@code init()} was a silent no-op and either
 * plugin's {@code disable()} tore down the shared listeners. These tests pin
 * the per-plugin registry so that regression cannot return.
 */
class EffectsApiMultiTenantTest {

    private final Map<Plugin, Object> savedStates = new HashMap<>();

    @BeforeEach
    void save() {
        savedStates.clear();
        savedStates.putAll(new HashMap<>(statesMap()));
        statesMap().clear();
    }

    @AfterEach
    void restore() {
        Map<Plugin, Object> states = statesMap();
        states.clear();
        states.putAll(savedStates);
    }

    @Test
    @DisplayName("getInstance() throws IllegalStateException when no plugin is registered (S-006)")
    void getInstanceThrowsWhenEmpty() {
        assertThrows(IllegalStateException.class, EffectsAPI::getInstance);
    }

    @Test
    @DisplayName("a single registered plugin resolves unambiguously")
    void singlePluginResolves() {
        Plugin only = mock(Plugin.class);
        seedState(only);
        assertSame(only, EffectsAPI.getInstance(),
                "with one registered plugin getInstance() must return it");
    }

    @Test
    @DisplayName("two plugins share the class without clobbering each other's state")
    void twoPluginsCoexist() {
        Plugin a = mock(Plugin.class);
        Plugin b = mock(Plugin.class);
        seedState(a);
        seedState(b);

        // Both states are present (the second registration did not overwrite the first).
        assertEquals(2, statesMap().size(), "both plugins must keep their own state");
        assertTrue(statesMap().containsKey(a));
        assertTrue(statesMap().containsKey(b));

        // getInstance() still resolves to a registered plugin (best-effort) and never throws.
        assertNotNull(EffectsAPI.getInstance());
    }

    @Test
    @DisplayName("disable(Plugin) removes only the caller's state")
    void disableIsPerPlugin() {
        Plugin a = mock(Plugin.class);
        Plugin b = mock(Plugin.class);
        seedState(a);
        seedState(b);

        EffectsAPI.disable(a);

        assertFalse(statesMap().containsKey(a), "disable(a) must remove a's state");
        assertTrue(statesMap().containsKey(b), "disable(a) must not touch b's state");
    }

    // --- reflection helpers (seed STATES with null listeners; init() would
    //     require a live Bukkit server to register events) ---

    @SuppressWarnings("unchecked")
    private static Map<Plugin, Object> statesMap() {
        try {
            Field f = EffectsAPI.class.getDeclaredField("STATES");
            f.setAccessible(true);
            return (Map<Plugin, Object>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void seedState(Plugin plugin) {
        try {
            Class<?> stateClass =
                    Class.forName("io.github.dailystruggle.effectsapi.EffectsAPI$PluginState");
            Constructor<?> ctor = stateClass.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            statesMap().put(plugin, ctor.newInstance(null, null));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
