package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

/**
 * Concrete {@code /rtp menu ...} command leaves for parameter pickers, selection menus,
 * config editors, and cart operations (ADR-050).
 */
final class MenuConcreteCommandLeavesB {

    private MenuConcreteCommandLeavesB() {}

    // -- shared parameter names ---------------------------------------------

    static final String P_PATH = "path";
    static final String P_PARAM = "param";
    static final String P_N = "n";
    static final String P_FILE = "file";
    static final String P_DIR = "dir";
    static final String P_KEY = "key";
    static final String P_TYPE = "type";
    static final String P_QUERY = "query";
    static final String P_PAGE = "page";
    static final String P_VALUE = "value";
    static final String P_SCOPE = "scope";
    static final String P_TEXT = "text";
    static final String P_KIND = "kind";
    static final String P_ENTRY = "entry";
    static final String P_OP = "op";
    static final String P_PREFILL = "prefill";
    static final String P_MODE = "mode";

    // -- helpers -----------------------------------------------------------

    private static String first(@Nullable Map<String, List<String>> p, String key) {
        if (p == null) return null;
        List<String> v = p.get(key);
        if (v == null || v.isEmpty()) return null;
        return v.get(0);
    }

    private static String[] splitDots(@Nullable String dotted) {
        if (dotted == null || dotted.isEmpty()) return new String[0];
        String[] parts = dotted.split("\\.");
        int kept = 0;
        for (String s : parts) if (s != null && !s.isEmpty()) kept++;
        String[] out = new String[kept];
        int i = 0;
        for (String s : parts) if (s != null && !s.isEmpty()) out[i++] = s;
        return out;
    }

    private static CommandParameter freeParam(String perm, String desc) {
        return new CommandParameter(perm, desc, (uuid, value) -> true) {
            @Override
            public Set<String> values() { return Collections.emptySet(); }
        };
    }

    private static CommandParameter posIntParam(String perm, String desc) {
        return new CommandParameter(perm, desc, (uuid, value) -> {
            if (value == null) return false;
            try { return Integer.parseInt(value) >= 1; }
            catch (NumberFormatException ignored) { return false; }
        }) {
            @Override
            public Set<String> values() { return Collections.emptySet(); }
        };
    }

    // -- /rtp menu picker path=<dotted> param=<name> -----------------------

