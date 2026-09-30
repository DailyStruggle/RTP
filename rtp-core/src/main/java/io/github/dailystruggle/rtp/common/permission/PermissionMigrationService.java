package io.github.dailystruggle.rtp.common.permission;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for scanning, mapping, and migrating permissions non-destructively
 * from competitor plugins (BetterRTP, JustRTP, EzRTP, JakesRTP) to LeafRTP node conventions
 * by directly querying permission providers using configurable command templates.
 */
public class PermissionMigrationService {

    public static final String DEFAULT_GROUP_LIST_TEMPLATE = "lp listgroups";
    public static final String DEFAULT_GROUP_GET_TEMPLATE = "lp group [group] permission info";
    public static final String DEFAULT_GROUP_SET_TEMPLATE = "lp group [group] permission set [permission] [value] [contexts]";
    public static final String DEFAULT_GROUP_UNSET_TEMPLATE = "lp group [group] permission unset [permission] [contexts]";
    public static final String DEFAULT_USER_LIST_TEMPLATE = "lp listusers";
    public static final String DEFAULT_USER_GET_TEMPLATE = "lp user [user] permission info";
    public static final String DEFAULT_USER_SET_TEMPLATE = "lp user [user] permission set [permission] [value] [contexts]";
    public static final String DEFAULT_USER_UNSET_TEMPLATE = "lp user [user] permission unset [permission] [contexts]";

    private String groupListTemplate = DEFAULT_GROUP_LIST_TEMPLATE;
    private String groupGetTemplate = DEFAULT_GROUP_GET_TEMPLATE;
    private String groupSetTemplate = DEFAULT_GROUP_SET_TEMPLATE;
    private String groupUnsetTemplate = DEFAULT_GROUP_UNSET_TEMPLATE;
    private String userListTemplate = DEFAULT_USER_LIST_TEMPLATE;
    private String userGetTemplate = DEFAULT_USER_GET_TEMPLATE;
    private String userSetTemplate = DEFAULT_USER_SET_TEMPLATE;
    private String userUnsetTemplate = DEFAULT_USER_UNSET_TEMPLATE;

    public PermissionMigrationService() {
        loadTemplatesFromConfig();
    }

    /**
     * Attempts to read command templates from integrations.yml if present.
     */
    public void loadTemplatesFromConfig() {
        File pluginDir = null;
        if (RTP.configs != null && RTP.configs.pluginDirectory != null) {
            pluginDir = RTP.configs.pluginDirectory;
        } else if (RTP.serverAccessor != null) {
            pluginDir = RTP.serverAccessor.getPluginDirectory();
        }

        if (pluginDir == null) return;

        File[] candidateFiles = new File[]{
                new File(new File(pluginDir, "addons"), "integrations.yml"),
                new File(pluginDir, "integrations.yml")
        };

        for (File candidate : candidateFiles) {
            if (candidate.isFile()) {
                try {
                    String content = Files.readString(candidate.toPath());
                    RtpYamlConfig yaml = RtpYamlConfig.parse(content);
                    String gl = yaml.getString("permissions.command_templates.group_list");
                    String gg = yaml.getString("permissions.command_templates.group_get");
                    String gs = yaml.getString("permissions.command_templates.group_set");
                    String gu = yaml.getString("permissions.command_templates.group_unset");
                    String ul = yaml.getString("permissions.command_templates.user_list");
                    String ug = yaml.getString("permissions.command_templates.user_get");
                    String us = yaml.getString("permissions.command_templates.user_set");
                    String uu = yaml.getString("permissions.command_templates.user_unset");

                    if (gl != null && !gl.isBlank()) this.groupListTemplate = gl;
                    if (gg != null && !gg.isBlank()) this.groupGetTemplate = gg;
                    if (gs != null && !gs.isBlank()) this.groupSetTemplate = gs;
                    if (gu != null && !gu.isBlank()) this.groupUnsetTemplate = gu;
                    if (ul != null && !ul.isBlank()) this.userListTemplate = ul;
                    if (ug != null && !ug.isBlank()) this.userGetTemplate = ug;
                    if (us != null && !us.isBlank()) this.userSetTemplate = us;
                    if (uu != null && !uu.isBlank()) this.userUnsetTemplate = uu;
                    break;
                } catch (Exception e) {
                    RTP.log(Level.WARNING, "[RTP] Failed to load permissions templates from " + candidate, e);
                }
            }
        }
    }

