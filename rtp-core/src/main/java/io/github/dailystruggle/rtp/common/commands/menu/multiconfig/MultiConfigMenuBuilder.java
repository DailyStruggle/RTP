package io.github.dailystruggle.rtp.common.commands.menu.multiconfig;

import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.common.commands.menu.CommandTreeMenuBuilder;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Curated submenu page builder for the generic {@link MultiConfigParser MultiConfig}
 * surface (regions, worlds, and registered kinds).
 *
 * <p>Renders entry lists, entry config pages, and removal confirmation models
 * using concrete {@code /rtp menu ...} command actions.
 */
public final class MultiConfigMenuBuilder {

    /**
     * Optional delegate for flat-config editing parity (clickable keys, pagination, cart).
     * Falls back to display-only layout when unset.
     */
    private volatile @org.jetbrains.annotations.Nullable CommandTreeMenuBuilder commandTreeMenuBuilder;

    /**
     * Constructs a menu builder emitting concrete command actions.
     */
    public MultiConfigMenuBuilder() {
    }

    /**
     * Wire (or unwire) the {@link CommandTreeMenuBuilder} used by
     * {@link #buildEntry} to delegate per-entry rendering. Pass
     * {@code null} to disable delegation (legacy display-only layout).
     */
    public void setCommandTreeMenuBuilder(@org.jetbrains.annotations.Nullable CommandTreeMenuBuilder builder) {
        this.commandTreeMenuBuilder = builder;
    }

    /**
     * Build the entry-list page for a {@link MultiConfigParser} kind.
     *
     * @param parserKind  config kind (e.g. {@code "regions"}, {@code "worlds"})
     * @param parser      live parser providing entries
     * @param removeMode  whether viewer is currently in entry-removal mode
     * @param viewer      UUID of calling player
     * @return populated {@link MenuModel} for the selector
     */
    public MenuModel buildSelector(String parserKind,
                                   MultiConfigParser<?> parser,
                                   boolean removeMode,
                                   UUID viewer) {
        Objects.requireNonNull(parserKind, "parserKind");
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(viewer, "viewer");

        MultiConfigRemovalGuard guard = MultiConfigRemovalGuards.get(parserKind);

        List<MenuLine> lines = new ArrayList<>();

        String title = "&1&l\u2699 config " + parserKind;
        lines.add(MenuLine.of(new MenuFragment(title, null, null)));
        lines.add(MenuLine.of(new MenuFragment(
                "&8click an entry to edit; toggle remove-mode to delete",
                null, null)));
        lines.add(new MenuLine(List.of()));

        // Back row - returns to the admin panel.
        addRow(lines, "&1[back]",
                "return to the admin panel",
                new MenuAction.OpenAdminPanel());

        // Toggle row - flips remove-mode. Dispatcher reads/writes the
        // per-viewer flag and re-renders. Label reflects current state.
        String toggleLabel = removeMode
                ? "&c&l[remove-mode: on]"
                : "&2[remove-mode: off]";
        addRow(lines, toggleLabel,
                removeMode
                        ? "click an entry to remove it (locked entries are grayed)"
                        : "switch to remove-mode",
                new MenuAction.OpenMultiConfigSelector("!toggle:" + parserKind));

        // Add row: anvil prompt prefilled with next default name to create entry.
        // Lowercase parameter names match TreeCommand token parsing requirements.
        String seedName = nextDefaultName(parser.listParsers());
        addRow(lines, "&2&l[+ add new]",
                "type a name (default: \"" + seedName + "\")",
                new MenuAction.PromptAnvilInput(
                        new String[]{"multiaddkind=" + parserKind},
                        "multiadd", seedName));

        lines.add(new MenuLine(List.of()));

        // Sorted entry rows.
        Set<String> sorted = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        sorted.addAll(parser.listParsers());

        for (String entry : sorted) {
            boolean locked = guard.isLocked(entry);

            if (removeMode) {
                if (locked) {
                    // Locked: gray, non-clickable, hover carries the
                    // registered reason. No token minted.
                    String reason = guard.reason(entry);
                    lines.add(MenuLine.of(new MenuFragment(
                            "&8" + entry + " (locked)",
                            reason == null || reason.isEmpty() ? null : reason,
                            null)));
                } else {
                    addRow(lines, "&c" + entry,
                            "click to remove \"" + entry + "\"",
                            new MenuAction.MultiConfigMutate(
                                    parserKind, entry,
                                    MenuAction.MultiConfigMutate.Op.REMOVE));
                }
            } else {
                addRow(lines, "&1" + entry,
                        "edit \"" + entry + "\"",
                        new MenuAction.OpenMultiConfigEntry(parserKind, entry));
            }
        }

        MenuPage page = new MenuPage(lines);
        List<MenuPage> pages = List.of(page);
        // Emits concrete /rtp menu command actions directly.
        return new MenuModel(title, pages);
    }

