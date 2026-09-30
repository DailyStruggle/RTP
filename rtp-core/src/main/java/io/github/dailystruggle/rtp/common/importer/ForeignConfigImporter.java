package io.github.dailystruggle.rtp.common.importer;

import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seam for importing third-party random teleport plugin configurations
 * into LeafRTP region, world, and global configurations (ADR-066).
 */
public interface ForeignConfigImporter {

    /**
     * Unique identifier for this importer source (e.g. "ezrtp", "justrtp").
     *
     * @return lowercase source name
     */
    String sourceName();

    /**
     * Checks whether this importer can handle the configuration at the given plugin folder.
     *
     * @param sourcePluginDir candidate directory, e.g. {@code plugins/EzRTP}
     * @return true if candidate directory contains required configuration files
     */
    boolean canImport(Path sourcePluginDir);

    /**
     * Imports configuration from the source directory into the destination directory.
     * Default overwrite behavior is false (refuses to clobber existing files).
     *
     * @param sourcePluginDir directory of the foreign plugin (e.g. {@code plugins/EzRTP})
     * @param destinationDir  destination root directory of LeafRTP (e.g. {@code plugins/RTP})
     * @return result of the import operation
     */
    default ImportResult importConfiguration(Path sourcePluginDir, Path destinationDir) {
        return importConfiguration(sourcePluginDir, destinationDir, false);
    }

    /**
     * Imports configuration from the source directory into the destination directory.
     *
     * @param sourcePluginDir directory of the foreign plugin (e.g. {@code plugins/EzRTP})
     * @param destinationDir  destination root directory of LeafRTP (e.g. {@code plugins/RTP})
     * @param overwrite       whether to overwrite existing configuration files
     * @return result of the import operation
     */
    ImportResult importConfiguration(Path sourcePluginDir, Path destinationDir, boolean overwrite);

    /**
     * Safely load and pre-process a foreign YAML file, handling empty brackets (e.g. {@code []}, {@code {}})
     * that standard competitor configs frequently include but which our strict YAML subset rejects.
     */
    default RtpYamlConfig loadForeignYaml(Path file, List<String> warnings) {
        if (file == null || !Files.exists(file)) return null;
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String preprocessed = sanitizeForeignYaml(raw);
            return RtpYamlConfig.parse(preprocessed);
        } catch (Throwable e) {
            warnings.add("Failed to parse " + file.getFileName() + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Sanitizes foreign YAML text so it can be parsed by RtpYamlReader:
     * - Strips unquoted comments immediately following ':' or ': ' (e.g. 'key: # comment')
     * - Replaces ': []' and ': {}' with ':'
     * - Unfolds inline flow sequences (e.g. 'slots: [1, 2]') into standard block sequences
     * - Indents zero-indent sequence items that are directly under a top-level mapping key
     */
    static String sanitizeForeignYaml(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String[] lines = raw.split("\r?\n", -1);
        Pattern emptyBrackets = Pattern.compile("^(\\s*[^#:\\r\\n]+:)\\s*(\\[\\]|\\{\\})(.*)$");
        Pattern immediateComment = Pattern.compile("^(\\s*[^#:\\r\\n]+:)\\s+#.*$");
        Pattern flowSeq = Pattern.compile("^(\\s*)([^#:\\r\\n]+:)\\s*\\[([^\\]]*)\\]\\s*(#.*)?$");

        List<String> pass1 = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            Matcher mFlow = flowSeq.matcher(line);
            if (mFlow.matches()) {
                String indent = mFlow.group(1);
                String key = mFlow.group(2);
                String inside = mFlow.group(3).trim();
                String comment = mFlow.group(4);

                if (inside.isEmpty()) {
                    pass1.add(indent + key + (comment != null ? " " + comment.trim() : ""));
                } else {
                    pass1.add(indent + key + (comment != null && !comment.trim().isEmpty() ? " " + comment.trim() : ""));
                    String[] items = inside.split(",");
                    for (String it : items) {
                        pass1.add(indent + "  - " + it.trim());
                    }
                }
                continue;
            }

            Matcher mEmpty = emptyBrackets.matcher(line);
            if (mEmpty.matches()) {
                String keyPart = mEmpty.group(1);
                String trailing = mEmpty.group(3);
                line = keyPart + (trailing.isEmpty() ? "" : " " + trailing.trim());
            }

            Matcher mComm = immediateComment.matcher(line);
            if (mComm.matches()) {
                line = mComm.group(1);
            }

            pass1.add(line);
        }

        List<String> resultLines = new ArrayList<>();
        // In standard YAML, sequence items are frequently written at the exact same indentation level
        // as their parent mapping key (e.g. `CustomWorlds:\n- custom_world_1:` or `Biomes:\n  - desert` vs `Biomes:\n- desert`).
        // RtpYamlReader strictly requires child items (mappings or sequences) to have strictly greater indentation than the parent key.
        // Therefore, if any sequence item has leadingSpaces <= parentKeyIndent, we must shift the sequence block by:
        // shift = (parentKeyIndent + 2) - leadingSpaces.
        // We use an indentation stack of active mapping keys to find the appropriate parent key.
        int activeShift = 0;
        int activeSeqIndent = -1;
        List<Integer> keyIndentStack = new ArrayList<>();

        for (String line : pass1) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                if (activeShift > 0 && line.startsWith(" ")) {
                    resultLines.add(" ".repeat(activeShift) + line);
                } else {
                    resultLines.add(line);
                }
                continue;
            }

            int leadingSpaces = 0;
            while (leadingSpaces < line.length() && line.charAt(leadingSpaces) == ' ') {
                leadingSpaces++;
            }
            String content = line.substring(leadingSpaces);

            if (content.startsWith("- ") || content.equals("-")) {
                if (!keyIndentStack.isEmpty()) {
                    int parentKeyIndent = keyIndentStack.get(keyIndentStack.size() - 1);
                    if (leadingSpaces <= parentKeyIndent) {
                        activeShift = (parentKeyIndent + 2) - leadingSpaces;
                        activeSeqIndent = leadingSpaces;
                    } else if (activeSeqIndent >= 0 && leadingSpaces == activeSeqIndent) {
                        // Sibling item in shifted sequence
                    } else if (activeSeqIndent >= 0 && leadingSpaces > activeSeqIndent) {
                        // Continuation or nested within item
                    } else {
                        activeShift = 0;
                        activeSeqIndent = -1;
                    }
                } else {
                    if (leadingSpaces == 0) {
                        activeShift = 2;
                        activeSeqIndent = 0;
                    } else {
                        activeShift = 0;
                        activeSeqIndent = -1;
                    }
                }
            } else {
                if (activeShift > 0 && leadingSpaces <= activeSeqIndent) {
                    activeShift = 0;
                    activeSeqIndent = -1;
                }

                // If this is a key, update keyIndentStack
                if (content.endsWith(":") || content.contains(": ") || content.contains(":#")) {
                    int effectiveIndent = leadingSpaces + activeShift;
                    while (!keyIndentStack.isEmpty() && keyIndentStack.get(keyIndentStack.size() - 1) >= effectiveIndent) {
                        keyIndentStack.remove(keyIndentStack.size() - 1);
                    }
                    if (content.endsWith(":")) {
                        keyIndentStack.add(effectiveIndent);
                    }
                }
            }

            if (activeShift > 0) {
                resultLines.add(" ".repeat(activeShift) + line);
            } else {
                resultLines.add(line);
            }
        }
        return String.join("\n", resultLines);
    }
}