    public String getGroupListTemplate() {
        return groupListTemplate;
    }

    public void setGroupListTemplate(String groupListTemplate) {
        this.groupListTemplate = groupListTemplate;
    }

    public String getGroupGetTemplate() {
        return groupGetTemplate;
    }

    public void setGroupGetTemplate(String groupGetTemplate) {
        this.groupGetTemplate = groupGetTemplate;
    }

    public String getGroupSetTemplate() {
        return groupSetTemplate;
    }

    public void setGroupSetTemplate(String groupSetTemplate) {
        this.groupSetTemplate = groupSetTemplate;
    }

    public String getGroupUnsetTemplate() {
        return groupUnsetTemplate;
    }

    public void setGroupUnsetTemplate(String groupUnsetTemplate) {
        this.groupUnsetTemplate = groupUnsetTemplate;
    }

    public String getUserListTemplate() {
        return userListTemplate;
    }

    public void setUserListTemplate(String userListTemplate) {
        this.userListTemplate = userListTemplate;
    }

    public String getUserGetTemplate() {
        return userGetTemplate;
    }

    public void setUserGetTemplate(String userGetTemplate) {
        this.userGetTemplate = userGetTemplate;
    }

    public String getUserSetTemplate() {
        return userSetTemplate;
    }

    public void setUserSetTemplate(String userSetTemplate) {
        this.userSetTemplate = userSetTemplate;
    }

    public String getUserUnsetTemplate() {
        return userUnsetTemplate;
    }

    public void setUserUnsetTemplate(String userUnsetTemplate) {
        this.userUnsetTemplate = userUnsetTemplate;
    }

