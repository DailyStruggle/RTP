package io.github.dailystruggle.rtp.common.bit.config;

import io.github.dailystruggle.effectsapi.common.EffectFactory;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Live inputs a config check may consult. Every field is optional: {@code null} means "unknown"
 * (headless run, accessor not yet installed), which a check shall report as at most INFO, never as
 * a missing reference. World names come from already-loaded worlds only; no chunk or world load
 * is triggered (S-005).
 *
 * @param worldNames names of loaded worlds, or {@code null} when unknown
 * @param effectNames registered effect factory names, or {@code null} when unknown
 * @param resources class loader that holds the bundled default resources (ADR-064 directives)
 */
public record ConfigEnvironment(Set<String> worldNames, Set<String> effectNames, ClassLoader resources) {
    public static final ConfigEnvironment HEADLESS = new ConfigEnvironment(null, null, RTP.class.getClassLoader());

    public ConfigEnvironment {
        worldNames = worldNames == null ? null : Set.copyOf(worldNames);
        effectNames = effectNames == null ? null : Set.copyOf(effectNames);
        resources = resources == null ? RTP.class.getClassLoader() : resources;
    }

    public Optional<Set<String>> worlds() {
        return Optional.ofNullable(worldNames);
    }

    public Optional<Set<String>> effects() {
        return Optional.ofNullable(effectNames);
    }

    public ConfigEnvironment withEffectNames(Set<String> names) {
        return new ConfigEnvironment(worldNames, names, resources);
    }

    /** Environment for the running server; falls back to {@link #HEADLESS} fields when absent. */
    public static ConfigEnvironment live() {
        RTPServerAccessor accessor = RTPRuntime.serverAccessor();
        Set<String> worlds = null;
        if (accessor != null) {
            try {
                Set<String> names = new LinkedHashSet<>();
                for (RTPWorld<?> world : accessor.getRTPWorlds()) {
                    if (world != null && world.name() != null) names.add(world.name());
                }
                worlds = names;
            } catch (RuntimeException e) {
                RTP.log(java.util.logging.Level.FINE, "[RTP bit] world names unavailable for config PBIT", e);
            }
        }
        return new ConfigEnvironment(worlds, accessor == null ? null : registeredEffects(), RTP.class.getClassLoader());
    }

    /** Upper-case registered effect names; {@code null} (unknown) before any platform registers effects. */
    static Set<String> registeredEffects() {
        try {
            Set<String> names = new LinkedHashSet<>();
            for (String name : EffectFactory.registeredNames()) {
                if (name != null && !name.isBlank()) names.add(name.toUpperCase(Locale.ROOT));
            }
            return names.isEmpty() ? null : names;
        } catch (RuntimeException | LinkageError e) {
            RTP.log(java.util.logging.Level.FINE, "[RTP bit] effect names unavailable for config PBIT", e);
            return null;
        }
    }
}