    static final class PickerCmd extends BaseRTPCmdImpl {
        private final MenuRedeemSubcommand owner;
        PickerCmd(MenuRedeemSubcommand owner) {
            super(owner);
            this.owner = owner;
            addParameter(P_PATH, freeParam(MenuRedeemSubcommand.PERMISSION,
                    "dotted parent path under /rtp"));
            addParameter(P_PARAM, freeParam(MenuRedeemSubcommand.PERMISSION,
                    "parameter name on the target TreeCommand"));
        }
        @Override public String name() { return "picker"; }
        @Override public String permission() { return MenuRedeemSubcommand.PERMISSION; }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n) { return run(c, p, null); }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n,
                                           Consumer<String> m) { return run(c, p, m); }
        private boolean run(UUID c, Map<String, List<String>> p, @Nullable Consumer<String> m) {
            String[] path = splitDots(first(p, P_PATH));
            String param = first(p, P_PARAM);
            if (param == null || param.isEmpty()) {
                owner.rejectMenuInvalid(c,
                        "menu picker rejected: missing 'param' for " + c, m);
                return false;
            }
            return owner.dispatchOpenParamPicker(c,
                    new MenuAction.OpenParamPicker(path, param), m);
        }
    }

    // -- /rtp menu world|region|biome|prefab (curated selection menus) -----

    /**
     * Shared base for dedicated destination/prefab selection leaves.
     * Enumerate parameter values and delegates rendering to SelectionMenuBuilder.
     */
    abstract static class SelectionLeaf extends BaseRTPCmdImpl {
        private final MenuRedeemSubcommand owner;
        private final String[] parentPath;
        private final String paramName;
        private final String displayName;
        private final java.util.function.Function<String, String> colorSupplier;
        private final boolean executeOnClick;
        private final @Nullable MenuAction backAction;

        SelectionLeaf(MenuRedeemSubcommand owner,
                      String[] parentPath,
                      String paramName,
                      String displayName,
                      java.util.function.Function<String, String> colorSupplier,
                      boolean executeOnClick) {
            this(owner, parentPath, paramName, displayName, colorSupplier,
                    executeOnClick, null);
        }

        SelectionLeaf(MenuRedeemSubcommand owner,
                      String[] parentPath,
                      String paramName,
                      String displayName,
                      java.util.function.Function<String, String> colorSupplier,
                      boolean executeOnClick,
                      @Nullable MenuAction backAction) {
            super(owner);
            this.owner = owner;
            this.parentPath = parentPath;
            this.paramName = paramName;
            this.displayName = displayName;
            this.colorSupplier = colorSupplier;
            this.executeOnClick = executeOnClick;
            this.backAction = backAction;
        }

        @Override public String permission() { return MenuRedeemSubcommand.PERMISSION; }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n) { return run(c, null); }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n,
                                           Consumer<String> m) { return run(c, m); }

        private boolean run(UUID c, @Nullable Consumer<String> m) {
            return owner.dispatchSelectionMenu(c, parentPath, paramName,
                    displayName, colorSupplier, executeOnClick, backAction, m);
        }
    }

    /** {@code /rtp menu world} - pick a world, tinted by its resolved region. */
    static final class WorldCmd extends SelectionLeaf {
        WorldCmd(MenuRedeemSubcommand owner) {
            super(owner, new String[0], "world", "world",
                    MenuColor::worldRegionColorPrefix, true);
        }
        @Override public String name() { return "world"; }
    }

    /** {@code /rtp menu region} - pick a region, tinted by its biome mix. */
    static final class RegionCmd extends SelectionLeaf {
        RegionCmd(MenuRedeemSubcommand owner) {
            super(owner, new String[0], "region", "region",
                    MenuColor::regionColorPrefix, true);
        }
        @Override public String name() { return "region"; }
    }

    /** {@code /rtp menu biome} - pick a biome, tinted by its map color. */
    static final class BiomeCmd extends SelectionLeaf {
        BiomeCmd(MenuRedeemSubcommand owner) {
            super(owner, new String[0], "biome", "biome",
                    MenuColor::biomeColorPrefix, true);
        }
        @Override public String name() { return "biome"; }
    }

    /**
     * {@code /rtp menu prefab} - pick a bundled prefab id. Stages onto the
     * admin prefab-apply path and uses a uniform parchment-safe color (prefabs
     * carry no biome/map semantics).
     */
    static final class PrefabCmd extends SelectionLeaf {
        PrefabCmd(MenuRedeemSubcommand owner) {
            // The prefab picker stages values onto `admin prefab apply`, but is
            // reached from the admin panel; its Back row returns there rather
            // than looping onto the (non-navigable) value-staging path.
            super(owner, new String[]{"admin", "prefab", "apply"}, "id", "prefab",
                    null, false, new MenuAction.OpenAdminPanel());
        }
        @Override public String name() { return "prefab"; }
    }

    // -- /rtp menu page n=<n> ---------------------------------------------

    static final class PageCmd extends BaseRTPCmdImpl {
        private final MenuRedeemSubcommand owner;
        PageCmd(MenuRedeemSubcommand owner) {
            super(owner);
            this.owner = owner;
            addParameter(P_N, posIntParam(MenuRedeemSubcommand.PERMISSION,
                    "1-indexed page number"));
        }
        @Override public String name() { return "page"; }
        @Override public String permission() { return MenuRedeemSubcommand.PERMISSION; }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n) { return run(c, p, null); }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n,
                                           Consumer<String> m) { return run(c, p, m); }
        private boolean run(UUID c, Map<String, List<String>> p, @Nullable Consumer<String> m) {
            String raw = first(p, P_N);
            int idx;
            try { idx = (raw == null) ? 1 : Math.max(1, Integer.parseInt(raw)); }
            catch (NumberFormatException ex) { idx = 1; }
            return owner.openPage(c, null, idx, m);
        }
    }

    // -- /rtp menu config (selector + file/key/search variants) ------------

    /**
     * Single leaf for {@code /rtp menu config ...} handling selector, directory, file, and key paths.
     */
    static final class ConfigCmd extends BaseRTPCmdImpl {
        private final MenuRedeemSubcommand owner;
        ConfigCmd(MenuRedeemSubcommand owner) {
            super(owner);
            this.owner = owner;
            addParameter(P_DIR, freeParam(MenuRedeemSubcommand.PERMISSION, "config sub-directory path"));
            addParameter(P_FILE, freeParam(MenuRedeemSubcommand.PERMISSION, "config file name"));
            addParameter(P_KEY, freeParam(MenuRedeemSubcommand.PERMISSION, "parameter key"));
            addParameter(P_TYPE, freeParam(MenuRedeemSubcommand.PERMISSION, "sub-parameter type"));
            addParameter(P_QUERY, freeParam(MenuRedeemSubcommand.PERMISSION, "search query"));
            addParameter(P_PAGE, posIntParam(MenuRedeemSubcommand.PERMISSION, "1-indexed page"));
            addSubCommand(new ConfigSearchCmd(owner, this));
        }
        @Override public String name() { return "config"; }
        @Override public String permission() { return MenuRedeemSubcommand.PERMISSION; }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n) { return run(c, p, null); }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n,
                                           Consumer<String> m) { return run(c, p, m); }
        private boolean run(UUID c, Map<String, List<String>> p, @Nullable Consumer<String> m) {
            String file = first(p, P_FILE);
            String key = first(p, P_KEY);
            if (file == null || file.isEmpty()) {
                // ADR-071 rule 7: a bare `/rtp menu config` opens the root
                // directory; `dir=<subPath>` recurses into that directory.
                String dir = first(p, P_DIR);
                return owner.dispatchOpenConfigSelector(c,
                        new MenuAction.OpenConfigSelector(dir == null ? "" : dir), m);
            }
            // ADR-050: slash-bearing `file=<kind>/<entry>` is the synthetic
            // multi-config form (matches `MultiConfigMenuBuilder.buildEntry`
            // and the STAGE-confirm reopen from `AnvilInputSession`). Route
            // via `reopenAfterCartOp` so the entry page re-renders with the
            // cart surfaced, instead of `dispatchOpenConfigFile` rejecting
            // the slash-bearing name as unknown.
            if (key == null || key.isEmpty()) {
                if (file.indexOf('/') > 0) {
                    return owner.reopenAfterCartOp(c, file, m);
                }
                return owner.dispatchOpenConfigFile(c,
                        new MenuAction.OpenConfigFile(file), m);
            }
            return owner.dispatchOpenConfigKey(c,
                    new MenuAction.OpenConfigKey(file, key), m);
        }
    }

    /**
     * {@code /rtp menu config search [query=<q>] [page=<n>]} - bare opens
     * the search prompt; with a query opens the results page.
     */
    static final class ConfigSearchCmd extends BaseRTPCmdImpl {
        private final MenuRedeemSubcommand owner;
        ConfigSearchCmd(MenuRedeemSubcommand owner, CommandsAPICommand parent) {
            super(parent);
            this.owner = owner;
            addParameter(P_QUERY, freeParam(MenuRedeemSubcommand.PERMISSION, "search query"));
            addParameter(P_PAGE, posIntParam(MenuRedeemSubcommand.PERMISSION, "1-indexed page"));
        }
        @Override public String name() { return "search"; }
        @Override public String permission() { return MenuRedeemSubcommand.PERMISSION; }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n) { return run(c, p, null); }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n,
                                           Consumer<String> m) { return run(c, p, m); }
        private boolean run(UUID c, Map<String, List<String>> p, @Nullable Consumer<String> m) {
            String query = first(p, P_QUERY);
            if (query == null || query.isEmpty()) {
                return owner.dispatchOpenConfigSearchPrompt(c, m);
            }
            String pageRaw = first(p, P_PAGE);
            int page;
            try { page = (pageRaw == null) ? 0 : Math.max(0, Integer.parseInt(pageRaw) - 1); }
            catch (NumberFormatException ex) { page = 0; }
            return owner.dispatchOpenConfigSearchResults(c,
                    new MenuAction.OpenConfigSearchResults(query, page), m);
        }
    }

    // -- /rtp menu stage / unstage / apply / discard -----------------------

    static final class StageCmd extends BaseRTPCmdImpl {
        private final MenuRedeemSubcommand owner;
        StageCmd(MenuRedeemSubcommand owner) {
            super(owner);
            this.owner = owner;
            addParameter(P_FILE, freeParam(MenuRedeemSubcommand.PERMISSION, "config file name"));
            addParameter(P_KEY, freeParam(MenuRedeemSubcommand.PERMISSION, "parameter key"));
            addParameter(P_VALUE, freeParam(MenuRedeemSubcommand.PERMISSION, "value to stage"));
        }
        @Override public String name() { return "stage"; }
        @Override public String permission() { return MenuRedeemSubcommand.PERMISSION; }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n) { return run(c, p, null); }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n,
                                           Consumer<String> m) { return run(c, p, m); }
        private boolean run(UUID c, Map<String, List<String>> p, @Nullable Consumer<String> m) {
            String file = first(p, P_FILE);
            String key = first(p, P_KEY);
            String value = first(p, P_VALUE);
            if (file == null || file.isEmpty() || key == null || key.isEmpty()) {
                owner.rejectMenuInvalid(c,
                        "menu stage rejected: missing 'file' or 'key' for " + c, m);
                return false;
            }
            return owner.dispatchStageConfigValue(c,
                    new MenuAction.StageConfigValue(file, key, value == null ? "" : value), m);
        }
    }

    static final class UnstageCmd extends BaseRTPCmdImpl {
        private final MenuRedeemSubcommand owner;
        UnstageCmd(MenuRedeemSubcommand owner) {
            super(owner);
            this.owner = owner;
            addParameter(P_FILE, freeParam(MenuRedeemSubcommand.PERMISSION, "config file name"));
            addParameter(P_KEY, freeParam(MenuRedeemSubcommand.PERMISSION, "parameter key"));
        }
        @Override public String name() { return "unstage"; }
        @Override public String permission() { return MenuRedeemSubcommand.PERMISSION; }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n) { return run(c, p, null); }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n,
                                           Consumer<String> m) { return run(c, p, m); }
        private boolean run(UUID c, Map<String, List<String>> p, @Nullable Consumer<String> m) {
            String file = first(p, P_FILE);
            String key = first(p, P_KEY);
            if (file == null || file.isEmpty() || key == null || key.isEmpty()) {
                owner.rejectMenuInvalid(c,
                        "menu unstage rejected: missing 'file' or 'key' for " + c, m);
                return false;
            }
            return owner.dispatchUnstageConfigValue(c,
                    new MenuAction.UnstageConfigValue(file, key), m);
        }
    }

    static final class ApplyCmd extends BaseRTPCmdImpl {
        private final MenuRedeemSubcommand owner;
        ApplyCmd(MenuRedeemSubcommand owner) {
            super(owner);
            this.owner = owner;
            addParameter(P_FILE, freeParam(MenuRedeemSubcommand.PERMISSION, "config file name"));
        }
        @Override public String name() { return "apply"; }
        @Override public String permission() { return MenuRedeemSubcommand.PERMISSION; }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n) { return run(c, p, null); }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n,
                                           Consumer<String> m) { return run(c, p, m); }
        private boolean run(UUID c, Map<String, List<String>> p, @Nullable Consumer<String> m) {
            String file = first(p, P_FILE);
            if (file == null || file.isEmpty()) {
                owner.rejectMenuInvalid(c,
                        "menu apply rejected: missing 'file' for " + c, m);
                return false;
            }
            return owner.dispatchApplyStagedConfig(c,
                    new MenuAction.ApplyStagedConfig(file), m);
        }
    }

    static final class DiscardCmd extends BaseRTPCmdImpl {
        private final MenuRedeemSubcommand owner;
        DiscardCmd(MenuRedeemSubcommand owner) {
            super(owner);
            this.owner = owner;
            addParameter(P_FILE, freeParam(MenuRedeemSubcommand.PERMISSION, "config file name"));
        }
        @Override public String name() { return "discard"; }
        @Override public String permission() { return MenuRedeemSubcommand.PERMISSION; }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n) { return run(c, p, null); }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n,
                                           Consumer<String> m) { return run(c, p, m); }
        private boolean run(UUID c, Map<String, List<String>> p, @Nullable Consumer<String> m) {
            String file = first(p, P_FILE);
            if (file == null || file.isEmpty()) {
                owner.rejectMenuInvalid(c,
                        "menu discard rejected: missing 'file' for " + c, m);
                return false;
            }
            return owner.dispatchDiscardStagedConfig(c,
                    new MenuAction.DiscardStagedConfig(file), m);
        }
    }

    // -- /rtp menu info scope=<global|world:N|region:N> [text=true] -------

    static final class InfoCmd extends BaseRTPCmdImpl {
        private final MenuRedeemSubcommand owner;
        InfoCmd(MenuRedeemSubcommand owner) {
            super(owner);
            this.owner = owner;
            addParameter(P_SCOPE, freeParam(MenuRedeemSubcommand.PERMISSION,
                    "info scope: global | world:<name> | region:<name>"));
            addParameter(P_TEXT, freeParam(MenuRedeemSubcommand.PERMISSION,
                    "if true, render chat (text) instead of book"));
        }
        @Override public String name() { return "info"; }
        @Override public String permission() { return MenuRedeemSubcommand.PERMISSION; }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n) { return run(c, p, null); }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n,
                                           Consumer<String> m) { return run(c, p, m); }
        private boolean run(UUID c, Map<String, List<String>> p, @Nullable Consumer<String> m) {
            MenuAction.InfoScopeToken scope = parseScope(first(p, P_SCOPE));
            if (scope == null) {
                // Fall through to the helper which will reject with menuInvalid.
                scope = MenuAction.InfoScopeToken.global();
            }
            String text = first(p, P_TEXT);
            boolean asText = "true".equalsIgnoreCase(text) || "1".equals(text);
            if (asText) {
                return owner.dispatchSwitchInfoToText(c,
                        new MenuAction.SwitchInfoToText(scope), m);
            }
            return owner.dispatchOpenInfo(c, new MenuAction.OpenInfo(scope), m);
        }

        @Nullable
        private static MenuAction.InfoScopeToken parseScope(@Nullable String raw) {
            if (raw == null || raw.isEmpty()) return null;
            if ("global".equalsIgnoreCase(raw)) return MenuAction.InfoScopeToken.global();
            int colon = raw.indexOf(':');
            if (colon <= 0 || colon == raw.length() - 1) return null;
            String kind = raw.substring(0, colon);
            String name = raw.substring(colon + 1);
            if ("world".equalsIgnoreCase(kind)) return MenuAction.InfoScopeToken.world(name);
            if ("region".equalsIgnoreCase(kind)) return MenuAction.InfoScopeToken.region(name);
            return null;
        }
    }

    // -- /rtp menu anvil path=<dots> param=<name> [prefill=<s>] [mode=run|stage]

    /**
     * ADR-050 Stage 2: concrete leaf for {@link MenuAction.PromptAnvilInput}.
     * Routes into {@link MenuRedeemSubcommand#dispatchPromptAnvilInput}, which
     * opens the platform anvil GUI on the player. Wire grammar:
     * {@code /rtp menu anvil path=<dotted> param=<name> [prefill=<text>] [mode=run|stage]}.
     */
    static final class AnvilCmd extends BaseRTPCmdImpl {
        private final MenuRedeemSubcommand owner;
        AnvilCmd(MenuRedeemSubcommand owner) {
            super(owner);
            this.owner = owner;
            addParameter(P_PATH, freeParam(MenuRedeemSubcommand.PERMISSION,
                    "dotted parent path under /rtp"));
            addParameter(P_PARAM, freeParam(MenuRedeemSubcommand.PERMISSION,
                    "parameter name to prompt for"));
            addParameter(P_PREFILL, freeParam(MenuRedeemSubcommand.PERMISSION,
                    "initial anvil text"));
            addParameter(P_MODE, freeParam(MenuRedeemSubcommand.PERMISSION,
                    "run (execute /rtp on confirm) | stage (route into config-staging cart)"));
        }
        @Override public String name() { return "anvil"; }
        @Override public String permission() { return MenuRedeemSubcommand.PERMISSION; }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n) { return run(c, p, null); }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n,
                                           Consumer<String> m) { return run(c, p, m); }
        private boolean run(UUID c, Map<String, List<String>> p, @Nullable Consumer<String> m) {
            String[] path = splitDots(first(p, P_PATH));
            String param = first(p, P_PARAM);
            if (param == null || param.isEmpty()) {
                owner.rejectMenuInvalid(c,
                        "menu anvil rejected: missing 'param' for " + c, m);
                return false;
            }
            String prefill = first(p, P_PREFILL);
            if (prefill == null) prefill = "";
            String modeRaw = first(p, P_MODE);
            MenuAction.Mode mode = "stage".equalsIgnoreCase(modeRaw)
                    ? MenuAction.Mode.STAGE
                    : MenuAction.Mode.RUN;
            return owner.dispatchPromptAnvilInput(c,
                    new MenuAction.PromptAnvilInput(path, param, prefill, mode), m);
        }
    }

    // -- /rtp menu multi kind=<kind> [entry=<name>] [op=add|remove] -------

    static final class MultiCmd extends BaseRTPCmdImpl {
        private final MenuRedeemSubcommand owner;
        MultiCmd(MenuRedeemSubcommand owner) {
            super(owner);
            this.owner = owner;
            addParameter(P_KIND, freeParam(MenuRedeemSubcommand.PERMISSION,
                    "multi-config parser kind"));
            addParameter(P_ENTRY, freeParam(MenuRedeemSubcommand.PERMISSION,
                    "entry name within the kind"));
            addParameter(P_OP, freeParam(MenuRedeemSubcommand.PERMISSION,
                    "mutation op: add | remove"));
        }
        @Override public String name() { return "multi"; }
        @Override public String permission() { return MenuRedeemSubcommand.PERMISSION; }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n) { return run(c, p, null); }
        @Override public boolean onCommand(UUID c, Map<String, List<String>> p,
                                           @Nullable CommandsAPICommand n,
                                           Consumer<String> m) { return run(c, p, m); }
        private boolean run(UUID c, Map<String, List<String>> p, @Nullable Consumer<String> m) {
            String kind = first(p, P_KIND);
            String entry = first(p, P_ENTRY);
            String opRaw = first(p, P_OP);
            if (kind == null || kind.isEmpty()) {
                owner.rejectMenuInvalid(c,
                        "menu multi rejected: missing 'kind' for " + c, m);
                return false;
            }
            if (opRaw != null && !opRaw.isEmpty() && entry != null && !entry.isEmpty()) {
                MenuAction.MultiConfigMutate.Op op;
                if ("add".equalsIgnoreCase(opRaw)) op = MenuAction.MultiConfigMutate.Op.ADD;
                else if ("remove".equalsIgnoreCase(opRaw)) op = MenuAction.MultiConfigMutate.Op.REMOVE;
                else {
                    return owner.dispatchOpenMultiConfigSelector(c,
                            new MenuAction.OpenMultiConfigSelector(kind), m);
                }
                return owner.dispatchMultiConfigMutate(c,
                        new MenuAction.MultiConfigMutate(kind, entry, op), m);
            }
            if (entry != null && !entry.isEmpty()) {
                return owner.dispatchOpenMultiConfigEntry(c,
                        new MenuAction.OpenMultiConfigEntry(kind, entry), m);
            }
            return owner.dispatchOpenMultiConfigSelector(c,
                    new MenuAction.OpenMultiConfigSelector(kind), m);
        }
    }
}