    /**
     * Map a single competitor permission node to the corresponding LeafRTP node(s).
     */
    @NotNull
    public List<String> mapPermission(@Nullable String sourcePermission) {
        if (sourcePermission == null || sourcePermission.isBlank()) {
            return Collections.emptyList();
        }

        String lower = sourcePermission.trim().toLowerCase(Locale.ROOT);
        List<String> mapped = new ArrayList<>();

        // BetterRTP mapping
        if (lower.startsWith("betterrtp.")) {
            String suffix = lower.substring("betterrtp.".length());
            switch (suffix) {
                case "*":
                    mapped.add("rtp.*");
                    break;
                case "use":
                    mapped.add("rtp.use");
                    break;
                case "world":
                    mapped.add("rtp.world");
                    break;
                case "world.*":
                    mapped.add("rtp.worlds.*");
                    break;
                case "bypass.cooldown":
                    mapped.add("rtp.noCooldown");
                    break;
                case "bypass.delay":
                    mapped.add("rtp.noDelay");
                    break;
                case "bypass.economy":
                case "bypass.hunger":
                    mapped.add("rtp.free");
                    break;
                case "player":
                    mapped.add("rtp.other");
                    break;
                case "biome":
                    mapped.add("rtp.biome.*");
                    break;
                case "reload":
                    mapped.add("rtp.reload");
                    break;
                case "admin":
                    mapped.add("rtp.admin");
                    break;
                default:
                    if (suffix.startsWith("world.")) {
                        String worldName = suffix.substring("world.".length());
                        mapped.add("rtp.worlds." + worldName);
                    } else if (suffix.startsWith("biome.")) {
                        String biomeName = suffix.substring("biome.".length());
                        mapped.add("rtp.biome." + biomeName);
                    }
                    break;
            }
        }
        // JustRTP mapping
        else if (lower.startsWith("justrtp.")) {
            String suffix = lower.substring("justrtp.".length());
            switch (suffix) {
                case "*":
                    mapped.add("rtp.*");
                    break;
                case "use":
                case "rtp":
                    mapped.add("rtp.use");
                    break;
                case "world":
                    mapped.add("rtp.world");
                    break;
                case "world.*":
                    mapped.add("rtp.worlds.*");
                    break;
                case "biome":
                    mapped.add("rtp.biome");
                    break;
                case "biome.*":
                    mapped.add("rtp.biome.*");
                    break;
                case "bypass.cooldown":
                case "nocooldown":
                    mapped.add("rtp.noCooldown");
                    break;
                case "bypass.delay":
                case "nodelay":
                    mapped.add("rtp.noDelay");
                    break;
                case "bypass.cost":
                case "free":
                    mapped.add("rtp.free");
                    break;
                case "other":
                    mapped.add("rtp.other");
                    break;
                case "admin":
                    mapped.add("rtp.admin");
                    break;
                case "reload":
                    mapped.add("rtp.reload");
                    break;
                default:
                    if (suffix.startsWith("world.")) {
                        String worldName = suffix.substring("world.".length());
                        mapped.add("rtp.worlds." + worldName);
                    } else if (suffix.startsWith("biome.")) {
                        String biomeName = suffix.substring("biome.".length());
                        mapped.add("rtp.biome." + biomeName);
                    }
                    break;
            }
        }
        // EzRTP mapping
        else if (lower.startsWith("ezrtp.")) {
            String suffix = lower.substring("ezrtp.".length());
            switch (suffix) {
                case "*":
                    mapped.add("rtp.*");
                    break;
                case "use":
                case "rtp":
                    mapped.add("rtp.use");
                    break;
                case "world":
                    mapped.add("rtp.world");
                    break;
                case "world.*":
                    mapped.add("rtp.worlds.*");
                    break;
                case "bypass.cooldown":
                case "cooldown.bypass":
                    mapped.add("rtp.noCooldown");
                    break;
                case "bypass.delay":
                case "delay.bypass":
                    mapped.add("rtp.noDelay");
                    break;
                case "bypass.cost":
                case "cost.bypass":
                    mapped.add("rtp.free");
                    break;
                case "other":
                    mapped.add("rtp.other");
                    break;
                case "admin":
                    mapped.add("rtp.admin");
                    break;
                case "reload":
                    mapped.add("rtp.reload");
                    break;
                default:
                    if (suffix.startsWith("world.")) {
                        String worldName = suffix.substring("world.".length());
                        mapped.add("rtp.worlds." + worldName);
                    } else if (suffix.startsWith("biome.")) {
                        String biomeName = suffix.substring("biome.".length());
                        mapped.add("rtp.biome." + biomeName);
                    }
                    break;
            }
        }
        // JakesRTP mapping
        else if (lower.startsWith("jakesrtp.")) {
            String suffix = lower.substring("jakesrtp.".length());
            switch (suffix) {
                case "*":
                    mapped.add("rtp.*");
                    break;
                case "use":
                case "usebyname":
                    mapped.add("rtp.use");
                    break;
                case "nocooldown":
                case "bypass.cooldown":
                    mapped.add("rtp.noCooldown");
                    break;
                case "nowarmup":
                case "bypass.warmup":
                case "bypass.delay":
                    mapped.add("rtp.noDelay");
                    break;
                case "others":
                case "other":
                case "forcertp":
                    mapped.add("rtp.other");
                    break;
                case "rtpondeath":
                    mapped.add("rtp.onEvent.respawn");
                    break;
                case "admin":
                case "permpack.admin":
                    mapped.add("rtp.admin");
                    break;
                case "reload":
                    mapped.add("rtp.reload");
                    break;
                default:
                    if (suffix.startsWith("use.")) {
                        String target = suffix.substring("use.".length());
                        mapped.add("rtp.regions." + target);
                    } else if (suffix.startsWith("nocooldown.")) {
                        mapped.add("rtp.noCooldown");
                    } else if (suffix.startsWith("nowarmup.")) {
                        mapped.add("rtp.noDelay");
                    }
                    break;
            }
        }

        return Collections.unmodifiableList(mapped);
    }