    /**
     * Build the per-entry page for {@code entryName} inside {@code parserKind}.
     */
    public MenuModel buildEntry(String parserKind,
                                String entryName,
                                MultiConfigParser<?> parser,
                                UUID viewer) {
        return buildEntry(parserKind, entryName, parser, viewer,
                new java.util.LinkedHashMap<>());
    }

    /**
     * Cart-aware entry builder delegating to {@link CommandTreeMenuBuilder} when wired.
     *
     * <p>Surfaces clickable keys, pagination, and pending cart changes under
     * synthetic filename {@code "<parserKind>/<entryName>"}. Falls back to display-only.
     */
    public MenuModel buildEntry(String parserKind,
                                String entryName,
                                MultiConfigParser<?> parser,
                                UUID viewer,
                                java.util.LinkedHashMap<String, String> cartSnapshot) {
        Objects.requireNonNull(parserKind, "parserKind");
        Objects.requireNonNull(entryName, "entryName");
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(cartSnapshot, "cartSnapshot");
        if (entryName.isEmpty()) {
            throw new IllegalArgumentException("entryName must not be empty");
        }

        ConfigParser<?> entryParser = resolveEntryParser(parser, entryName);
        MultiConfigRemovalGuard guard = MultiConfigRemovalGuards.get(parserKind);

        // Delegation path. When the platform adapter wired a
        // CommandTreeMenuBuilder (rtp-plugin RTPCmdBukkit), reuse the
        // flat-config page builder so every editor feature (clickable
        // keys, pagination, staging cart) applies uniformly to
        // multiconfig entries. Post-processing rewrites Back + appends
        // the Remove row.
        CommandTreeMenuBuilder delegate = this.commandTreeMenuBuilder;
        if (delegate != null && entryParser != null) {
            String syntheticFileName = parserKind + "/" + entryName;
            MenuModel inner = invokeBuildConfigFile(
                    delegate, viewer, syntheticFileName, entryParser, cartSnapshot);
            if (inner != null) {
                String title = "&1&l\u2699 " + parserKind + " / " + entryName;
                List<MenuPage> rewritten = rewritePagesForEntry(
                        inner.pages(), parserKind, entryName, guard);
                mintTokens(rewritten, viewer);
                return new MenuModel(title, rewritten);
            }
        }

        // Fallback: legacy display-only layout. Surface tests that build
        // a MultiConfigMenuBuilder without wiring CommandTreeMenuBuilder
        // exercise this branch.
        List<MenuLine> lines = new ArrayList<>();

        // Title + Back. Back returns to the selector page for this kind.
        String title = "&1&l\u2699 " + parserKind + " / " + entryName;
        lines.add(MenuLine.of(new MenuFragment(title, null, null)));
        addRow(lines, "&1[back]",
                "return to the " + parserKind + " list",
                new MenuAction.OpenMultiConfigSelector(parserKind));
        lines.add(new MenuLine(List.of()));

        // Resolve the per-entry ConfigParser. If the entry is unknown
        // (race / forged token), surface a hint row rather than crashing
        // so the dispatcher's S-004 audit hook is the single reject path.
        // (entryParser already resolved above the delegation branch.)
        if (entryParser == null) {
            lines.add(MenuLine.of(new MenuFragment(
                    "&8(entry \"" + entryName + "\" not found)", null, null)));
        } else {
            renderEntryKeys(lines, entryParser);
        }

        // Remove row. Locked entries render gray + hover reason; otherwise
        // the row dispatches a REMOVE mutate, which the dispatcher (step 7)
        // routes through buildConfirmRemove.
        lines.add(new MenuLine(List.of()));
        boolean locked = guard.isLocked(entryName);
        if (locked) {
            String reason = guard.reason(entryName);
            lines.add(MenuLine.of(new MenuFragment(
                    "&8[remove] (locked)",
                    reason == null || reason.isEmpty() ? null : reason,
                    null)));
        } else {
            addRow(lines, "&c&l[remove]",
                    "delete \"" + entryName + "\" (asks for confirmation)",
                    new MenuAction.MultiConfigMutate(
                            parserKind, entryName,
                            MenuAction.MultiConfigMutate.Op.REMOVE));
        }

        MenuPage page = new MenuPage(lines);
        List<MenuPage> pages = List.of(page);
        mintTokens(pages, viewer);
        return new MenuModel(title, pages);
    }

