package io.github.dailystruggle.rtp.common.importer.schema;

import io.github.dailystruggle.rtp.common.importer.AbstractForeignConfigImporter;
import io.github.dailystruggle.rtp.common.importer.ImportResult;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Generic schema-driven foreign configuration importer.
 * Executes declarative porting using a {@link PluginImportSchema}.
 */
public class GenericSchemaImporter extends AbstractForeignConfigImporter {

    private final PluginImportSchema schema;

    public GenericSchemaImporter(PluginImportSchema schema) {
        this.schema = Objects.requireNonNull(schema, "schema cannot be null");
    }

    public PluginImportSchema getSchema() {
        return schema;
    }

    @Override
    public String sourceName() {
        return schema.pluginName().toLowerCase(Locale.ROOT);
    }

    @Override
    public List<String> directoryAliases() {
        return schema.directoryAliases();
    }

    @Override
    public List<String> indicatorFiles() {
        return schema.candidateFiles();
    }

    @Override
    public boolean canImport(Path sourcePluginDir) {
        if (sourcePluginDir == null || !Files.isDirectory(sourcePluginDir)) {
            return false;
        }
        for (String f : schema.candidateFiles()) {
            if (Files.exists(sourcePluginDir.resolve(f))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ImportResult importConfiguration(Path sourcePluginDir, Path destinationDir, boolean overwrite) {
        List<Path> createdFiles = new ArrayList<>();
        List<Path> backedUpFiles = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> mappedEntities = new ArrayList<>();

        if (!canImport(sourcePluginDir)) {
            errors.add("Directory " + sourcePluginDir + " does not contain any candidate files: " + schema.candidateFiles());
            return new ImportResult(false, sourceName(), createdFiles, warnings, errors, mappedEntities);
        }

        Path primaryConfigFile = null;
        for (String f : schema.candidateFiles()) {
            Path p = sourcePluginDir.resolve(f);
            if (Files.exists(p)) {
                primaryConfigFile = p;
                break;
            }
        }

        if (primaryConfigFile == null) {
            errors.add("Failed to locate primary config file in " + sourcePluginDir);
            return new ImportResult(false, sourceName(), createdFiles, warnings, errors, mappedEntities);
        }

        RtpYamlConfig srcConfig = loadYamlSafe(primaryConfigFile, warnings);
        if (srcConfig == null) {
            errors.add("Could not parse config from " + primaryConfigFile.getFileName());
            return new ImportResult(false, sourceName(), createdFiles, warnings, errors, mappedEntities);
        }

        PluginImportSchema.WorldMapping wm = schema.worldMapping();
        PluginImportSchema.FieldAliases fa = wm.fields();

        Map<String, RtpYamlSection> worldSections = discoverWorldSections(
                sourcePluginDir,
                srcConfig,
                null,
                wm.rootPath().isEmpty() ? new String[]{"worlds", "CustomWorlds", "zones", "locations"} : new String[]{wm.rootPath()}
        );

        // Fallback: If no dedicated world sections found, check top-level for a default world
        if (worldSections.isEmpty()) {
            worldSections.put("world", srcConfig);
        }

        List<DiscoveredWorldRegion> discoveredList = new ArrayList<>();
        for (Map.Entry<String, RtpYamlSection> entry : worldSections.entrySet()) {
            String targetName = entry.getKey();
            RtpYamlSection sec = entry.getValue();

            int maxRadius = getIntCaseInsensitive(sec, 5000, fa.maxRadius().toArray(new String[0]));
            int minRadius = getIntCaseInsensitive(sec, 100, fa.minRadius().toArray(new String[0]));
            int centerX = getIntCaseInsensitive(sec, 0, fa.centerX().toArray(new String[0]));
            int centerZ = getIntCaseInsensitive(sec, 0, fa.centerZ().toArray(new String[0]));
            int minY = getIntCaseInsensitive(sec, 64, fa.minY().toArray(new String[0]));
            int maxY = getIntCaseInsensitive(sec, 320, fa.maxY().toArray(new String[0]));
            String shapeRaw = getStringCaseInsensitive(sec, "CIRCLE", fa.shape().toArray(new String[0]));
            double price = getDoubleCaseInsensitive(sec, 0.0, fa.price().toArray(new String[0]));
            List<String> biomes = getStringListCaseInsensitive(sec, fa.biomes().toArray(new String[0]));

            String shapeName = normalizeShape(shapeRaw);
            String worldName = getStringCaseInsensitive(sec, targetName, "world", "World", "name");
            if (worldName == null || worldName.trim().isEmpty()) worldName = targetName;

            discoveredList.add(new DiscoveredWorldRegion(
                    targetName, worldName, shapeName, minRadius, maxRadius, centerX, centerZ, minY, maxY, price, biomes
            ));
        }

        emitRegionsAndWorlds(discoveredList, destinationDir, overwrite, createdFiles, mappedEntities, errors);

        // Global settings mapping
        PluginImportSchema.GlobalMapping gm = schema.globalMapping();
        int cooldown = getIntCaseInsensitive(srcConfig, -1, gm.defaultCooldown().toArray(new String[0]));
        int delay = getIntCaseInsensitive(srcConfig, -1, gm.defaultDelay().toArray(new String[0]));

        if (cooldown >= 0 || delay >= 0) {
            updateDestinationConfig(destinationDir,
                    GlobalSettings.builder().cooldown(cooldown).delay(delay).build(),
                    mappedEntities, createdFiles, warnings);
        }

        // Mirror database & effects if present
        mirrorDatabaseConfig(srcConfig, destinationDir, overwrite, mappedEntities, createdFiles, warnings);
        mirrorEffectsConfig(srcConfig, destinationDir, overwrite, mappedEntities, createdFiles, warnings);

        boolean success = errors.isEmpty() && !createdFiles.isEmpty();
        return new ImportResult(success, sourceName(), createdFiles, warnings, errors, mappedEntities);
    }

    private static String normalizeShape(String raw) {
        if (raw == null) return "CIRCLE";
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.contains("SQUARE") || s.contains("RECT")) return "SQUARE";
        return "CIRCLE";
    }
}