    /**
     * String-parse the output lines of a group list command (e.g. {@code lp listgroups}).
     * Handles formats like:
     * - "Groups: default, admin, vip, moderator"
     * - "- default"
     * - "> default"
     * - "default (weight: 10)"
     *
     * @param lines raw string output lines
     * @return list of parsed group names
     */
    @NotNull
    public List<String> parseGroupListOutput(@Nullable Collection<String> lines) {
        if (lines == null || lines.isEmpty()) return Collections.emptyList();
        Set<String> groups = new LinkedHashSet<>();

        for (String raw : lines) {
            if (raw == null) continue;
            String line = cleanAnsiAndColors(raw).trim();
            if (line.isEmpty()) continue;

            // LuckPerms typical: "Groups: default, vip, admin"
            if (line.toLowerCase(Locale.ROOT).contains("groups:") || line.toLowerCase(Locale.ROOT).contains("groups -")) {
                int idx = line.indexOf(':');
                if (idx < 0) idx = line.indexOf('-');
                String rest = line.substring(idx + 1).trim();
                for (String token : rest.split("[,;\\s]+")) {
                    token = cleanToken(token);
                    if (!token.isEmpty()) groups.add(token);
                }
            } else if (line.startsWith("-") || line.startsWith("*") || line.startsWith(">")) {
                String token = line.substring(1).trim();
                // If token contains weight or metadata in parens e.g. "vip (weight: 10)", take first word
                if (token.contains("(")) {
                    token = token.substring(0, token.indexOf('(')).trim();
                }
                token = cleanToken(token);
                if (!token.isEmpty()) groups.add(token);
            }
        }

        return new ArrayList<>(groups);
    }

    private static String cleanToken(String token) {
        if (token == null) return "";
        // Remove trailing or leading parenthesis, brackets, quotes
        token = token.replaceAll("[()\\[\\]{}:,\"]", "").trim();
        // Skip metadata phrases
        if (token.equalsIgnoreCase("weight") || token.equalsIgnoreCase("inherited") || token.equalsIgnoreCase("group")) {
            return "";
        }
        return token;
    }

    private static String cleanAnsiAndColors(String s) {
        if (s == null) return "";
        // Strip Minecraft color codes (§x or &x)
        String c = s.replaceAll("[§&][0-9a-fk-orA-FK-OR]", "");
        // Strip ANSI escapes
        return c.replaceAll("\u001B\\[[;?0-9]*[a-zA-Z]", "");
    }

    /**
     * Represents a single parsed permission node with its value and context qualifier string.
     */
    public static class ParsedNode {
        private final String permission;
        private final boolean value;
        private final String contexts; // e.g. "world=world_nether server=survival"

        public ParsedNode(String permission, boolean value, String contexts) {
            this.permission = permission;
            this.value = value;
            this.contexts = contexts != null ? contexts.trim() : "";
        }

        public String getPermission() {
            return permission;
        }

        public boolean getValue() {
            return value;
        }

        public String getContexts() {
            return contexts;
        }
    }

    private static final Pattern LP_NODE_PATTERN = Pattern.compile(
            "([a-zA-Z0-9_.-]+)\\s*(?:\\((true|false)\\))?\\s*(?:[\\(\\[]([^\\]\\)]+)[\\)\\]])?"
    );

