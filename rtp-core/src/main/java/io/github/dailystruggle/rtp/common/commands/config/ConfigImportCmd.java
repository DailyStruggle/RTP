package io.github.dailystruggle.rtp.common.commands.config;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.importer.ForeignConfigImporter;
import io.github.dailystruggle.rtp.common.importer.ForeignConfigImporterRegistry;
import io.github.dailystruggle.rtp.common.importer.ImportResult;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;

/**
 * Subcommand {@code /rtp config import [source] [--overwrite]}.
 * Implements the foreign config importer seam (ADR-066).
 */
public class ConfigImportCmd extends BaseRTPCmdImpl {

    public static final String PARAM_SOURCE = "source";
    public static final String PARAM_OVERWRITE = "overwrite";
    public static final String PARAM_PATH = "path";

    public ConfigImportCmd(@Nullable CommandsAPICommand parent) {
        super(parent);

        addParameter(PARAM_SOURCE, new CommandParameter("rtp.config", "foreign plugin source name",
                (uuid, s) -> true) {
            @Override
            public Set<String> values() {
                return ForeignConfigImporterRegistry.getRegisteredSourceNames();
            }
        });

        addParameter(PARAM_PATH, new CommandParameter("rtp.config", "custom source directory path to import from",
                (uuid, s) -> true) {
            @Override
            public Set<String> values() {
                return Collections.emptySet();
            }
        });

        addParameter(PARAM_OVERWRITE, new BooleanParameter("rtp.config", "whether to overwrite existing files",
                (uuid, s) -> true));

        addSubCommand(new ConfigImportPermissionsCmd(this));
    }

    @Override
    public String name() {
        return "import";
    }

    @Override
    public String permission() {
        return "rtp.config";
    }

    @Override
    public String description() {
        return "import configuration from competing RTP plugins";
    }