    /**
     * Build removal confirmation page with confirm/cancel action rows.
     */
    public MenuModel buildConfirmRemove(String parserKind,
                                        String entryName,
                                        UUID viewer) {
        Objects.requireNonNull(parserKind, "parserKind");
        Objects.requireNonNull(entryName, "entryName");
        Objects.requireNonNull(viewer, "viewer");
        if (entryName.isEmpty()) {
            throw new IllegalArgumentException("entryName must not be empty");
        }

        List<MenuLine> lines = new ArrayList<>();
        String title = "&1&l\u2699 remove " + parserKind + ": &0" + entryName;
        lines.add(MenuLine.of(new MenuFragment(title, null, null)));
        lines.add(MenuLine.of(new MenuFragment(
                "&8this deletes the entry's yaml; click confirm to proceed",
                null, null)));
        lines.add(new MenuLine(List.of()));

        addRow(lines, "&c&l[confirm]",
                "delete \"" + entryName + "\"",
                new MenuAction.MultiConfigMutate(
                        parserKind, entryName,
                        MenuAction.MultiConfigMutate.Op.REMOVE));
        addRow(lines, "&2[cancel]",
                "return to the " + parserKind + " list",
                new MenuAction.OpenMultiConfigSelector(parserKind));

        MenuPage page = new MenuPage(lines);
        List<MenuPage> pages = List.of(page);
        mintTokens(pages, viewer);
        return new MenuModel(title, pages);
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Reflective bridge to {@link CommandTreeMenuBuilder#buildConfigFile}.
     * Isolates generic enum type parameters; returns null on invocation failure.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static @org.jetbrains.annotations.Nullable MenuModel invokeBuildConfigFile(
            CommandTreeMenuBuilder delegate,
            UUID viewer,
            String syntheticFileName,
            ConfigParser<?> entryParser,
            java.util.LinkedHashMap<String, String> cartSnapshot) {
        try {
            return ((CommandTreeMenuBuilder) delegate).buildConfigFile(
                    viewer, syntheticFileName,
                    (ConfigParser) entryParser, cartSnapshot);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Rewrites delegate pages: swaps back target to multiconfig selector and appends remove row.
     */
    private static List<MenuPage> rewritePagesForEntry(List<MenuPage> pages,
                                                       String parserKind,
                                                       String entryName,
                                                       MultiConfigRemovalGuard guard) {
        List<MenuPage> out = new ArrayList<>(pages.size());
        int lastIdx = pages.size() - 1;
        MenuAction backTarget = new MenuAction.OpenMultiConfigSelector(parserKind);
        for (int i = 0; i < pages.size(); i++) {
            MenuPage page = pages.get(i);
            List<MenuLine> rewritten = new ArrayList<>(page.lines().size() + 2);
            for (MenuLine line : page.lines()) {
                List<MenuFragment> frags = line.fragments();
                boolean changed = false;
                List<MenuFragment> newFrags = new ArrayList<>(frags.size());
                for (MenuFragment f : frags) {
                    if (f.action() instanceof MenuAction.OpenConfigSelector) {
                        newFrags.add(new MenuFragment(
                                f.text(), f.hover(), backTarget));
                        changed = true;
                    } else {
                        newFrags.add(f);
                    }
                }
                rewritten.add(changed ? new MenuLine(newFrags) : line);
            }
            if (i == lastIdx) {
                rewritten.add(new MenuLine(List.of()));
                boolean locked = guard.isLocked(entryName);
                if (locked) {
                    String reason = guard.reason(entryName);
                    rewritten.add(MenuLine.of(new MenuFragment(
                            "&8[remove] (locked)",
                            reason == null || reason.isEmpty() ? null : reason,
                            null)));
                } else {
                    rewritten.add(MenuLine.of(new MenuFragment(
                            "&c&l[remove]",
                            "delete \"" + entryName + "\" (asks for confirmation)",
                            new MenuAction.MultiConfigMutate(
                                    parserKind, entryName,
                                    MenuAction.MultiConfigMutate.Op.REMOVE))));
                }
            }
            out.add(new MenuPage(rewritten));
        }
        return out;
    }

    private static void addRow(List<MenuLine> lines,
                               String label,
                               String hover,
                               MenuAction action) {
        if (label == null || label.isEmpty()) return;
        lines.add(MenuLine.of(new MenuFragment(label, hover, action)));
    }

    /**
     * Synthesize a {@code default&lt;N&gt;} name not present in
     * {@code existing}. {@code N} is incremented from 1 until a free
     * slot is found; the literal name {@code "default"} is treated as
     * occupied even when absent so the seed always reads as a copy.
     */
    static String nextDefaultName(Set<String> existing) {
        Set<String> ci = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (existing != null) ci.addAll(existing);
        int n = 1;
        while (true) {
            String candidate = "default" + n;
            if (!ci.contains(candidate)) return candidate;
            n++;
        }
    }

    /**
     * Look up the per-entry {@link ConfigParser} case-insensitively.
     * {@link MultiConfigParser#getParser(String)} expects the on-disk
     * name; tolerate case drift from the click token because file-name
     * casing is filesystem-dependent.
     */
    private static ConfigParser<?> resolveEntryParser(MultiConfigParser<?> parser,
                                                      String entryName) {
        // listParsers() is authoritative for what is loaded; trust it and
        // tolerate case drift from the click token (filesystem-dependent).
        // Avoid calling getParser() for unknown names because the
        // MultiConfigParser.getParser fallback path queries the underlying
        // Factory which throws IllegalStateException when the factory map
        // is empty (no template registered) - we want a clean "not found"
        // sentinel here, not a thrown exception.
        String target = entryName.toLowerCase(Locale.ROOT);
        for (String candidate : parser.listParsers()) {
            if (candidate == null) continue;
            if (candidate.toLowerCase(Locale.ROOT).equals(target)) {
                try {
                    return parser.getParser(candidate);
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Render one non-clickable display row per enum key in
     * {@code entryParser}, label {@code "<key>: <currentValue>"}. Empty
     * parsers produce a single empty-state hint row. Click-to-edit is
     * deferred to step 7 (cart scope widening to
     * {@code (parserKind, entryName, file)}).
     */
    private static <E extends Enum<E>> void renderEntryKeys(List<MenuLine> lines,
                                                            ConfigParser<E> entryParser) {
        E[] enumValues = entryParser.myClass.getEnumConstants();
        EnumMap<E, Object> loaded = entryParser.getData();
        boolean any = false;
        if (enumValues != null) {
            for (E key : enumValues) {
                if (key == null) continue;
                if (!loaded.containsKey(key)) continue;
                if ("VERSION".equals(key.name().toUpperCase(Locale.ROOT))) continue;
                Object current = loaded.get(key);
                String currentStr = current == null
                        ? "&8(unset)"
                        : String.valueOf(current);
                String label = "&2" + key.name() + "&7: &0" + currentStr;
                lines.add(MenuLine.of(new MenuFragment(label, null, null)));
                any = true;
            }
        }
        if (!any) {
            lines.add(MenuLine.of(new MenuFragment(
                    "&8(no editable keys in this entry)", null, null)));
        }
    }

    /**
     * ADR-050 Stage 3β.D.2b (2026-05-24): no-op. The renderer emits concrete
     * {@code /rtp menu ...} commands; no token is consulted. Method retained
     * to keep call sites mechanical; remove in a follow-up cleanup once all
     * three call sites are also pruned.
     */
    @SuppressWarnings("unused")
    private void mintTokens(List<MenuPage> pages, UUID viewer) {
    }
}