    /**
     * String-parse permission info output lines from a provider (e.g. {@code lp group <group> permission info}).
     * Extracts permission node, boolean value, and any attached contexts.
     */
    @NotNull
    public List<ParsedNode> parsePermissionInfoOutput(@Nullable Collection<String> lines) {
        if (lines == null || lines.isEmpty()) return Collections.emptyList();
        List<ParsedNode> result = new ArrayList<>();

        for (String raw : lines) {
            if (raw == null) continue;
            String line = cleanAnsiAndColors(raw).trim();
            if (line.isEmpty() || line.toLowerCase(Locale.ROOT).startsWith("page") || line.toLowerCase(Locale.ROOT).startsWith("showing")) {
                continue;
            }

            // Skip plugin headers like "[LP] default's Permissions:" or "Permissions:"
            if (line.endsWith(":") || line.toLowerCase(Locale.ROOT).contains("'s permissions") || line.toLowerCase(Locale.ROOT).contains("'s nodes")) {
                continue;
            }

            // Remove leading list bullets (+, -, >, *)
            if (line.startsWith("+") || line.startsWith("-") || line.startsWith(">") || line.startsWith("*")) {
                line = line.substring(1).trim();
            }

            // Remove LuckPerms shorthand flags like "d " or "g " if present
            if (line.matches("^[a-z]\\s+.*")) {
                line = line.substring(2).trim();
            }

            Matcher matcher = LP_NODE_PATTERN.matcher(line);
            if (matcher.find()) {
                String node = matcher.group(1);
                if (node == null || node.equalsIgnoreCase("nodes") || node.equalsIgnoreCase("permission") || node.equalsIgnoreCase("permissions")) {
                    continue;
                }

                boolean val = true;
                String valStr = matcher.group(2);
                if (valStr != null) {
                    val = Boolean.parseBoolean(valStr);
                }

                String contextsRaw = matcher.group(3);
                String contextsFormatted = "";
                if (contextsRaw != null && !contextsRaw.isBlank()) {
                    // Turn "world=nether, server=survival" or "world=nether server=survival" into "world=nether server=survival"
                    StringBuilder ctxBuilder = new StringBuilder();
                    for (String part : contextsRaw.split("[,;]+")) {
                        part = part.trim();
                        if (!part.isEmpty() && part.contains("=")) {
                            if (ctxBuilder.length() > 0) ctxBuilder.append(" ");
                            ctxBuilder.append(part);
                        }
                    }
                    contextsFormatted = ctxBuilder.toString();
                }

                result.add(new ParsedNode(node, val, contextsFormatted));
            }
        }

        return result;
    }