    @Override
    public boolean onCommand(UUID callerId,
                             Map<String, List<String>> parameterValues,
                             CommandsAPICommand nextCommand) {
        if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

        // Resolve destination and plugins directories
        File pluginDir = null;
        if (RTP.configs != null && RTP.configs.pluginDirectory != null) {
            pluginDir = RTP.configs.pluginDirectory;
        } else if (RTP.serverAccessor != null) {
            pluginDir = RTP.serverAccessor.getPluginDirectory();
        }

        if (pluginDir == null) {
            sendMessage(callerId, "&c[RTP] Cannot import: plugin directory is not available.");
            return false;
        }

        Path destinationDir = pluginDir.toPath();
        Path pluginsDir = destinationDir.getParent();
        if (pluginsDir == null) {
            sendMessage(callerId, "&c[RTP] Cannot import: parent plugins directory is not available.");
            return false;
        }

        boolean overwrite = false;
        if (parameterValues != null) {
            if (parameterValues.containsKey(PARAM_OVERWRITE)) {
                List<String> ovValues = parameterValues.get(PARAM_OVERWRITE);
                if (ovValues != null && !ovValues.isEmpty()) {
                    overwrite = Boolean.parseBoolean(ovValues.get(0));
                } else {
                    overwrite = true;
                }
            }
            // Also check for raw flag style --overwrite
            if (parameterValues.containsKey("--overwrite") || parameterValues.containsKey("-o")) {
                overwrite = true;
            }
        }

        String requestedSource = null;
        if (parameterValues != null && parameterValues.containsKey(PARAM_SOURCE)) {
            List<String> sList = parameterValues.get(PARAM_SOURCE);
            if (sList != null && !sList.isEmpty()) {
                requestedSource = sList.get(0);
            }
        }

        // Custom source path support
        String customPathStr = null;
        if (parameterValues != null) {
            if (parameterValues.containsKey(PARAM_PATH)) {
                List<String> pList = parameterValues.get(PARAM_PATH);
                if (pList != null && !pList.isEmpty()) {
                    customPathStr = pList.get(0);
                }
            } else if (parameterValues.containsKey("--path")) {
                List<String> pList = parameterValues.get("--path");
                if (pList != null && !pList.isEmpty()) {
                    customPathStr = pList.get(0);
                }
            }
        }

        if (customPathStr != null && !customPathStr.trim().isEmpty()) {
            Path customPath = Path.of(customPathStr.trim());
            if (java.nio.file.Files.isDirectory(customPath)) {
                // Determine whether customPath is the plugins root or a specific plugin's folder
                pluginsDir = customPath;
            } else {
                sendMessage(callerId, "&c[RTP] Specified path is not an existing directory: &f" + customPathStr);
                return false;
            }
        }

        ForeignConfigImporter importer;
        Path sourceDir = null;

        // If custom path directly points to a plugin folder that an importer can handle:
        if (customPathStr != null) {
            Path directPath = Path.of(customPathStr.trim());
            if (requestedSource != null) {
                importer = ForeignConfigImporterRegistry.getImporter(requestedSource);
                if (importer != null && importer.canImport(directPath)) {
                    sourceDir = directPath;
                }
            } else {
                // Attempt detection on the direct folder
                for (String srcName : ForeignConfigImporterRegistry.getRegisteredSourceNames()) {
                    ForeignConfigImporter imp = ForeignConfigImporterRegistry.getImporter(srcName);
                    if (imp != null && imp.canImport(directPath)) {
                        requestedSource = srcName;
                        importer = imp;
                        sourceDir = directPath;
                        break;
                    }
                }
            }
        }

        if (sourceDir == null) {
            if (requestedSource == null || requestedSource.trim().isEmpty()) {
                // Auto-detection
                Map<String, Path> detected = ForeignConfigImporterRegistry.detectAvailableSources(pluginsDir);
                if (detected.isEmpty()) {
                    sendMessage(callerId, "&e[RTP] No competitor plugin configurations detected in &f"
                            + pluginsDir.getFileName() + "&e. Available sources: &f"
                            + String.join(", ", ForeignConfigImporterRegistry.getRegisteredSourceNames()));
                    return false;
                } else if (detected.size() > 1) {
                    sendMessage(callerId, "&e[RTP] Multiple competitor configurations detected: &f"
                            + String.join(", ", detected.keySet())
                            + "&e. Please specify one: &f/rtp config import <source>");
                    return false;
                } else {
                    Map.Entry<String, Path> single = detected.entrySet().iterator().next();
                    requestedSource = single.getKey();
                    sourceDir = single.getValue();
                    importer = ForeignConfigImporterRegistry.getImporter(requestedSource);
                    sendMessage(callerId, "&a[RTP] Auto-detected competitor configuration: &f" + requestedSource);
                }
            } else {
                importer = ForeignConfigImporterRegistry.getImporter(requestedSource);
                if (importer == null) {
                    sendMessage(callerId, "&c[RTP] Unknown source: &f" + requestedSource
                            + "&c. Supported sources: &f"
                            + String.join(", ", ForeignConfigImporterRegistry.getRegisteredSourceNames()));
                    return false;
                }
                sourceDir = ForeignConfigImporterRegistry.resolveSourceDir(pluginsDir, requestedSource);
                if (sourceDir == null || !importer.canImport(sourceDir)) {
                    sendMessage(callerId, "&c[RTP] Could not find configuration for &f" + requestedSource
                            + "&c in &f" + pluginsDir);
                    return false;
                }
            }
        } else {
            importer = ForeignConfigImporterRegistry.getImporter(requestedSource);
        }

        sendMessage(callerId, "&7[RTP] Importing from &f" + sourceDir.getFileName() + "&7 (overwrite=" + overwrite + ")...");
        ImportResult result = importer.importConfiguration(sourceDir, destinationDir, overwrite);

        for (String warn : result.getWarnings()) {
            sendMessage(callerId, "&e[WARN] " + warn);
        }

        if (!result.isSuccess()) {
            sendMessage(callerId, "&c[RTP] Import failed!");
            for (String err : result.getErrors()) {
                sendMessage(callerId, "&c[ERROR] " + err);
            }
            if (!overwrite) {
                sendMessage(callerId, "&7Tip: pass &foverwrite=true&7 or &f--overwrite&7 to replace existing files.");
            }
            return false;
        }

        sendMessage(callerId, "&a[RTP] Successfully imported configuration from &f" + result.getSourceName() + "&a:");
        for (String entity : result.getMappedEntities()) {
            sendMessage(callerId, "  &2✔ &f" + entity);
        }
        sendMessage(callerId, "&aGenerated &f" + result.getWrittenFiles().size() + "&a file(s). Run &f/rtp reload&a to apply.");

        return true;
    }

    private void sendMessage(@Nullable UUID callerId, String msg) {
        if (callerId != null && RTP.serverAccessor != null) {
            RTP.serverAccessor.sendMessage(callerId, msg);
        } else {
            RTP.log(Level.INFO, msg.replaceAll("&[0-9a-fk-or]", ""));
        }
    }
}
