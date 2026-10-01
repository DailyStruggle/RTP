package io.github.dailystruggle.rtp.common.importer.schema;

import java.util.*;

/**
 * In-code declarative descriptor specifying how to map a foreign RTP plugin's
 * configuration layout into LeafRTP regions, worlds, and settings.
 */
public record PluginImportSchema(
        String pluginName,
        List<String> directoryAliases,
        List<String> candidateFiles,
        WorldMapping worldMapping,
        EffectMapping effectMapping,
        GlobalMapping globalMapping,
        Map<String, String> permissionEquivalences
) {
    public enum Mode {
        MAP,  // Root section keys are world/region names: `worlds: { world: {...} }`
        LIST  // Root section is a sequence of entries: `worlds: [ { world: "world", ... } ]`
    }

    public record WorldMapping(
            String rootPath,
            Mode mode,
            FieldAliases fields
    ) {}

    public record FieldAliases(
            List<String> maxRadius,
            List<String> minRadius,
            List<String> centerX,
            List<String> centerZ,
            List<String> minY,
            List<String> maxY,
            List<String> shape,
            List<String> price,
            List<String> cooldown,
            List<String> delay,
            List<String> blacklistedBlocks,
            List<String> biomes
    ) {}

    public record EffectMapping(
            List<String> particleKeys,
            List<String> soundKeys
    ) {}

    public record GlobalMapping(
            List<String> defaultCooldown,
            List<String> defaultDelay,
            List<String> maxAttempts
    ) {}

    public static Builder builder(String pluginName) {
        return new Builder(pluginName);
    }

    public static class Builder {
        private final String pluginName;
        private final List<String> directoryAliases = new ArrayList<>();
        private final List<String> candidateFiles = new ArrayList<>();
        private WorldMapping worldMapping;
        private EffectMapping effectMapping = new EffectMapping(List.of(), List.of());
        private GlobalMapping globalMapping = new GlobalMapping(List.of(), List.of(), List.of());
        private final Map<String, String> permissions = new LinkedHashMap<>();

        public Builder(String pluginName) {
            this.pluginName = pluginName;
            this.directoryAliases.add(pluginName);
        }

        public Builder aliases(String... aliases) {
            Collections.addAll(this.directoryAliases, aliases);
            return this;
        }

        public Builder candidateFiles(String... files) {
            Collections.addAll(this.candidateFiles, files);
            return this;
        }

        public Builder worldMapping(String rootPath, Mode mode, FieldAliases fields) {
            this.worldMapping = new WorldMapping(rootPath, mode, fields);
            return this;
        }

        public Builder effectMapping(List<String> particleKeys, List<String> soundKeys) {
            this.effectMapping = new EffectMapping(particleKeys, soundKeys);
            return this;
        }

        public Builder globalMapping(List<String> defaultCooldown, List<String> defaultDelay, List<String> maxAttempts) {
            this.globalMapping = new GlobalMapping(defaultCooldown, defaultDelay, maxAttempts);
            return this;
        }

        public Builder permission(String foreignNode, String leafNode) {
            this.permissions.put(foreignNode, leafNode);
            return this;
        }

        public PluginImportSchema build() {
            if (candidateFiles.isEmpty()) {
                candidateFiles.add("config.yml");
            }
            if (worldMapping == null) {
                worldMapping = new WorldMapping("", Mode.MAP, new FieldAliases(
                        List.of("max-radius", "maxRadius", "radius"),
                        List.of("min-radius", "minRadius"),
                        List.of("center.x", "centerX", "center-x"),
                        List.of("center.z", "centerZ", "center-z"),
                        List.of("min-y", "minY"),
                        List.of("max-y", "maxY"),
                        List.of("shape", "type"),
                        List.of("price", "cost"),
                        List.of("cooldown"),
                        List.of("delay", "teleport-delay"),
                        List.of("blacklisted-blocks", "unsafe-blocks"),
                        List.of("biomes", "blacklisted-biomes")
                ));
            }
            return new PluginImportSchema(
                    pluginName,
                    List.copyOf(directoryAliases),
                    List.copyOf(candidateFiles),
                    worldMapping,
                    effectMapping,
                    globalMapping,
                    Map.copyOf(permissions)
            );
        }
    }
}