    /**
     * Build command for setting a user permission.
     */
    public String formatUserSet(String user, String permission, boolean value, String contexts) {
        String ctx = (contexts != null && !contexts.isBlank()) ? " " + contexts.trim() : "";
        return userSetTemplate
                .replace("[user]", user)
                .replace("[permission]", permission)
                .replace("[value]", String.valueOf(value))
                .replace("[contexts]", ctx)
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Build command for unsetting a user permission.
     */
    public String formatUserUnset(String user, String permission, String contexts) {
        String ctx = (contexts != null && !contexts.isBlank()) ? " " + contexts.trim() : "";
        return userUnsetTemplate
                .replace("[user]", user)
                .replace("[permission]", permission)
                .replace("[contexts]", ctx)
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Build command for setting a group permission.
     */
    public String formatGroupSet(String group, String permission, boolean value, String contexts) {
        String ctx = (contexts != null && !contexts.isBlank()) ? " " + contexts.trim() : "";
        return groupSetTemplate
                .replace("[group]", group)
                .replace("[permission]", permission)
                .replace("[value]", String.valueOf(value))
                .replace("[contexts]", ctx)
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Build command for unsetting a group permission.
     */
    public String formatGroupUnset(String group, String permission, String contexts) {
        String ctx = (contexts != null && !contexts.isBlank()) ? " " + contexts.trim() : "";
        return groupUnsetTemplate
                .replace("[group]", group)
                .replace("[permission]", permission)
                .replace("[contexts]", ctx)
                .replaceAll("\\s+", " ")
                .trim();
    }

    public String formatGroupGet(String group) {
        return groupGetTemplate.replace("[group]", group).trim();
    }

    public String formatUserGet(String user) {
        return userGetTemplate.replace("[user]", user).trim();
    }

    /**
     * Result of a permission migration inspection or execution.
     */
    public static class MigrationPlan {
        private final List<PermissionEntry> mappedEntries = new ArrayList<>();
        private final List<String> generatedCommands = new ArrayList<>();
        private final List<String> executedCommands = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private final boolean applied;

        public MigrationPlan(boolean applied) {
            this.applied = applied;
        }

        public void addEntry(PermissionEntry entry, String command) {
            mappedEntries.add(entry);
            generatedCommands.add(command);
        }

        public List<PermissionEntry> getMappedEntries() {
            return Collections.unmodifiableList(mappedEntries);
        }

        public List<String> getGeneratedCommands() {
            return Collections.unmodifiableList(generatedCommands);
        }

        public List<String> getExecutedCommands() {
            return executedCommands;
        }

        public List<String> getErrors() {
            return errors;
        }

        public boolean isApplied() {
            return applied;
        }
    }

    public static class PermissionEntry {
        private final String targetType; // "user" or "group"
        private final String targetName; // username or group name
        private final String sourcePermission;
        private final String targetPermission;
        private final boolean value;
        private final String contexts;

        public PermissionEntry(String targetType, String targetName, String sourcePermission, String targetPermission, boolean value, String contexts) {
            this.targetType = targetType;
            this.targetName = targetName;
            this.sourcePermission = sourcePermission;
            this.targetPermission = targetPermission;
            this.value = value;
            this.contexts = contexts != null ? contexts.trim() : "";
        }

        public String getTargetType() {
            return targetType;
        }

        public String getTargetName() {
            return targetName;
        }

        public String getSourcePermission() {
            return sourcePermission;
        }

        public String getTargetPermission() {
            return targetPermission;
        }

        public boolean getValue() {
            return value;
        }

        public String getContexts() {
            return contexts;
        }
    }

    /**
     * Scans parsed nodes for a given group or user and plans non-destructive append-only migrations.
     * Preserves world-specific and server-specific contexts.
     * Note: competitor nodes are NEVER unset.
     *
     * @param targetType "user" or "group"
     * @param targetName identifier
     * @param parsedNodes parsed nodes held by the target
     * @param sourceFilter optional filter (e.g. "betterrtp", "justrtp", "ezrtp")
     * @param apply whether to execute generated commands
     * @return MigrationPlan detailing mapped entries and commands
     */
    public MigrationPlan planMigration(String targetType,
                                      String targetName,
                                      Collection<ParsedNode> parsedNodes,
                                      @Nullable String sourceFilter,
                                      boolean apply) {
        MigrationPlan plan = new MigrationPlan(apply);
        if (parsedNodes == null || parsedNodes.isEmpty()) {
            return plan;
        }

        Set<String> existing = new HashSet<>();
        for (ParsedNode node : parsedNodes) {
            if (node != null && node.getPermission() != null) {
                existing.add(node.getPermission().toLowerCase(Locale.ROOT));
            }
        }

        for (ParsedNode node : parsedNodes) {
            if (node == null || node.getPermission() == null) continue;
            String lower = node.getPermission().trim().toLowerCase(Locale.ROOT);

            if (sourceFilter != null && !sourceFilter.isBlank()) {
                String sFilter = sourceFilter.trim().toLowerCase(Locale.ROOT);
                if (!lower.startsWith(sFilter)) {
                    continue;
                }
            }

            List<String> targetNodes = mapPermission(lower);
            for (String targetNode : targetNodes) {
                // If the target permission is not already explicitly present, schedule append-only set
                if (!existing.contains(targetNode.toLowerCase(Locale.ROOT))) {
                    String cmd;
                    if ("group".equalsIgnoreCase(targetType)) {
                        cmd = formatGroupSet(targetName, targetNode, node.getValue(), node.getContexts());
                    } else {
                        cmd = formatUserSet(targetName, targetNode, node.getValue(), node.getContexts());
                    }
                    PermissionEntry entry = new PermissionEntry(targetType, targetName, node.getPermission(), targetNode, node.getValue(), node.getContexts());
                    plan.addEntry(entry, cmd);
                }
            }
        }

        if (apply) {
            UUID consoleId = RTPAPI.serverId;
            for (String cmd : plan.getGeneratedCommands()) {
                boolean ok = false;
                if (RTP.serverAccessor != null) {
                    ok = RTP.serverAccessor.executeCommand(consoleId, cmd);
                }
                if (ok) {
                    plan.executedCommands.add(cmd);
                } else {
                    plan.errors.add("Failed to dispatch: " + cmd);
                }
            }
        }

        return plan;
    }
}
