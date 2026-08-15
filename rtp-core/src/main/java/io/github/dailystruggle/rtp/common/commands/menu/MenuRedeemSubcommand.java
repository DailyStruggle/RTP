package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuConsumerProfile;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuOpenRequest;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.maps.MapDispatch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;

import org.jetbrains.annotations.Nullable;
/**
 * The {@code /rtp menu:<token>} redeem subcommand (ADR-035 §3,
 * CHECKLIST-generalized-menu.md item 2.2).
 *
 * <p>This subcommand registers under the {@code /rtp} root with literal name
 * {@code "menu"}; the actual redeem invocation arrives as
 * {@code /rtp menu:<token>} which the {@code commands-api} parser splits via
 * {@link TreeCommand#splitOnParamDelimiter(String)} into the {@code menu}
 * leaf + a {@code token} parameter value. Equivalent forms
 * ({@code /rtp menu <token>} or {@code /rtp menu token:<value>}) are accepted
 * by the same dispatch path so end-users and renderers can both reach it.
 *
 * <p>Behaviour on receipt (in order):
 *
 * <ol>
 *   <li>Resolve the token from the parsed parameter values.</li>
 *   <li>(ADR-050 Stage 3β.D.2b) The token consume path is gone; click
 *       events now carry concrete {@code /rtp menu ...} commands and the
 *       ownership check is implicit in the leaf's permission gate.</li>
 *   <li>On failure: route the configurable {@code menu.invalid} / {@code menu.expired}
 *       message through {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor#sendMessage}
 *       and log a {@link Level#WARNING} entry via {@link RTP#log} per
 *       REQ-RTP-S-004 / REQ-RTP-S-007 / REQ-RTP-F-013. Never silently swallow.</li>
 *   <li>On success: dispatch the stored {@link MenuAction}. Per ADR-035 §3 only
 *       {@link MenuAction.RunRtpCommand} reaches the redeem path
 *       (the other variants are renderer-resolved click effects); a non-Run
 *       action is treated as a protocol error and rejected through the same
 *       {@code menu.invalid} path.</li>
 * </ol>
 *
 * <p>Threading: the dispatch chains directly into the {@code /rtp} root's
 * {@code onCommand(args)} so S-005 (no main-thread chunk I/O) is preserved
 * end-to-end — the chunk-I/O happens inside the live teleport pipeline, not
 * inside redeem.
 */
public final class MenuRedeemSubcommand extends BaseRTPCmdImpl {

    /** Permission required to redeem a menu token. Reuses {@code rtp.menu}. */
    public static final String PERMISSION = "rtp.menu";

    /**
     * Permission required to dispatch the curated config subtree variants
     * (PROPOSAL-config-view-as-book.md v3.7.1). Missing permission yields an
     * S-004 reject path on inbound {@link MenuAction.OpenConfigSelector} /
     * {@link MenuAction.OpenConfigFile} / {@link MenuAction.OpenConfigKey}.
     */
    public static final String CONFIG_VIEW_PERMISSION = "rtp.config.view";

    /**
     * Permission required to open the curated admin panel
     * (PROPOSAL-admin-panel.md v2). Missing permission yields an S-004 reject
     * path on an inbound {@link MenuAction.OpenAdminPanel}.
     */
    public static final String ADMIN_MENU_PERMISSION = "rtp.menu.admin";

    /**
     * Parameter names used when the anvil-input "+ add new" prompt on the
     * multiconfig selector page confirms. The platform synthesizes
     * {@code /rtp menu multiaddKind=<kind> multiadd=<typedName>}; the
     * {@link #dispatch} early branch detects this pair and routes it to
     * {@link #dispatchMultiConfigMutate} with op = ADD.
     */
    public static final String PARAM_MULTIADD = "multiadd";
    // Lowercase key: commands-api's TreeCommand parameter parser
    // lowercases the token name before lookup (TreeCommand.java:277,
    // `argSplit[0].toLowerCase()`), so the registered key MUST be
    // all-lowercase. A camelCase key registers fine but is unreachable
    // -- the lookup returns null and msgBadParameter fires with the
    // submitted form ("invalid command argument multiaddKind=regions"
    // observed 2026-05-23). Keep this lowercase to match the parser.
    public static final String PARAM_MULTIADD_KIND = "multiaddkind";

    /**
     * Parameter name used when the user (or a renderer's pagination click)
     * types {@code /rtp menu page:<n>} (CHECKLIST item 5.3.b, D-005 approved
     * 2026-05-15). Values are 1-indexed on the wire and translated to a
     * zero-based {@link MenuOpenRequest#pageIndex()} before reaching the page
     * builder. Default (parameter absent or unparseable) is page 1 / index 0.
     */
    public static final String PARAM_PAGE = "page";

    private final TreeCommand rtpRoot;
    private final java.util.function.Function<UUID, Predicate<String>> permissionProbeFactory;

    /**
     * Package-private accessor for the permission probe factory installed at
     * construction. Exposed so collaborator classes in the same package (e.g.
     * {@link VisualizationDispatch}) can build their own permission gates
     * without holding a back-reference to this subcommand. Intentionally
     * narrow: callers should prefer constructing a {@link MenuPermissionGates}
     * from the returned function rather than calling the probe directly.
     */
    java.util.function.Function<UUID, Predicate<String>> permissionProbeFactory() {
        return permissionProbeFactory;
    }
    /**
     * ADR-050 menu-package split: shared permission-gate helper. Bound to
     * {@link #permissionProbeFactory} at construction. The three legacy
     * {@code hasXxxPermission(UUID)} methods on this class now delegate
     * here; the menu leaves (under this same package) read it directly.
     */
    final MenuPermissionGates permissionGates;
    /** Optional renderer for the no-token open-page path (CHECKLIST item 3.2 / 4.2). */
    private final @Nullable MenuRenderer renderer;
    /**
     * Optional page builder for the open-page path. Signature:
     * {@code (node, openRequest, assembledPath) -> MenuModel}. Decoupling via a SAM
     * keeps {@code rtp-core} free of a direct {@code CommandTreeMenuBuilder}
     * field — callers supply a closure binding the live {@link Predicate} +
     * {@link MenuConsumerProfile}. The {@code assembledPath} is the list of
     * args from the {@code /rtp} root down to (and including) {@code node},
     * e.g. {@code ["config", "performance"]} for {@code /rtp config performance};
     * an empty list opens the root menu page. The builder uses the path to
     * emit Back / Execute navigation rows pointing at the correct sibling /
     * parent / self command.
     */
    private final @Nullable MenuPageBuilder pageBuilder;
    /**
     * Stage A.2 (optional): parameter-value picker builder. When present and
     * the consumed {@link MenuAction} is {@link MenuAction.OpenParamPicker},
     * dispatch routes through this SAM to render the picker sub-page. When
     * {@code null}, an inbound {@code OpenParamPicker} is treated as a
     * protocol error (S-004 reject + WARN), matching the existing
     * {@link MenuAction.OpenMenu} behaviour when {@link #pageBuilder} is
     * absent.
     */
    private final @Nullable MenuParamPickerBuilder paramPickerBuilder;
    /**
     * ADR-045 (optional): platform-side hook to open an anvil GUI for the
     * "type a custom value..." picker row. When present, an inbound
     * {@link MenuAction.PromptAnvilInput} token is dispatched here; on confirm
     * the platform synthesizes
     * {@code /rtp <parentPath...> <paramName>:<typed>} as the player. When
     * {@code null} (Spigot without Adventure, Fabric, test scaffolds), an
     * inbound {@code PromptAnvilInput} is treated as a protocol error
     * (S-004 reject + WARN) — same fallback shape as the absent
     * {@link #paramPickerBuilder}.
     */
    private final @Nullable AnvilInputOpener anvilInputOpener;
    /**
     * PROPOSAL-config-view-as-book.md v3.7 (optional): builder for the three
     * curated config-subtree pages (selector / per-file / per-key). When
     * present and the consumed {@link MenuAction} is one of the
     * {@code OpenConfig*} variants, dispatch routes through this SAM. When
     * {@code null}, an inbound {@code OpenConfig*} action is treated as a
     * protocol error (S-004 reject + WARN), matching the absent
     * {@link #paramPickerBuilder} fallback shape.
     */
    private final @Nullable MenuConfigSubtreeBuilder configSubtreeBuilder;
    /**
     * PROPOSAL-admin-panel.md v2 (optional): builder for the two curated
     * no-payload pages ({@link MenuAction.OpenAdminPanel},
     * {@link MenuAction.OpenFrontPage}). When present and the consumed
     * {@link MenuAction} is one of those two, dispatch routes through this
     * SAM. When {@code null}, an inbound admin/front-page action is treated
     * as a protocol error (S-004 reject + WARN), matching the absent
     * {@link #configSubtreeBuilder} fallback shape.
     */
    private final @Nullable MenuCuratedPageBuilder curatedPageBuilder;
    /**
     * PROPOSAL-rtp-menu-config-search.md (optional): builder for the
     * config-search results page. When present and the consumed
     * {@link MenuAction} is {@link MenuAction.OpenConfigSearchResults},
     * dispatch routes through this SAM. When {@code null}, the action is
     * treated as a protocol error (S-004 reject + WARN). The
     * {@link MenuAction.OpenConfigSearchPrompt} variant routes through the
     * existing {@link #anvilInputOpener} (Q4 Decision A) and does not
     * require this builder to be wired.
     */
    private final @Nullable MenuConfigSearchBuilder configSearchBuilder;
    /**
     * PROPOSAL-info-as-book.md section 4.6 (optional): builder for the
     * {@code /rtp info} book pages. When present and the consumed
     * {@link MenuAction} is {@link MenuAction.OpenInfo}, dispatch routes
     * through this SAM. When {@code null}, an inbound {@code OpenInfo}
     * action is treated as a protocol error (S-004 reject + WARN), matching
     * the absent {@link #configSubtreeBuilder} fallback shape. The companion
     * {@link MenuAction.SwitchInfoToText} variant routes through the existing
     * {@code /rtp info} chat path (no builder dependency) and so does not
     * require this field to be wired.
     */
    private final @Nullable MenuInfoBookBuilder infoBookBuilder;

    /**
     * Per-player single-file config staging cart. Keyed by viewer UUID;
     * value is a {@link Cart} carrying the file name and an insertion-ordered
     * {@code key -> value} map. Mutated only through
     * {@link #stageInCart(UUID, String, String, String)},
     * {@link #unstageInCart(UUID, String, String)},
     * {@link #applyCart(UUID, String)}, and {@link #clearCart(UUID)} so the
     * insertion order survives concurrent reads (the inner map is wrapped in
     * a synchronized block per access; reads return defensive copies). The
     * cart is dropped on return to the file selector
     * ({@link MenuAction.OpenConfigSelector} dispatch), on a successful
     * apply, on an explicit discard, and shall be dropped by the renderer on
     * player disconnect via {@link #clearCart(UUID)}.
     */
    private final Map<UUID, Cart> carts = new ConcurrentHashMap<>();

    /**
     * CHECKLIST-multiconfig-menu step 7: per-viewer remove-mode flag set,
     * keyed by viewer UUID, value is the set of {@code parserKind} strings
     * the viewer currently has remove-mode toggled on for. Mutated only by
     * {@link #dispatchOpenMultiConfigSelector} when the toggle row is
     * clicked. Cleared on a successful REMOVE / ADD mutation and on viewer
     * disconnect (best-effort - the registry's TTL evict eventually drops
     * stale entries even if disconnect is missed).
     */
    private final Map<UUID, Set<String>> removeModeKinds = new ConcurrentHashMap<>();

    /**
     * CHECKLIST-multiconfig-menu step 7 (optional): builder for the three
     * MultiConfig submenu pages (selector / per-entry / confirm-remove).
     * Different from the other builder SAMs above because the builder is a
     * concrete class living in {@code rtp-core} (no platform indirection
     * needed); injected via {@link #setMultiConfigBuilder} so we do not
     * grow the constructor overload chain by another arm. When {@code null},
     * the three new {@link MenuAction.OpenMultiConfigSelector} /
     * {@link MenuAction.OpenMultiConfigEntry} /
     * {@link MenuAction.MultiConfigMutate} arms reject with
     * {@code menuInvalid} + WARN (S-004), matching the absent-builder
     * fallback shape of {@link #configSubtreeBuilder} and friends.
     */
    private volatile io.github.dailystruggle.rtp.common.commands.menu.multiconfig.MultiConfigMenuBuilder multiConfigBuilder;

    // {@link Cart} moved to its own file as the first step of the
    // ADR-050 menu-package split. See {@code Cart.java} in this package.

    /**
     * Returns the {@link CartSink} bound to this subcommand instance. The
     * sink is stable across the lifetime of the subcommand; renderer-side
     * wiring code may capture the reference at plugin enable and pass it
     * to the platform {@link AnvilInputOpener}.
     */
    public CartSink cartSink() {
        return this::stageInCart;
    }

    /**
     * Normalize a config file name to the bare lowercase basename used as
     * the cart key. The selector emits bare names ({@code "config"}) while
     * the mirror walker / STAGE-confirm reopen carries the suffixed segment
     * ({@code "config.yml"}); both must resolve to the same cart bucket so
     * the Pending list survives a STAGE-mode anvil round-trip. Tolerates
     * {@code null} for callers that pre-checked (returns {@code null}).
     */
    private static String normalizeCartFileName(String fileName) {
        if (fileName == null) return null;
        String n = fileName.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".yml")) n = n.substring(0, n.length() - 4);
        return n;
    }

    /**
     * Stage (or replace) a single {@code paramName=value} entry in
     * {@code viewer}'s cart for {@code fileName}. If the existing cart was
     * scoped to a different file the entire cart is replaced. Visible to
     * tests and to the {@link CartSink} returned by {@link #cartSink()}.
     */
    public void stageInCart(UUID viewer, String fileName, String paramName, String value) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(paramName, "paramName");
        Objects.requireNonNull(value, "value");
        final String key = normalizeCartFileName(fileName);
        carts.compute(viewer, (k, existing) -> {
            Cart c = (existing != null && existing.fileName.equals(key))
                    ? existing : new Cart(key);
            synchronized (c.entries) {
                c.entries.put(paramName, value);
            }
            return c;
        });
    }

    /**
     * Remove {@code paramName} from {@code viewer}'s cart for {@code fileName}.
     * No-op when the cart is empty, scoped to a different file, or does not
     * contain {@code paramName}. Empties the cart entry when the last key is
     * removed.
     */
    public void unstageInCart(UUID viewer, String fileName, String paramName) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(paramName, "paramName");
        final String key = normalizeCartFileName(fileName);
        carts.computeIfPresent(viewer, (k, c) -> {
            if (!c.fileName.equals(key)) return c;
            synchronized (c.entries) {
                c.entries.remove(paramName);
                if (c.entries.isEmpty()) return null;
            }
            return c;
        });
    }

    /**
     * Drop {@code viewer}'s cart, if any. Called on return to the file
     * selector, on a successful Apply, on an explicit Discard, and by the
     * renderer on player disconnect.
     */
    public void clearCart(UUID viewer) {
        if (viewer == null) return;
        carts.remove(viewer);
    }

    /**
     * Snapshot {@code viewer}'s cart entries for {@code fileName} as an
     * insertion-ordered defensive copy. Returns an empty map when the cart
     * is empty or scoped to a different file. Safe for concurrent callers.
     */
    public LinkedHashMap<String, String> snapshotCart(UUID viewer, String fileName) {
        Cart c = carts.get(viewer);
        final String key = normalizeCartFileName(fileName);
        if (c == null || !c.fileName.equals(key)) return new LinkedHashMap<>();
        synchronized (c.entries) {
            return new LinkedHashMap<>(c.entries);
        }
    }

    /**
     * Atomically pop {@code viewer}'s cart for {@code fileName} (clearing it)
     * and return its entries. Returns an empty map when the cart is empty or
     * scoped to a different file.
     */
    private LinkedHashMap<String, String> applyCart(UUID viewer, String fileName) {
        Cart c = carts.remove(viewer);
        final String key = normalizeCartFileName(fileName);
        if (c == null || !c.fileName.equals(key)) {
            if (c != null) carts.put(viewer, c); // restore: not our cart
            return new LinkedHashMap<>();
        }
        synchronized (c.entries) {
            return new LinkedHashMap<>(c.entries);
        }
    }

    /**
     * SAM signature for {@link #pageBuilder}; declared at the class level so
     * callers (notably {@code RTPCmdBukkit}) can implement it without
     * importing a {@code TriFunction} from a utility package.
     */
    @FunctionalInterface
    public interface MenuPageBuilder {
        /**
         * @param node          the {@link TreeCommand} node to reflect.
         * @param open          the open request, bundling viewer UUID with
         *                      the zero-based {@code pageIndex} requested by
         *                      the click (or the wire-level {@code page:<n>}
         *                      parameter). Builders that produce a single page
         *                      may ignore the index; pagination-capable
         *                      builders (chat-style renderers) should clamp
         *                      to the model's actual page count.
         * @param assembledPath args from the {@code /rtp} root down to (and
         *                      including) {@code node}; empty = root page.
         */
        MenuModel build(TreeCommand node, MenuOpenRequest open, java.util.List<String> assembledPath);
    }

    /**
     * SAM signature for {@link #paramPickerBuilder} (Stage A.2). Implementers
     * reflect {@code parent}'s parameter {@code paramName} into a value-picker
     * {@link MenuModel}. {@code parentPath} is the args from the {@code /rtp}
     * root down to (and including) {@code parent}.
     */
    @FunctionalInterface
    public interface MenuParamPickerBuilder {
        MenuModel build(TreeCommand parent,
                        UUID viewer,
                        java.util.List<String> parentPath,
                        String paramName);
    }

    /**
     * SAM signature for {@link #anvilInputOpener} (ADR-045). Implementations
     * open an anvil GUI on the {@code viewer} player, prefill the rename slot
     * with {@code prefill}, and on confirm submit
     * {@code /rtp <parentPath...> <paramName>:<typed>} as the player. On
     * cancel (inventory closed without confirm) the implementation is a no-op.
     *
     * <p>Must be S-005-safe: no synchronous chunk I/O. On Folia the
     * implementation shall dispatch player-affecting operations via the
     * player's {@code EntityScheduler}.
     */
    @FunctionalInterface
    public interface AnvilInputOpener {
        /**
         * Back-compatible RUN-only entrypoint. This is the sole abstract
         * method so that simple test scaffolds and pre-staging-cart
         * implementers can target it as a SAM with the legacy 4-arg lambda
         * shape {@code (uuid, parentPath, paramName, prefill) -> ...}. The
         * historical contract stands: on confirm, submit
         * {@code /rtp <parentPath...> <paramName>=<typed>} as the player.
         *
         * @return {@code true} if the anvil GUI was opened, {@code false} if the
         *         platform refused (player offline, no inventory subsystem, etc).
         *         The caller treats {@code false} as an S-004 reject path.
         */
        boolean open(UUID viewer,
                     java.util.List<String> parentPath,
                     String paramName,
                     String prefill);

        /**
         * Mode-aware overload (added by the config staging-cart redesign).
         * Implementations that support STAGE mode shall override this
         * method and honor it as follows:
         * <ul>
         *   <li>{@link MenuAction.Mode#RUN} - same as the 4-arg overload.</li>
         *   <li>{@link MenuAction.Mode#STAGE} - on confirm, invoke
         *       {@code sink.stage(viewer, fileName, paramName, value)} (file
         *       name derived from {@code parentPath[1]} when
         *       {@code parentPath[0]} is {@code config}) and reopen the
         *       curated {@code /rtp config <file>} page so the staged entry
         *       surfaces under "Pending changes". See the config staging-cart
         *       redesign tracked at
         *       {@code docs/dev/scratch/CHECKLIST-config-staging-cart.md}.</li>
         * </ul>
         * The default delegates to the 4-arg overload, ignoring {@code mode}
         * and {@code sink}. Test scaffolds that only need RUN behavior need
         * not override.
         *
         * @param sink the cart sink, non-null when {@code mode == STAGE};
         *             may be null for {@code mode == RUN}.
         */
        default boolean open(UUID viewer,
                             java.util.List<String> parentPath,
                             String paramName,
                             String prefill,
                             MenuAction.Mode mode,
                             @Nullable CartSink sink) {
            return open(viewer, parentPath, paramName, prefill);
        }

        /**
         * Bind the {@link CartSink} this opener should invoke on STAGE-mode
         * confirm. Wiring code (e.g. {@code RTPCmdBukkit#selectAnvilOpener})
         * calls this once after {@link MenuRedeemSubcommand} has been
         * constructed, passing {@code redeem.cartSink()}. Default
         * implementation is a no-op so legacy implementers (including test
         * scaffolds) need not opt in.
         */
        default void setCartSink(@Nullable CartSink sink) {
            // no-op; override on implementations that support STAGE mode.
        }
    }

    /**
     * Sink that the platform anvil opener invokes on STAGE-mode confirm to
     * route the typed value into the player's per-backend config staging
     * cart. Implemented by {@link MenuRedeemSubcommand} itself; exposed via
     * {@link #cartSink()} so the renderer-side wiring code can pass a
     * reference to the platform {@link AnvilInputOpener} at plugin enable.
     */
    @FunctionalInterface
    public interface CartSink {
        /**
         * Add (or replace) a staged {@code paramName=value} entry in
         * {@code viewer}'s cart for {@code fileName}. Replaces the cart
         * entirely when {@code fileName} differs from any existing cart on
         * record (cart is scoped to one file at a time, per the staging-cart
         * design). Safe to call from any thread.
         */
        void stage(UUID viewer, String fileName, String paramName, String value);
    }

    /**
     * SAM signature for {@link #configSubtreeBuilder} (PROPOSAL-config-view-
     * as-book.md v3.7). Implementers render one of the three curated
     * config-subtree pages on demand. Each method shall read live
     * {@link io.github.dailystruggle.rtp.common.configuration.ConfigParser}
     * state at call time so the post-write rebuild contract (v3.6.3) holds:
     * after a config write, re-invoking the matching method shall surface
     * the new value in the rebuilt {@link MenuModel}.
     *
     * <p>Implementations shall not perform permission checks; the dispatch
     * arms apply {@link #CONFIG_VIEW_PERMISSION} before invoking the builder.
     */
    public interface MenuConfigSubtreeBuilder {
        /** Build the config-file selector page for the root directory (v3.7 §3.1). */
        MenuModel buildSelector(UUID viewer);

        /**
         * Build the config-file selector page for the directory at
         * {@code subDir} (a forward-slashed relative path under the config
         * root; {@code ""} is the root). ADR-071 rule 7: a directory page
         * lists its child directories and its member files, so the same
         * builder walks every level. Defaults to the root
         * {@link #buildSelector(UUID)} so legacy/test scaffolds compile and
         * behave unchanged.
         */
        default MenuModel buildSelector(UUID viewer, String subDir) {
            return buildSelector(viewer);
        }

        /**
         * Build the per-file key list page for {@code fileName} (v3.7 §3.2).
         * Implementations shall return {@code null} when the file name is
         * unknown to the live {@code RTP.configs} registry; the dispatch arm
         * treats {@code null} as an S-004 reject path.
         */
        @Nullable MenuModel buildFile(UUID viewer, String fileName);

        /**
         * Cart-aware overload (item 6 of the staging-cart redesign). Delegates
         * to the 2-arg {@link #buildFile(UUID, String)} by default so legacy
         * impls (notably the test scaffolds) compile and behave unchanged.
         * Production impls override this overload to filter staged keys out
         * of the Changeable list and append Pending + Apply + Discard rows
         * driven by the {@code cartSnapshot} (insertion-ordered, may be
         * empty; never {@code null}).
         */
        default @Nullable MenuModel buildFile(UUID viewer,
                                              String fileName,
                                              java.util.LinkedHashMap<String, String> cartSnapshot) {
            return buildFile(viewer, fileName);
        }

        /**
         * Build the per-key value picker page for {@code (fileName, paramName)}
         * (v3.7 §3.3). Implementations shall return {@code null} when the
         * pair does not resolve to a known parameter; the dispatch arm treats
         * {@code null} as an S-004 reject path.
         */
        @Nullable MenuModel buildKey(UUID viewer, String fileName, String paramName);
    }

    /**
     * SAM for the two curated no-payload pages introduced by
     * PROPOSAL-admin-panel.md v2: the admin panel and the curated front page.
     * Implementers shall read live state at call time (matching the post-write
     * rebuild contract used elsewhere).
     *
     * <p>Implementations shall not perform the {@code rtp.menu.admin}
     * permission check; the {@code dispatchOpenAdminPanel} arm applies that
     * gate before invoking {@link #buildAdminPanel(UUID)}. The front-page
     * builder is unconditionally callable (no admin gate).
     */
    public interface MenuCuratedPageBuilder {
        /** Build the curated admin-panel page (PROPOSAL-admin-panel.md v2). */
        MenuModel buildAdminPanel(UUID viewer);

        /**
         * Build the curated front page. Mirrors the no-token / empty-path
         * branch of {@link MenuPageBuilder#build} but invocable directly from
         * an {@link MenuAction.OpenFrontPage} click (e.g. the admin panel's
         * back row).
         */
        MenuModel buildFrontPage(UUID viewer);

        /**
         * Build the curated Visualizations submenu reached from the admin
         * panel's Visualizations row (PR2b of the admin-Visualizations
         * feature; see {@link VisualizationsSubmenuBuilder}). Default
         * returns {@code null} so existing wire-ups that do not yet route
         * this action keep compiling; the dispatch arm treats {@code null}
         * as an S-004 reject path with {@code menuInvalid}.
         */
        default @Nullable MenuModel buildVisualizations(UUID viewer) {
            return null;
        }

        /**
         * Build the kind-scoped region picker reached from the top-level
         * Visualizations chart-kind picker (a row click on e.g. "Bad
         * Locations" lands here with {@code kind == REGION_BAD_LOCATIONS_SHAPE}).
         * Default returns {@code null} so existing wire-ups that do not yet
         * route this action keep compiling; the dispatch arm treats
         * {@code null} as an S-004 reject path with {@code menuInvalid}.
         */
        default @Nullable MenuModel buildVisualizationRegions(
                UUID viewer, ChartSpec.Kind kind) {
            return null;
        }
    }

    /**
     * SAM signature for {@link #configSearchBuilder}
     * (PROPOSAL-rtp-menu-config-search.md). Implementers build the
     * paginated config-search results page for {@code query} (page index is
     * 1-indexed, clamped by the implementation). Implementations shall:
     * <ul>
     *   <li>walk {@code RTP.configs} (including
     *       {@code MultiConfigParser.listParsers()/getParser()}) on each
     *       call so the post-write rebuild contract still holds;</li>
     *   <li>match case-insensitively against color-stripped key names and
     *       values via
     *       {@link io.github.dailystruggle.rtp.common.text.LegacyColorStrip},
     *       projecting hits back to raw offsets for highlight overlay;</li>
     *   <li>render rows clickable to
     *       {@link MenuAction.OpenConfigKey}.</li>
     * </ul>
     * Permission gating ({@link #CONFIG_VIEW_PERMISSION}) is performed by
     * the dispatch arm before the builder is invoked.
     */
    @FunctionalInterface
    public interface MenuConfigSearchBuilder {
        /**
         * @param viewer the clicking player's UUID.
         * @param query  the operator-typed search string (already trimmed by
         *               the dispatch arm; may still be short / empty, in
         *               which case the implementation may return an empty
         *               results page or {@code null} for an S-004 reject).
         * @param page   1-indexed page number; implementations clamp.
         * @return the results {@link MenuModel}, or {@code null} when the
         *         query / page does not resolve (S-004 reject path).
         */
        @Nullable MenuModel buildResults(UUID viewer, String query, int page);
    }

    /**
     * SAM signature for {@link #infoBookBuilder} (PROPOSAL-info-as-book.md
     * section 4.6). Implementers shall read live state at call time
     * (matching the post-write rebuild contract used elsewhere) and produce
     * a {@link MenuModel} whose pages mirror the chat output {@code /rtp info}
     * would have produced for the given {@code scope}.
     *
     * <p>Permission gating ({@code rtp.info}) is performed by the dispatch
     * arm before the builder is invoked.
     */
    @FunctionalInterface
    public interface MenuInfoBookBuilder {
        /**
         * @param viewer the clicking player's UUID.
         * @param scope  the {@code /rtp info} scope (global / world / region).
         * @return the rendered {@link MenuModel}; implementations should
         *         never return {@code null} (an empty book is preferred to a
         *         null), but the dispatch arm tolerates {@code null} as an
         *         S-004 reject path for defensive symmetry with
         *         {@link MenuConfigSearchBuilder}.
         */
        @Nullable MenuModel build(UUID viewer, MenuAction.InfoScopeToken scope);
    }

    /**
     * @param parent the {@code /rtp} root command (also used as the
     *               dispatch target for {@link MenuAction.RunRtpCommand}).
     */
    public MenuRedeemSubcommand(TreeCommand parent) {
        this(parent, uuid -> perm -> true, null, null, null);
    }

    /**
     * Test / wire-up constructor: callers (notably {@code rtp-plugin}) supply a
     * platform-aware {@link Predicate} factory so the dispatched {@link MenuAction.RunRtpCommand}
     * traverses the live command tree with the correct permission view.
     *
     * @param permissionProbeFactory maps the sender UUID to a permission probe.
     *                               Default behaviour ({@code _ -> _ -> true}) is
     *                               sound because every node in the {@code /rtp}
     *                               tree also gates itself at the adapter layer.
     */
    public MenuRedeemSubcommand(TreeCommand parent,
                                java.util.function.Function<UUID, Predicate<String>> permissionProbeFactory) {
        this(parent, permissionProbeFactory, null, null, null);
    }

    /**
     * Full-scope constructor (CHECKLIST item 4.2.a): adds optional open-page support.
     *
     * <p>When {@code renderer} and {@code pageBuilder} are both non-{@code null},
     * a token-less invocation ({@code /rtp menu} or {@code /rtp menu <subtree>})
     * reflects the matching {@link TreeCommand} node via {@code pageBuilder} and
     * passes the resulting {@link MenuModel} to {@code renderer.render(...)}.
     * When either is {@code null} (e.g. no renderer registered on this platform,
     * or the operator's {@code menu.renderer} list is exhausted), the no-token
     * path falls back to the existing {@code menuInvalid} reject + WARN log
     * (backward compatible with the Stage 3 wire-up).
     *
     * @param renderer    optional renderer used for open-page dispatch; pass
     *                    {@code null} to disable open-page support.
     * @param pageBuilder optional page builder; pass {@code null} together with
     *                    {@code renderer} to disable open-page support. The
     *                    closure must apply the caller's {@link Predicate} +
     *                    {@link MenuConsumerProfile} when reflecting the node.
     */
    public MenuRedeemSubcommand(TreeCommand parent,
                                java.util.function.Function<UUID, Predicate<String>> permissionProbeFactory,
                                @Nullable MenuRenderer renderer,
                                @Nullable MenuPageBuilder pageBuilder) {
        this(parent, permissionProbeFactory, renderer, pageBuilder, null);
    }

    /**
     * Stage A.2 constructor: extends the renderer + page-builder wire-up with
     * an optional {@link MenuParamPickerBuilder} for the parameter-value
     * picker sub-page. Pass {@code null} for {@code paramPickerBuilder} to
     * keep the pre-Stage-A.2 behaviour (inbound {@link MenuAction.OpenParamPicker}
     * tokens reject with {@code menuInvalid} + WARN).
     */
    public MenuRedeemSubcommand(TreeCommand parent,
                                java.util.function.Function<UUID, Predicate<String>> permissionProbeFactory,
                                @Nullable MenuRenderer renderer,
                                @Nullable MenuPageBuilder pageBuilder,
                                @Nullable MenuParamPickerBuilder paramPickerBuilder) {
        this(parent, permissionProbeFactory,
                renderer, pageBuilder, paramPickerBuilder, null);
    }

    /**
     * ADR-045 constructor: adds an optional {@link AnvilInputOpener} for the
     * "type a custom value..." picker row. Pass {@code null} to keep the
     * pre-ADR-045 behaviour (inbound {@link MenuAction.PromptAnvilInput}
     * tokens reject with {@code menuInvalid} + WARN).
     */
    public MenuRedeemSubcommand(TreeCommand parent,
                                java.util.function.Function<UUID, Predicate<String>> permissionProbeFactory,
                                @Nullable MenuRenderer renderer,
                                @Nullable MenuPageBuilder pageBuilder,
                                @Nullable MenuParamPickerBuilder paramPickerBuilder,
                                @Nullable AnvilInputOpener anvilInputOpener) {
        this(parent, permissionProbeFactory,
                renderer, pageBuilder, paramPickerBuilder, anvilInputOpener, null);
    }

    /**
     * PROPOSAL-config-view-as-book.md v3.7 constructor: adds an optional
     * {@link MenuConfigSubtreeBuilder} for the curated config-subtree pages.
     * Pass {@code null} to keep the pre-v3.7 behaviour (inbound
     * {@link MenuAction.OpenConfigSelector} / {@link MenuAction.OpenConfigFile} /
     * {@link MenuAction.OpenConfigKey} tokens reject with {@code menuInvalid} +
     * WARN).
     */
    public MenuRedeemSubcommand(TreeCommand parent,
                                java.util.function.Function<UUID, Predicate<String>> permissionProbeFactory,
                                @Nullable MenuRenderer renderer,
                                @Nullable MenuPageBuilder pageBuilder,
                                @Nullable MenuParamPickerBuilder paramPickerBuilder,
                                @Nullable AnvilInputOpener anvilInputOpener,
                                @Nullable MenuConfigSubtreeBuilder configSubtreeBuilder) {
        this(parent, permissionProbeFactory,
                renderer, pageBuilder, paramPickerBuilder,
                anvilInputOpener, configSubtreeBuilder, null);
    }

    /**
     * PROPOSAL-admin-panel.md v2 constructor: adds an optional
     * {@link MenuCuratedPageBuilder} for the curated admin-panel and front-page
     * pages. Pass {@code null} to keep the pre-v2 behaviour (inbound
     * {@link MenuAction.OpenAdminPanel} / {@link MenuAction.OpenFrontPage}
     * tokens reject with {@code menuInvalid} + WARN).
     */
    public MenuRedeemSubcommand(TreeCommand parent,
                                java.util.function.Function<UUID, Predicate<String>> permissionProbeFactory,
                                @Nullable MenuRenderer renderer,
                                @Nullable MenuPageBuilder pageBuilder,
                                @Nullable MenuParamPickerBuilder paramPickerBuilder,
                                @Nullable AnvilInputOpener anvilInputOpener,
                                @Nullable MenuConfigSubtreeBuilder configSubtreeBuilder,
                                @Nullable MenuCuratedPageBuilder curatedPageBuilder) {
        this(parent, permissionProbeFactory,
                renderer, pageBuilder, paramPickerBuilder,
                anvilInputOpener, configSubtreeBuilder, curatedPageBuilder,
                null);
    }

    /**
     * PROPOSAL-rtp-menu-config-search.md constructor: adds an optional
     * {@link MenuConfigSearchBuilder} for the config-search results page.
     * Pass {@code null} to keep the pre-search behaviour (inbound
     * {@link MenuAction.OpenConfigSearchResults} tokens reject with
     * {@code menuInvalid} + WARN; {@link MenuAction.OpenConfigSearchPrompt}
     * still works via the existing anvil-input opener wiring).
     */
    public MenuRedeemSubcommand(TreeCommand parent,
                                java.util.function.Function<UUID, Predicate<String>> permissionProbeFactory,
                                @Nullable MenuRenderer renderer,
                                @Nullable MenuPageBuilder pageBuilder,
                                @Nullable MenuParamPickerBuilder paramPickerBuilder,
                                @Nullable AnvilInputOpener anvilInputOpener,
                                @Nullable MenuConfigSubtreeBuilder configSubtreeBuilder,
                                @Nullable MenuCuratedPageBuilder curatedPageBuilder,
                                @Nullable MenuConfigSearchBuilder configSearchBuilder) {
        this(parent, permissionProbeFactory,
                renderer, pageBuilder, paramPickerBuilder,
                anvilInputOpener, configSubtreeBuilder, curatedPageBuilder,
                configSearchBuilder, null);
    }

    /**
     * PROPOSAL-info-as-book.md section 4.6 constructor: adds an optional
     * {@link MenuInfoBookBuilder} for the {@code /rtp info} book pages. Pass
     * {@code null} to keep the pre-info-book behaviour (inbound
     * {@link MenuAction.OpenInfo} tokens reject with {@code menuInvalid} +
     * WARN). The companion {@link MenuAction.SwitchInfoToText} variant
     * always works (it re-enters the {@code /rtp info} chat path).
     */
    public MenuRedeemSubcommand(TreeCommand parent,
                                java.util.function.Function<UUID, Predicate<String>> permissionProbeFactory,
                                @Nullable MenuRenderer renderer,
                                @Nullable MenuPageBuilder pageBuilder,
                                @Nullable MenuParamPickerBuilder paramPickerBuilder,
                                @Nullable AnvilInputOpener anvilInputOpener,
                                @Nullable MenuConfigSubtreeBuilder configSubtreeBuilder,
                                @Nullable MenuCuratedPageBuilder curatedPageBuilder,
                                @Nullable MenuConfigSearchBuilder configSearchBuilder,
                                @Nullable MenuInfoBookBuilder infoBookBuilder) {
        super(parent);
        this.rtpRoot = Objects.requireNonNull(parent, "parent");
        this.permissionProbeFactory =
                Objects.requireNonNull(permissionProbeFactory, "permissionProbeFactory");
        this.permissionGates = new MenuPermissionGates(this.permissionProbeFactory);
        // Either both renderer + builder are present (open-page enabled), or
        // both are null (open-page disabled, falls back to menuInvalid). A
        // half-configured state would be ambiguous, so we collapse it.
        if (renderer != null && pageBuilder != null) {
            this.renderer = renderer;
            this.pageBuilder = pageBuilder;
            // Param picker can only function when a renderer is also wired;
            // collapse the half-configured case to null for consistency.
            this.paramPickerBuilder = paramPickerBuilder;
            // Same for the anvil opener: only meaningful when a renderer is
            // wired (the action is renderer-emitted from a rendered picker).
            this.anvilInputOpener = anvilInputOpener;
            // Config-subtree builder: same renderer-coupling as the others.
            this.configSubtreeBuilder = configSubtreeBuilder;
            // Curated-page builder: same renderer-coupling as the others.
            this.curatedPageBuilder = curatedPageBuilder;
            // Config-search builder: same renderer-coupling as the others.
            this.configSearchBuilder = configSearchBuilder;
            // Info-book builder: same renderer-coupling as the others.
            this.infoBookBuilder = infoBookBuilder;
        } else {
            this.renderer = null;
            this.pageBuilder = null;
            this.paramPickerBuilder = null;
            this.anvilInputOpener = null;
            this.configSubtreeBuilder = null;
            this.curatedPageBuilder = null;
            this.configSearchBuilder = null;
            this.infoBookBuilder = null;
        }
        // ADR-050 Stage 3beta.D.2 (2026-05-24): the legacy `token=<v>`
        // parameter registration, the PARAM_TOKEN constant, the
        // MenuTokenRegistry ctor parameter, and the tokenRegistry field
        // are all gone. The renderer (Stage 2) emits concrete
        // `/rtp menu ...` commands; no caller sets `token=...` anymore.
        // Page parameter (CHECKLIST 5.3.b): `/rtp menu page:<n>` opens the
        // matching subtree at 1-indexed page n. No curated value list (the
        // valid range depends on the reflected model, not on the parameter
        // itself); the predicate accepts any positive integer string.
        getParameterLookup().put(PARAM_PAGE, new CommandParameter(PERMISSION,
                "menu page index (1-indexed)", (uuid, value) -> {
                    if (value == null) return false;
                    try {
                        return Integer.parseInt(value) >= 1;
                    } catch (NumberFormatException ignored) {
                        return false;
                    }
                }) {
            @Override
            public java.util.Set<String> values() {
                return java.util.Collections.emptySet();
            }
        });
        // "+ add new" anvil-confirm parameters. The anvil session submits
        // /rtp menu multiaddKind=<kind> multiadd=<typedName> as the player;
        // dispatch() routes the pair to dispatchMultiConfigMutate(ADD).
        // Predicate accepts any non-empty value (the parserKind unknown / the
        // name-collision path is re-checked server-side in the mutate arm).
        getParameterLookup().put(PARAM_MULTIADD, new CommandParameter(PERMISSION,
                "multiconfig add: typed entry name",
                (uuid, value) -> value != null && !value.isEmpty()) {
            @Override
            public java.util.Set<String> values() {
                return java.util.Collections.emptySet();
            }
        });
        getParameterLookup().put(PARAM_MULTIADD_KIND, new CommandParameter(PERMISSION,
                "multiconfig add: parser kind",
                (uuid, value) -> value != null && !value.isEmpty()) {
            @Override
            public java.util.Set<String> values() {
                return java.util.Collections.emptySet();
            }
        });
        // Mirror every TreeCommand sibling registered under rtpRoot as a
        // MenuMirrorSubcommand child of this `menu` subcommand, recursively
        // shadowing their parameters/children. This is what makes
        // `/rtp menu config config.yml ...` walk through the existing
        // commands-api parser into shadow nodes whose leaf onCommand renders
        // the menu at the corresponding /rtp subtree (with any name:value
        // segments staged in the assembled path) instead of running the real
        // subcommand.
        //
        // The walk is deferred via miscAsyncTasks with a delay greater than
        // ConfigCmd's own 5-tick deferred addCommands() pulse so the snapshot
        // captures ConfigCmd's per-file SubConfigCmd children
        // (`config.yml`, `regions.yml`, etc.) which are populated
        // asynchronously after RTPCmdBukkit construction. An eager mirror
        // walk in this constructor would catch ConfigCmd with zero children
        // and produce a dead `/rtp menu config ...` branch.
        // ADR-050 Stage 1a (Proposed 2026-05-24): register concrete-command
        // leaves that coexist with the token-redeem path. Tokens still work;
        // these leaves provide the self-documenting `/rtp menu open ...`,
        // `/rtp menu admin`, `/rtp menu front`, and `/rtp menu visualizations`
        // forms. Each routes into the matching `dispatch*` helper on this
        // subcommand; no logic is duplicated. See ADR-050.
        addSubCommand(new MenuConcreteCommandLeaves.OpenMenuConcreteCmd(this));
        addSubCommand(new MenuConcreteCommandLeaves.OpenAdminPanelConcreteCmd(this));
        addSubCommand(new MenuConcreteCommandLeaves.OpenFrontPageConcreteCmd(this));
        addSubCommand(new MenuConcreteCommandLeaves.OpenVisualizationsConcreteCmd(this));
        // ADR-050 Stage 1b (Proposed 2026-05-24): the remaining concrete leaves.
        // Names that would collide with /rtp root siblings mirrored by
        // seedMirrorTree (`config`, `info`) are deliberately NOT registered as
        // concrete children of `menu` here: the mirror walk already provides
        // `/rtp menu config file:<file> ...` and `/rtp menu info ...` via the
        // args-form parser, and registering a same-named concrete child would
        // pre-empt the mirror walk's `existing.containsKey` skip and break
        // the existing args-form behaviour. Stage 2 will switch the renderer
        // to emit the args-form mirror commands directly for those branches.
        addSubCommand(new MenuConcreteCommandLeavesB.PickerCmd(this));
        // Dedicated destination selectors. Each owns its display name and
        // row color supplier and renders through the curated
        // SelectionMenuBuilder (clean "pick a X" header, no command echo, no
        // "type a custom value" row), instead of the generic command-
        // navigation picker. The front page / admin panel rows run these as
        // /rtp menu world|region|biome|prefab.
        addSubCommand(new MenuConcreteCommandLeavesB.WorldCmd(this));
        addSubCommand(new MenuConcreteCommandLeavesB.RegionCmd(this));
        addSubCommand(new MenuConcreteCommandLeavesB.BiomeCmd(this));
        addSubCommand(new MenuConcreteCommandLeavesB.PrefabCmd(this));
        addSubCommand(new MenuConcreteCommandLeavesB.PageCmd(this));
        addSubCommand(new MenuConcreteCommandLeavesB.StageCmd(this));
        addSubCommand(new MenuConcreteCommandLeavesB.UnstageCmd(this));
        addSubCommand(new MenuConcreteCommandLeavesB.ApplyCmd(this));
        addSubCommand(new MenuConcreteCommandLeavesB.DiscardCmd(this));
        addSubCommand(new MenuConcreteCommandLeavesB.MultiCmd(this));
        // ADR-050 Stage 2 (2026-05-24): register `config`, `info`, and
        // `anvil` concrete leaves. They are registered BEFORE seedMirrorTree
        // runs (the seed walk is delayed via miscAsyncTasks with a 10-tick
        // delay), so the mirror walk's `existing.containsKey` check at
        // seedMirrorTree:962-964 will skip the colliding mirror names
        // (`config`, `info`) and the concrete leaves win. This is the
        // intentional cutover: Stage 2's renderer emits concrete commands
        // that target these leaves; the args-form mirror walk is no longer
        // needed for these branches.
        addSubCommand(new MenuConcreteCommandLeavesB.ConfigCmd(this));
        addSubCommand(new MenuConcreteCommandLeavesB.InfoCmd(this));
        addSubCommand(new MenuConcreteCommandLeavesB.AnvilCmd(this));
        // Register `/rtp visualization` as a sibling of `menu` under the
        // `/rtp` root so the visualizations selector is reachable directly
        // (not only via `/rtp menu visualizations`). seedMirrorTree below
        // will additionally surface it as `/rtp menu visualization` via the
        // mirror walk, which is harmless duplication during Stage 1a; the
        // canonical home is the root sibling per ADR-050. The temporary
        // location for this registration is here (inside MenuRedeemSubcommand's
        // constructor) rather than in RTPCmdImpl; Stage 1b will move it.
        rtpRoot.addSubCommand(new MenuConcreteCommandLeaves.VisualizationRootCmd(rtpRoot, this));

        RTP.getInstance().miscAsyncTasks.add(
                new io.github.dailystruggle.rtp.common.tasks.RTPRunnable(this::seedMirrorTree, 10));
    }

    /**
     * Snapshot the {@code /rtp} root's TreeCommand siblings (minus self) as
     * {@link MenuMirrorSubcommand} children of this subcommand. Idempotent:
     * a second call skips any sibling whose mirror is already registered.
     * Deferred (not run from the constructor) so subcommands that populate
     * their own children asynchronously (e.g. {@code ConfigCmd.addCommands})
     * are visible at snapshot time.
     */
    private void seedMirrorTree() {
        Map<String, CommandsAPICommand> siblings = rtpRoot.getCommandLookup();
        if (siblings == null || siblings.isEmpty()) return;
        String selfName = name();
        String selfKey = selfName == null ? null : selfName.toUpperCase(java.util.Locale.ROOT);
        Map<String, CommandsAPICommand> existing = getCommandLookup();
        for (Map.Entry<String, CommandsAPICommand> entry : siblings.entrySet()) {
            String key = entry.getKey();
            CommandsAPICommand sibling = entry.getValue();
            if (key == null || key.equals(selfKey)) continue;
            if (!(sibling instanceof TreeCommand tc)) continue;
            String mirrorName = tc.name();
            if (mirrorName == null || mirrorName.isEmpty()) continue;
            if (existing != null
                    && existing.containsKey(mirrorName.toUpperCase(java.util.Locale.ROOT))) {
                continue;
            }
            addSubCommand(new MenuMirrorSubcommand(this, this, tc, List.of(mirrorName)));
        }
    }

    @Override
    public String name() {
        return "menu";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public boolean onCommand(UUID senderId,
                             Map<String, List<String>> parameterValues,
                             @Nullable CommandsAPICommand nextCommand) {
        return dispatch(senderId, parameterValues, nextCommand, null);
    }

    @Override
    public boolean onCommand(UUID senderId,
                             Map<String, List<String>> parameterValues,
                             @Nullable CommandsAPICommand nextCommand,
                             Consumer<String> messageMethod) {
        return dispatch(senderId, parameterValues, nextCommand, messageMethod);
    }

    /**
     * ADR-050 (Proposed 2026-05-24) Stage 1c/γ2: token-redeem dispatch
     * deleted. {@code /rtp menu} now serves only two paths:
     * <ol>
     *   <li>the "+ add new" anvil-confirm shortcut
     *       ({@code /rtp menu multiaddKind=&lt;kind&gt; multiadd=&lt;name&gt;}),
     *       routed directly to {@link #dispatchMultiConfigMutate} with
     *       op = ADD;</li>
     *   <li>the no-arg open-page fallback ({@code /rtp menu} bare), which
     *       reflects the {@code /rtp} root via {@link #openPage} or returns
     *       {@code true} when the parser is about to descend into a
     *       {@link MenuMirrorSubcommand} child.</li>
     * </ol>
     *
     * <p>Every other menu navigation is now a concrete child command
     * (e.g. {@code /rtp menu admin}, {@code /rtp menu open path=...}) under
     * this subcommand; see {@code MenuConcreteCommandLeaves} and
     * {@code MenuConcreteCommandLeavesB}. The legacy
     * {@code MenuTokenRegistry} consume path and its 22-arm
     * {@code instanceof MenuAction} chain were removed because tokens
     * conflicted with the "admin-friendly, self-documenting commands"
     * goal of ADR-050; the {@code MenuTokenRegistry} field is retained
     * only to keep the ctor signature stable until Stage 3 deletes the
     * registry outright.
     */
    private boolean dispatch(UUID senderId,
                             Map<String, List<String>> parameterValues,
                             @Nullable CommandsAPICommand nextCommand,
                             @Nullable Consumer<String> messageMethod) {
        if (senderId == null) {
            reject(null, CommandMessages.menuUnknownPlayer,
                    "menu redeem rejected: no sender UUID", messageMethod);
            return false;
        }
        // "+ add new" anvil-confirm early branch: when the player submits
        // /rtp menu multiaddKind=<kind> multiadd=<typedName>, route directly
        // to dispatchMultiConfigMutate(ADD). The anvil opener writes this
        // form on confirm; it is not a token redeem.
        List<String> multiAddName = parameterValues != null
                ? parameterValues.get(PARAM_MULTIADD) : null;
        List<String> multiAddKind = parameterValues != null
                ? parameterValues.get(PARAM_MULTIADD_KIND) : null;
        if (multiAddName != null && !multiAddName.isEmpty()
                && multiAddKind != null && !multiAddKind.isEmpty()) {
            String typedName = multiAddName.get(0);
            String kind = multiAddKind.get(0);
            if (typedName != null && !typedName.isEmpty()
                    && kind != null && !kind.isEmpty()) {
                return dispatchMultiConfigMutate(senderId,
                        new MenuAction.MultiConfigMutate(kind, typedName,
                                MenuAction.MultiConfigMutate.Op.ADD),
                        messageMethod);
            }
        }
        // Bare `/rtp menu` (or `/rtp menu page=<n>`): open the root menu
        // page when a renderer + builder are wired. Token-bearing forms
        // (`/rtp menu token=<x>`) silently fall through to this branch
        // now that the consume path is gone; the token parameter is
        // ignored.
        if (renderer != null && pageBuilder != null) {
            // The commands-api walker invokes this node's onCommand as
            // "independent functionality" on its way down to a matched
            // child (TreeCommand.java:244-273). When the parser is about
            // to descend into ANY child command (a mirror child such as
            // `/rtp menu config regions default.yml size:500`, or a
            // concrete leaf such as `/rtp menu apply file=...`,
            // `/rtp menu stage ...`, `/rtp menu page n=2`, etc.), this
            // node must NOT open a page itself: the child is responsible
            // for its own rendering (or, like apply, for deliberately
            // leaving the book closed). Opening the root page here would
            // pop a stray, do-nothing book in front of the child's effect.
            // Returning `true` lets the walker continue to the child.
            if (nextCommand != null) {
                return true;
            }
            int pageIndex = extractPageIndex(parameterValues);
            return openPage(senderId, nextCommand, pageIndex, messageMethod);
        }
        reject(senderId, CommandMessages.menuInvalid,
                "menu redeem rejected: open-page disabled", messageMethod);
        return false;
    }

    /**
     * Open-page branch (no-token path). Reflects the root menu page; the
     * forward-descend / back navigation lives in {@link #dispatchOpen} which
     * walks an explicit path against the live tree.
     *
     * <p>{@code nextCommand} is preserved for backward compatibility with the
     * Stage-3 wire-up (a {@code /rtp menu <sub>} bare-arg form historically
     * routed the matching sub-{@code TreeCommand} here). Stage A.1 callers
     * normally hit this through the no-token bare {@code /rtp menu} form,
     * which lands on {@code rtpRoot}.
     *
     * <p>Failure modes:
     * <ul>
     *   <li>{@code pageBuilder} throws → log WARN + reject with
     *       {@code menuInvalid} (S-004).</li>
     *   <li>{@code renderer} throws (e.g. S-006 offline-player
     *       {@link IllegalStateException}) → log WARN + reject with
     *       {@code menuInvalid} (S-004).</li>
     * </ul>
     */
    // ADR-050 Stage 1b: package-private (used by `/rtp menu page n=<n>` leaf).
    boolean openPage(UUID senderId,
                             @Nullable CommandsAPICommand nextCommand,
                             int pageIndex,
                             @Nullable Consumer<String> messageMethod) {
        TreeCommand target = rtpRoot;
        java.util.List<String> assembledPath = java.util.Collections.emptyList();
        if (nextCommand instanceof TreeCommand tc) {
            target = tc;
            String n = tc.name();
            if (n != null && !n.isEmpty()) {
                assembledPath = java.util.List.of(n);
            }
        }
        return renderAt(senderId, target, assembledPath, pageIndex, messageMethod);
    }

    /**
     * Navigation branch (token-bearing {@link MenuAction.OpenMenu}). The
     * action's {@code path} is the args from the {@code /rtp} root down to
     * the target node; an empty path opens the root menu page. The path is
     * walked against {@link #rtpRoot} via {@link TreeCommand#getCommandLookup()}
     * — server-side resolution only, the commands-api parser is never
     * re-entered on this path, so navigation rows are independent of the
     * {@code menu} subcommand's parameter grammar (which only knows
     * {@code token}).
     *
     * <p>Unknown / unreachable segments collapse to {@code menuInvalid} +
     * WARN (S-004).
     */
    // ADR-050 (Proposed 2026-05-24): package-private so the new concrete
    // `/rtp menu open ...` leaf (an OpenMenuConcreteCmd nested below) can
    // route into the same helper the token-redeem path uses. Permission gate
    // and S-004 reject paths stay inside; no logic moves out.
    boolean dispatchOpen(UUID senderId,
                                 MenuAction.OpenMenu open,
                                 @Nullable Consumer<String> messageMethod) {
        if (renderer == null || pageBuilder == null) {
            // OpenMenu reached redeem but no renderer/builder is wired. Treat
            // as a protocol error (renderer minted it without us having one
            // to dispatch through) and refuse rather than silently no-op.
            RTP.log(Level.WARNING,
                    "menu open-action received with open-page disabled for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu open rejected: open-page disabled", messageMethod);
            return false;
        }
        String[] path = open.path();
        TreeCommand target = rtpRoot;
        for (String segment : path) {
            // Stage A.3: segments containing '=' are staged parameter
            // assignments (`name=value`) accumulated by the picker flow.
            // They do not advance the command-node walk — they ride along
            // in the assembled path and are surfaced by the Execute row.
            // ('=' separator matches commands-api parameter parsing.)
            if (segment != null && segment.indexOf('=') >= 0) {
                continue;
            }
            CommandsAPICommand next = target.getCommandLookup().get(segment.toUpperCase(java.util.Locale.ROOT));
            if (!(next instanceof TreeCommand)) {
                // SubConfigCmd children register under `<name>.YML`
                // (see dispatchPromptAnvilInput note); probe the suffixed
                // key when the bare key misses.
                next = target.getCommandLookup()
                        .get(segment.toUpperCase(java.util.Locale.ROOT) + ".YML");
            }
            if (!(next instanceof TreeCommand tc)) {
                RTP.log(Level.WARNING,
                        "menu open-action path segment '" + segment
                                + "' did not resolve to a TreeCommand under "
                                + target.name() + " for " + senderId);
                reject(senderId, CommandMessages.menuInvalid,
                        "menu open rejected: unknown path segment '" + segment + "'",
                        messageMethod);
                return false;
            }
            target = tc;
        }
        return renderAt(senderId, target, java.util.List.of(path), 0, messageMethod);
    }

    /**
     * Stage A.2 dispatch for {@link MenuAction.OpenParamPicker}. Walks
     * {@code parentPath} against the live {@link TreeCommand} graph (same
     * pattern as {@link #dispatchOpen}), verifies the parameter exists on
     * the resolved node, then invokes the configured
     * {@link MenuParamPickerBuilder} and hands the resulting {@link MenuModel}
     * to the renderer.
     *
     * <p>All failure paths log WARN and reject with {@code menuInvalid}
     * (S-004): missing builder, unknown path segment, unknown parameter,
     * builder exception, null model, or renderer exception.
     */
    // ADR-050 Stage 1b: package-private so MenuConcreteCommandLeavesB can call.
    boolean dispatchOpenParamPicker(UUID senderId,
                                            MenuAction.OpenParamPicker picker,
                                            @Nullable Consumer<String> messageMethod) {
        if (renderer == null || paramPickerBuilder == null) {
            RTP.log(Level.WARNING,
                    "menu param-picker received with picker-page disabled for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu param-picker rejected: picker-page disabled", messageMethod);
            return false;
        }
        String[] parentPath = picker.parentPath();
        TreeCommand target = rtpRoot;
        for (String segment : parentPath) {
            // Stage A.3: skip staged ame=value` parameter assignments —
            // they ride along in the assembled path without advancing the
            // command-node walk (see dispatchOpen).
            if (segment != null && segment.indexOf('=') >= 0) {
                continue;
            }
            CommandsAPICommand next = target.getCommandLookup()
                    .get(segment.toUpperCase(java.util.Locale.ROOT));
            if (!(next instanceof TreeCommand)) {
                // SubConfigCmd children register under `<name>.YML`
                // (see dispatchPromptAnvilInput note); probe the suffixed
                // key when the bare key misses.
                next = target.getCommandLookup()
                        .get(segment.toUpperCase(java.util.Locale.ROOT) + ".YML");
            }
            if (!(next instanceof TreeCommand tc)) {
                RTP.log(Level.WARNING,
                        "menu param-picker path segment '" + segment
                                + "' did not resolve to a TreeCommand under "
                                + target.name() + " for " + senderId);
                reject(senderId, CommandMessages.menuInvalid,
                        "menu param-picker rejected: unknown path segment '" + segment + "'",
                        messageMethod);
                return false;
            }
            target = tc;
        }
        // Verify the parameter exists (case-insensitive on the upper-cased
        // commands-api key, then on the raw name).
        String paramName = picker.paramName();
        Map<String, CommandParameter> paramLookup = target.getParameterLookup();
        boolean known = false;
        if (paramLookup != null) {
            if (paramLookup.containsKey(paramName)) known = true;
            else if (paramLookup.containsKey(paramName.toLowerCase(java.util.Locale.ROOT))) known = true;
            else if (paramLookup.containsKey(paramName.toUpperCase(java.util.Locale.ROOT))) known = true;
        }
        if (!known) {
            RTP.log(Level.WARNING,
                    "menu param-picker unknown parameter '" + paramName
                            + "' on " + target.name() + " for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu param-picker rejected: unknown parameter '" + paramName + "'",
                    messageMethod);
            return false;
        }
        MenuModel model;
        try {
            model = paramPickerBuilder.build(target, senderId,
                    java.util.List.of(parentPath), paramName);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu param-picker failed for " + senderId
                            + " node=" + target.name() + " param=" + paramName
                            + ": " + e.getMessage(), e);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu param-picker rejected: builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            reject(senderId, CommandMessages.menuInvalid,
                    "menu param-picker rejected: builder returned null model",
                    messageMethod);
            return false;
        }
        return MenuDrawer.draw(renderer, senderId, model, messageMethod,
                this::reject, "param-picker",
                "node=" + target.name() + " param=" + paramName);
    }

    /**
     * Dispatch for a dedicated destination/selection leaf (e.g.
     * {@code /rtp menu world|region|biome|prefab}). Walks {@code parentPath}
     * against the live {@link TreeCommand} graph (same pattern as
     * {@link #dispatchOpenParamPicker}), resolves the named parameter, takes
     * its {@code relevantValues(senderId)} as the entry set, and renders the
     * curated {@link SelectionMenuBuilder} page. The leaf supplies the
     * {@code displayName} (for the {@code "pick a <displayName>"} header) and
     * the {@code colorSupplier} that tints each row.
     *
     * <p>All failure paths log WARN and reject with {@code menuInvalid}
     * (S-004): missing renderer, unknown path segment, unknown parameter,
     * builder exception, null model, or renderer exception.
     *
     * @param senderId      viewing player
     * @param parentPath    path from {@code /rtp} to the node owning the param
     *                      (empty = root; staging appends onto this)
     * @param paramName     parameter to enumerate and stage
     * @param displayName   human label for the header
     * @param colorSupplier per-entry color prefix supplier (may be {@code null})
     * @param executeOnClick when {@code true} each entry runs the assembled
     *                      {@code /rtp} command on click (teleport); when
     *                      {@code false} it stages the choice for a later
     *                      Execute row (prefab apply/confirm flow)
     * @param messageMethod optional feedback sink
     */
    boolean dispatchSelectionMenu(UUID senderId,
                                  String[] parentPath,
                                  String paramName,
                                  String displayName,
                                  java.util.function.Function<String, String> colorSupplier,
                                  boolean executeOnClick,
                                  @Nullable Consumer<String> messageMethod) {
        return dispatchSelectionMenu(senderId, parentPath, paramName, displayName,
                colorSupplier, executeOnClick, null, messageMethod);
    }

    /**
     * Variant of {@link #dispatchSelectionMenu(UUID, String[], String, String,
     * java.util.function.Function, boolean, Consumer)} that lets the caller
     * override the Back-row destination. Used by the prefab picker, whose
     * value-staging {@code parentPath} ({@code admin prefab apply}) is not a
     * navigable page: its Back row must return to the admin panel.
     */
    boolean dispatchSelectionMenu(UUID senderId,
                                  String[] parentPath,
                                  String paramName,
                                  String displayName,
                                  java.util.function.Function<String, String> colorSupplier,
                                  boolean executeOnClick,
                                  @Nullable MenuAction backAction,
                                  @Nullable Consumer<String> messageMethod) {
        if (renderer == null) {
            RTP.log(Level.WARNING,
                    "menu selection received with menu disabled for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu selection rejected: menu disabled", messageMethod);
            return false;
        }
        TreeCommand target = rtpRoot;
        for (String segment : parentPath) {
            if (segment != null && segment.indexOf('=') >= 0) {
                continue;
            }
            CommandsAPICommand next = target.getCommandLookup()
                    .get(segment.toUpperCase(java.util.Locale.ROOT));
            if (!(next instanceof TreeCommand)) {
                next = target.getCommandLookup()
                        .get(segment.toUpperCase(java.util.Locale.ROOT) + ".YML");
            }
            if (!(next instanceof TreeCommand tc)) {
                RTP.log(Level.WARNING,
                        "menu selection path segment '" + segment
                                + "' did not resolve to a TreeCommand under "
                                + target.name() + " for " + senderId);
                reject(senderId, CommandMessages.menuInvalid,
                        "menu selection rejected: unknown path segment '" + segment + "'",
                        messageMethod);
                return false;
            }
            target = tc;
        }
        Map<String, CommandParameter> paramLookup = target.getParameterLookup();
        CommandParameter param = null;
        if (paramLookup != null) {
            param = paramLookup.get(paramName);
            if (param == null) param = paramLookup.get(paramName.toLowerCase(java.util.Locale.ROOT));
            if (param == null) param = paramLookup.get(paramName.toUpperCase(java.util.Locale.ROOT));
        }
        if (param == null) {
            RTP.log(Level.WARNING,
                    "menu selection unknown parameter '" + paramName
                            + "' on " + target.name() + " for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu selection rejected: unknown parameter '" + paramName + "'",
                    messageMethod);
            return false;
        }
        Set<String> entries;
        try {
            Set<String> r = param.relevantValues(senderId);
            entries = (r != null) ? r : param.values();
        } catch (RuntimeException e) {
            try {
                entries = param.values();
            } catch (RuntimeException ignored) {
                entries = java.util.Collections.emptySet();
            }
        }
        MenuModel model;
        try {
            model = new SelectionMenuBuilder().build(
                    java.util.List.of(parentPath), paramName, displayName, entries,
                    colorSupplier, executeOnClick, backAction);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu selection failed for " + senderId
                            + " node=" + target.name() + " param=" + paramName
                            + ": " + e.getMessage(), e);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu selection rejected: builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            reject(senderId, CommandMessages.menuInvalid,
                    "menu selection rejected: builder returned null model",
                    messageMethod);
            return false;
        }
        return MenuDrawer.draw(renderer, senderId, model, messageMethod,
                this::reject, "selection",
                "node=" + target.name() + " param=" + paramName);
    }

    /**
     * ADR-045 dispatch for {@link MenuAction.PromptAnvilInput}. Walks
     * {@code parentPath} against the live {@link TreeCommand} graph to
     * confirm the parameter exists (defensive — the renderer should never
     * mint this for an unknown parameter, but a stale token after a config
     * reload could land here), then hands off to the platform-side
     * {@link AnvilInputOpener}. The opener is responsible for opening the
     * anvil GUI on the player and, on confirm, submitting
     * {@code /rtp <parentPath...> <paramName>=<typed>} as the player.
     *
     * <p>Failure paths log WARN and reject with {@code menuInvalid} (S-004):
     * opener absent, unknown path segment, unknown parameter, or opener
     * threw / returned {@code false}.
     */
    // ADR-050 Stage 2 (2026-05-24): promoted from `private` to package-
    // private so the concrete `AnvilCmd` leaf in this package can invoke it.
    boolean dispatchPromptAnvilInput(UUID senderId,
                                             MenuAction.PromptAnvilInput prompt,
                                             @Nullable Consumer<String> messageMethod) {
        if (anvilInputOpener == null) {
            RTP.log(Level.WARNING,
                    "menu anvil-input received with anvil-input disabled for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu anvil-input rejected: anvil-input disabled", messageMethod);
            return false;
        }
        String[] parentPath = prompt.parentPath();
        TreeCommand target = rtpRoot;
        for (String segment : parentPath) {
            // Skip staged `name=value` segments (see dispatchOpen).
            if (segment != null && segment.indexOf('=') >= 0) {
                continue;
            }
            CommandsAPICommand next = target.getCommandLookup()
                    .get(segment.toUpperCase(java.util.Locale.ROOT));
            if (!(next instanceof TreeCommand)) {
                // Fallback for SubConfigCmd children: the per-file
                // SubConfigCmd registers itself under
                // `configParser.name.toLowerCase()` which carries the
                // `.yml` suffix (see SubConfigCmd ctor + ConfigParser.name),
                // so `commandLookup` exposes e.g. `"DEFAULT.YML"` -- not
                // `"DEFAULT"`. The menu's slash-aware short-circuit emits
                // path segments without the `.yml` suffix (e.g.
                // `["config","regions","default1234"]`), which would
                // otherwise fail the lookup and reject every per-region
                // anvil edit click. Probe the `.yml`-suffixed key when the
                // bare key misses; mirrors the same suffix-fallback in
                // dispatchOpenConfigKey (lines 1782-1791).
                String suffixed = segment.toUpperCase(java.util.Locale.ROOT) + ".YML";
                next = target.getCommandLookup().get(suffixed);
            }
            if (!(next instanceof TreeCommand tc)) {
                RTP.log(Level.WARNING,
                        "menu anvil-input path segment '" + segment
                                + "' did not resolve to a TreeCommand under "
                                + target.name() + " for " + senderId);
                reject(senderId, CommandMessages.menuInvalid,
                        "menu anvil-input rejected: unknown path segment '" + segment + "'",
                        messageMethod);
                return false;
            }
            target = tc;
        }
        String paramName = prompt.paramName();
        // No parameter-existence gate here: commands-api owns parameter
        // validation (it discards unknown name=value tokens during parse),
        // and the curated config registrations expose keys through paths
        // that getParameterLookup() does not surface uniformly across
        // ConfigCmd / SubConfigCmd / MultiConfigParser nodes. Defending here
        // with containsKey() rejects legitimate clicks (the regression that
        // produced "menu anvil-input unknown parameter 'canceldistance=5'"
        // when the mirror leaf still re-encoded params into the path). Let
        // the anvil confirm path submit the resulting /rtp ... key=value
        // command verbatim; an unknown key will be discarded downstream by
        // commands-api with its own messaging.
        boolean opened;
        try {
            // Mode-aware overload: STAGE-mode prompts (config staging-cart)
            // must reach the opener's STAGE branch so the typed value lands
            // in the per-player cart and the curated /rtp config <file> page
            // reopens with the new entry. Calling the legacy 4-arg form here
            // silently demoted every STAGE prompt to RUN, which executed the
            // /rtp config write immediately and closed the menu - the exact
            // regression this fix addresses ("failed last time to fully
            // apply this change").
            opened = anvilInputOpener.open(senderId,
                    java.util.List.of(parentPath), paramName, prompt.prefill(),
                    prompt.mode(), cartSink());
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu anvil-input opener failed for " + senderId
                            + " node=" + target.name() + " param=" + paramName
                            + ": " + e.getMessage(), e);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu anvil-input rejected: opener failure", messageMethod);
            return false;
        }
        if (!opened) {
            RTP.log(Level.WARNING,
                    "menu anvil-input opener refused for " + senderId
                            + " node=" + target.name() + " param=" + paramName);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu anvil-input rejected: opener refused", messageMethod);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------------
    // PROPOSAL-config-view-as-book.md v3.7 — three curated config-subtree
    // dispatch arms. Shape mirrors dispatchOpenParamPicker: gate, builder,
    // render, with S-004 reject on every failure path.
    // ------------------------------------------------------------------------

    /**
     * Returns {@code true} when {@code senderId} carries
     * {@link #CONFIG_VIEW_PERMISSION}. The permission probe is provided by
     * {@link #permissionProbeFactory}; a null probe is treated as deny-by-
     * default for the curated config surface (v3.7.1).
     */
    // ADR-050 split: delegates to {@link #permissionGates}. Kept as a method
    // so the existing 12 call sites in this file (and the leaf classes in
    // this package via inherited access) need no churn.
    private boolean hasConfigViewPermission(UUID senderId) {
        return permissionGates.hasConfigView(senderId);
    }

    /**
     * Dispatch for {@link MenuAction.OpenConfigSelector} (v3.7 §3.1). Gates
     * on {@link #CONFIG_VIEW_PERMISSION}, then routes through
     * {@link #configSubtreeBuilder}'s {@code buildSelector}. All failure paths
     * log WARN and reject with {@code menuInvalid} (S-004).
     */
    // ADR-050 Stage 1b: package-private.
    boolean dispatchOpenConfigSelector(UUID senderId,
                                               @Nullable Consumer<String> messageMethod) {
        return dispatchOpenConfigSelector(senderId,
                new MenuAction.OpenConfigSelector(""), messageMethod);
    }

    /**
     * Directory-aware dispatch for {@link MenuAction.OpenConfigSelector}
     * (ADR-071 rule 7). Routes through {@link #configSubtreeBuilder}'s
     * {@code buildSelector(viewer, subDir)} so the same recursive walk renders
     * the root and every nested config directory.
     */
    boolean dispatchOpenConfigSelector(UUID senderId,
                                               MenuAction.OpenConfigSelector open,
                                               @Nullable Consumer<String> messageMethod) {
        String subDir = (open == null) ? "" : open.subDir();
        // Staging-cart contract: returning to the file-selector page silently
        // drops any pending edits the player had staged for a previously-open
        // file. The cart is single-file-scoped; navigating up to the selector
        // is the canonical "I'm done with this file" gesture, mirroring the
        // user-confirmed scope cut on the staging-cart proposal (see
        // docs/dev/scratch/CHECKLIST-config-staging-cart.md). Cleared
        // unconditionally, even on the permission-denied path below, since a
        // permission revocation should not leak a stale cart either.
        clearCart(senderId);
        if (renderer == null || configSubtreeBuilder == null) {
            RTP.log(Level.WARNING,
                    "menu config-selector received with config-subtree disabled for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-selector rejected: config-subtree disabled", messageMethod);
            return false;
        }
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu config-selector denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-selector rejected: permission denied", messageMethod);
            return false;
        }
        MenuModel model;
        try {
            model = configSubtreeBuilder.buildSelector(senderId, subDir);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu config-selector builder failed for " + senderId
                            + " dir='" + subDir + "': " + e.getMessage(), e);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-selector rejected: builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-selector rejected: builder returned null model",
                    messageMethod);
            return false;
        }
        return MenuDrawer.draw(renderer, senderId, model, messageMethod,
                this::reject, "config-selector");
    }

    /**
     * Dispatch for {@link MenuAction.OpenConfigFile} (v3.7 §3.2). Gates on
     * {@link #CONFIG_VIEW_PERMISSION}, then routes through
     * {@link #configSubtreeBuilder}'s {@code buildFile}. A {@code null}
     * return from the builder (unknown file name) is an S-004 reject path.
     */
    // ADR-050 Stage 1b: package-private.
    boolean dispatchOpenConfigFile(UUID senderId,
                                           MenuAction.OpenConfigFile open,
                                           @Nullable Consumer<String> messageMethod) {
        if (renderer == null || configSubtreeBuilder == null) {
            RTP.log(Level.WARNING,
                    "menu config-file received with config-subtree disabled for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-file rejected: config-subtree disabled", messageMethod);
            return false;
        }
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu config-file denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-file rejected: permission denied", messageMethod);
            return false;
        }
        String fileName = open.fileName();
        // Slash-aware routing (ADR-050): a "<kind>/<entryName>" synthetic name
        // addresses a multiconfig entry page, not a flat config file. The
        // flat-config buildFile below cannot resolve a slash-bearing name and
        // would reject it as "unknown file", which breaks the Back row of the
        // finite-value picker opened for a multiconfig entry key (e.g. the
        // shape/vert type picker on regions/<entry>). Route those to the
        // multiconfig entry re-open so Back returns to the correct page.
        if (fileName != null) {
            int fileSlash = fileName.indexOf('/');
            if (fileSlash > 0 && fileSlash < fileName.length() - 1) {
                return reopenAfterCartOp(senderId, fileName, messageMethod);
            }
        }
        MenuModel model;
        try {
            // Item 6 of the staging-cart redesign: pass the viewer's current
            // cart snapshot (file-scoped; empty when no cart or scoped to a
            // different file) so the curated page can filter staged keys out
            // of the Changeable list and append Pending + Apply + Discard
            // rows. Snapshot is read live on every render so post-stage and
            // post-unstage re-renders surface the new cart state.
            LinkedHashMap<String, String> cartSnap = snapshotCart(senderId, fileName);
            model = configSubtreeBuilder.buildFile(senderId, fileName, cartSnap);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu config-file builder failed for " + senderId
                            + " file=" + fileName + ": " + e.getMessage(), e);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-file rejected: builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            RTP.log(Level.WARNING,
                    "menu config-file unknown file '" + fileName
                            + "' for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-file rejected: unknown file '" + fileName + "'",
                    messageMethod);
            return false;
        }
        return MenuDrawer.draw(renderer, senderId, model, messageMethod,
                this::reject, "config-file", "file=" + fileName);
    }

    /**
     * Dispatch for {@link MenuAction.OpenConfigKey} (v3.7 §3.3, updated
     * 2026-05-21). Gates on {@link #CONFIG_VIEW_PERMISSION}, resolves the
     * canonical {@code <file>.yml} segment under the live {@code config}
     * subcommand, then short-circuits directly to
     * {@link #dispatchPromptAnvilInput} in {@link MenuAction.Mode#STAGE}
     * mode. The previous behavior rendered an intermediate per-key value
     * picker page (suggested values + "type a custom value" anvil row),
     * which forced the operator through a redundant click before the
     * anvil opened. Per the user request 2026-05-21 ("immediately jump
     * from clicking a value to update to anvil menu and remove the
     * intermediate step and return to the config menu with the updated
     * value set at the top"), clicking a key row now opens the anvil
     * immediately; the STAGE-mode anvil confirm reopens
     * {@code /rtp config <file>} with the new entry surfaced in the
     * Pending list (see {@link CommandTreeMenuBuilder#buildConfigFile}).
     *
     * <p>Path resolution mirrors the production
     * {@code MenuConfigSubtreeBuilder.buildKey} walk: probe
     * {@code config.<file>.yml} first (the canonical registration form),
     * fall back to {@code config.<file>} for legacy registrations that
     * omit the suffix. Failure to resolve either form is an S-004 reject
     * with {@code menuInvalid}.
     */
    /**
     * Best-effort lookup of the current configured value for {@code paramName}
     * under the config file named {@code bareFileName} (no {@code .yml}
     * suffix), formatted as a plain string for use as the prefill of a
     * STAGE-mode anvil prompt. Walks {@link RTP#configs}' registered
     * {@link io.github.dailystruggle.rtp.common.configuration.ConfigParser}s
     * by their {@code name} (case-insensitive, suffix-tolerant) and returns
     * the matching enum entry's value via {@code String.valueOf}. Returns
     * {@code ""} when no parser, no enum constant, or no stored value
     * matches; this preserves the previous empty-prefill behaviour as a
     * safe fallback rather than corrupting the anvil with garbage.
     */
    private static String resolveCurrentConfigValueAsString(
            String bareFileName, String paramName) {
        if (paramName == null || paramName.isEmpty()) return "";
        if (bareFileName == null || bareFileName.isEmpty()) return "";
        if (RTP.configs == null) return "";
        String target = bareFileName.toLowerCase(java.util.Locale.ROOT);
        if (target.endsWith(".yml")) target = target.substring(0, target.length() - 4);
        io.github.dailystruggle.rtp.common.factory.FactoryValue<?> match = null;
        try {
            for (io.github.dailystruggle.rtp.common.configuration.ConfigParser<?> p
                    : RTP.configs.configParserMap.values()) {
                if (p == null || p.name == null) continue;
                String pn = p.name.toLowerCase(java.util.Locale.ROOT);
                if (pn.endsWith(".yml")) pn = pn.substring(0, pn.length() - 4);
                if (pn.equals(target)) {
                    match = p;
                    break;
                }
            }
        } catch (RuntimeException ignored) {
            return "";
        }
        if (match == null) return "";
        java.util.EnumMap<?, Object> data;
        try {
            data = match.getData();
        } catch (RuntimeException ignored) {
            return "";
        }
        return resolveDottedValueString(data, paramName);
    }

    /**
     * Resolve a (possibly dotted) {@code paramName} against an enum-keyed
     * config data map, formatted as a plain string. A bare name matches an
     * enum constant directly; a dotted name (e.g. {@code shape.radius})
     * resolves the leading segment to its enum constant and then descends
     * into the stored {@link io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection}
     * or {@link java.util.Map} value via the remaining dotted path. Returns
     * {@code ""} when nothing matches.
     */
    private static String resolveDottedValueString(
            java.util.EnumMap<?, Object> data, String paramName) {
        if (data == null || data.isEmpty()) return "";
        if (paramName == null || paramName.isEmpty()) return "";
        int dot = paramName.indexOf('.');
        String head = dot < 0 ? paramName : paramName.substring(0, dot);
        String rest = dot < 0 ? null : paramName.substring(dot + 1);
        for (java.util.Map.Entry<?, Object> e : data.entrySet()) {
            Enum<?> k = (Enum<?>) e.getKey();
            if (k == null) continue;
            if (k.name().equalsIgnoreCase(head)) {
                Object v = e.getValue();
                if (rest == null) {
                    return v == null ? "" : String.valueOf(v);
                }
                // ADR-073 inheritance: a block value may be an @<file> reference
                // token (e.g. region/world files store `shape: "@config"`) rather
                // than a nested section. Descending into the raw token yields
                // nothing, which surfaced as a spurious "(unset)" current value in
                // the finite-value picker. Resolve the reference to its owning
                // block first so the stored sub-key (e.g. shape.name) is found.
                if (io.github.dailystruggle.rtp.common.configuration
                        .ConfigDefaultResolver.isReference(v)) {
                    v = io.github.dailystruggle.rtp.common.configuration
                            .ConfigDefaultResolver.resolve(v, head, v);
                }
                Object nested = descendDotted(v, rest);
                return nested == null ? "" : String.valueOf(nested);
            }
        }
        return "";
    }

    /**
     * Descend a dotted sub-path into a section/map value. Supports
     * {@link io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection}
     * (which accepts dotted keys natively) and nested {@link java.util.Map}s.
     * Returns {@code null} when the path cannot be resolved.
     */
    private static Object descendDotted(Object value, String dottedPath) {
        if (value == null || dottedPath == null || dottedPath.isEmpty()) return null;
        // A materialized shape/vert block is stored in a region/world config
        // parser not as a nested section but as a FactoryValue (e.g. a Shape
        // object) whose sub-keys live in its own enum-keyed getData() map.
        // Descending into it as if it were a section returned nothing, which
        // surfaced as a spurious "(unset)" current value in the finite-value
        // picker even after the operator picked a type. Descend via the
        // enum-keyed data map instead.
        if (value instanceof io.github.dailystruggle.rtp.common.factory.FactoryValue<?> fv) {
            int dot = dottedPath.indexOf('.');
            String head = dot < 0 ? dottedPath : dottedPath.substring(0, dot);
            String rest = dot < 0 ? null : dottedPath.substring(dot + 1);
            for (java.util.Map.Entry<? extends Enum<?>, Object> e : fv.getData().entrySet()) {
                Enum<?> k = e.getKey();
                if (k != null && k.name().equalsIgnoreCase(head)) {
                    Object child = e.getValue();
                    return rest == null ? child : descendDotted(child, rest);
                }
            }
            return null;
        }
        if (value instanceof io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection section) {
            try {
                return section.get(dottedPath);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        if (value instanceof java.util.Map<?, ?> map) {
            int dot = dottedPath.indexOf('.');
            String head = dot < 0 ? dottedPath : dottedPath.substring(0, dot);
            String rest = dot < 0 ? null : dottedPath.substring(dot + 1);
            Object child = null;
            for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() != null && e.getKey().toString().equalsIgnoreCase(head)) {
                    child = e.getValue();
                    break;
                }
            }
            if (rest == null) return child;
            return descendDotted(child, rest);
        }
        return null;
    }

    /**
     * Multiconfig-aware variant of
     * {@link #resolveCurrentConfigValueAsString} for entry parsers
     * reached via {@code <kind>/<entryName>}-shaped synthetic fileNames.
     * Resolves through {@link #resolveMultiConfigParser} and the entry's
     * {@link io.github.dailystruggle.rtp.common.configuration.MultiConfigParser#getParser(String)}.
     */
    private static String resolveCurrentMultiConfigValueAsString(
            String kind, String entryName, String paramName) {
        if (paramName == null || paramName.isEmpty()) return "";
        if (kind == null || kind.isEmpty()) return "";
        if (entryName == null || entryName.isEmpty()) return "";
        io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<?> mcp =
                resolveMultiConfigParser(kind);
        if (mcp == null) return "";
        io.github.dailystruggle.rtp.common.configuration.ConfigParser<?> entry;
        try {
            entry = mcp.getParser(entryName);
        } catch (RuntimeException ignored) {
            return "";
        }
        if (entry == null) return "";
        java.util.EnumMap<?, Object> data;
        try {
            data = entry.getData();
        } catch (RuntimeException ignored) {
            return "";
        }
        return resolveDottedValueString(data, paramName);
    }

    // ADR-050 Stage 1b: package-private.
    boolean dispatchOpenConfigKey(UUID senderId,
                                          MenuAction.OpenConfigKey open,
                                          @Nullable Consumer<String> messageMethod) {
        if (anvilInputOpener == null) {
            RTP.log(Level.WARNING,
                    "menu config-key received with anvil-input disabled for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-key rejected: anvil-input disabled", messageMethod);
            return false;
        }
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu config-key denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-key rejected: permission denied", messageMethod);
            return false;
        }
        String fileName = open.fileName();
        String paramName = open.paramName();

        // Slash-aware short-circuit (2026-05-23): multiconfig entry pages
        // address themselves as "<parserKind>/<entryName>" (the '/' is
        // illegal in real config filenames so there is no ambiguity).
        // The anvil's parentPath becomes ["config", parserKind, entryName]
        // which matches the existing /rtp config <kind> <entryName> CLI
        // leaf -- no TreeCommand walk validation needed here because the
        // dispatchPromptAnvilInput walker re-resolves the path itself.
        int slash = fileName.indexOf('/');
        if (slash > 0 && slash < fileName.length() - 1) {
            String kind = fileName.substring(0, slash);
            String entryName = fileName.substring(slash + 1);
            String prefill = resolveCurrentMultiConfigValueAsString(
                    kind, entryName, paramName);
            // Finite-domain short-circuit (ADR-064 amendment): when the key's
            // in-memory YAML block comment declares an @options list or an
            // @source registry (e.g. shape/vert), render a picker instead of
            // the free-text anvil so arbitrary input is structurally
            // impossible. Falls through to the anvil when no finite domain is
            // declared or the renderer is unavailable.
            java.util.List<String> slashOptions = resolveFiniteOptions(fileName, paramName);
            if (!slashOptions.isEmpty() && renderer != null) {
                MenuModel slashPicker = new CommandTreeMenuBuilder()
                        .buildOptionsPicker(senderId, fileName, paramName, prefill, slashOptions);
                return MenuDrawer.draw(renderer, senderId, slashPicker, messageMethod,
                        this::reject, "config-options",
                        "file=" + fileName + " param=" + paramName);
            }
            MenuAction.PromptAnvilInput slashPrompt = new MenuAction.PromptAnvilInput(
                    new String[]{"config", kind, entryName},
                    paramName,
                    prefill,
                    MenuAction.Mode.STAGE);
            return dispatchPromptAnvilInput(senderId, slashPrompt, messageMethod);
        }

        // Resolve the live `config` subtree and the per-file SubConfigCmd.
        // Both forms (`<file>.yml` and bare `<file>`) are accepted so the
        // dispatcher does not reject on baseline registrations that omit
        // the `.yml` suffix - mirrors the production buildKey path walk.
        CommandsAPICommand configCmd = rtpRoot.getCommandLookup()
                .get("CONFIG");
        if (!(configCmd instanceof TreeCommand configTree)) {
            RTP.log(Level.WARNING,
                    "menu config-key cannot resolve 'config' subcommand for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-key rejected: config subcommand unavailable",
                    messageMethod);
            return false;
        }
        String bare = fileName;
        if (bare.toLowerCase(java.util.Locale.ROOT).endsWith(".yml")) {
            bare = bare.substring(0, bare.length() - 4);
        }
        String subSegment = bare + ".yml";
        CommandsAPICommand subCmd = configTree.getCommandLookup()
                .get(subSegment.toUpperCase(java.util.Locale.ROOT));
        if (subCmd == null) {
            // Fallback to bare-name registration.
            subCmd = configTree.getCommandLookup()
                    .get(bare.toUpperCase(java.util.Locale.ROOT));
            if (subCmd != null) {
                subSegment = bare;
            }
        }
        if (!(subCmd instanceof TreeCommand)) {
            RTP.log(Level.WARNING,
                    "menu config-key unknown file=" + fileName
                            + " for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-key rejected: unknown file '" + fileName + "'",
                    messageMethod);
            return false;
        }

        // STAGE-mode anvil prompt: anvil-confirm routes through the cart
        // sink (see AnvilInputSession on Paper/Folia) which stages the
        // typed value into the per-player cart and reopens
        // /rtp menu config <file>. Prefill is the current configured
        // value (best-effort string form) so the operator sees what they
        // are replacing rather than the previous empty-space placeholder;
        // anything unresolvable falls back to empty string.
        String prefill = resolveCurrentConfigValueAsString(bare, paramName);
        // Finite-domain short-circuit (ADR-064 amendment): see the slash-path
        // note above. Render a finite-value picker when the key declares an
        // @options list or @source registry; otherwise open the anvil.
        java.util.List<String> options = resolveFiniteOptions(bare, paramName);
        if (!options.isEmpty() && renderer != null) {
            MenuModel picker = new CommandTreeMenuBuilder()
                    .buildOptionsPicker(senderId, bare, paramName, prefill, options);
            return MenuDrawer.draw(renderer, senderId, picker, messageMethod,
                    this::reject, "config-options",
                    "file=" + bare + " param=" + paramName);
        }
        MenuAction.PromptAnvilInput prompt = new MenuAction.PromptAnvilInput(
                new String[]{"config", subSegment},
                paramName,
                prefill,
                MenuAction.Mode.STAGE);
        return dispatchPromptAnvilInput(senderId, prompt, messageMethod);
    }

    /**
     * Resolve the finite value domain declared for {@code paramName} in the
     * config file {@code fileName} (bare or {@code kind/entryName} multiconfig
     * form), reading the key's in-memory YAML block comment
     * ({@link io.github.dailystruggle.rtp.common.configuration.ConfigDirectives})
     * with no file I/O. Returns the {@code @options} literal list when present,
     * or the resolved registry values for a recognized {@code @source}:
     * {@code shape} / {@code vert} (static factories, {@link RTP#factoryMap}),
     * {@code world} (live server worlds, {@link RTP#serverAccessor}) or
     * {@code region} (loaded regions, {@link RTP#selectionAPI}). The world and
     * region registries are server-derived values that change at runtime but
     * are not authored by this plugin, so they are enumerated live on each
     * menu open rather than baked into an {@code @options} list. Returns an
     * empty list when no finite domain is declared, the comment is
     * unavailable, or resolution fails - the caller then falls back to the
     * free-text anvil prompt.
     */
    private static java.util.List<String> resolveFiniteOptions(String fileName, String paramName) {
        if (paramName == null || paramName.isEmpty()) return java.util.List.of();
        io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection yamlRoot =
                resolveYamlRootForFile(fileName);
        String comment = null;
        if (yamlRoot != null) {
            try {
                comment = yamlRoot.getComment(paramName);
            } catch (RuntimeException ignored) {
                comment = null;
            }
        }
        io.github.dailystruggle.rtp.common.configuration.ConfigDirectives directives =
                io.github.dailystruggle.rtp.common.configuration.ConfigDirectives.parse(comment);
        // ADR-073 inheritance fallback: a dotted key like "shape.name" carries
        // no local comment when its parent block ships an @<file> inheritance
        // token (e.g. region/world files store `shape: "@config"`). The menu
        // resolves that token for display; do the same for directive lookup so
        // the finite-value picker still fires. Resolve the parent's reference
        // to the owning default file and read the sub-key's comment there.
        if (directives.options().isEmpty()
                && (directives.source() == null || directives.source().isEmpty())) {
            io.github.dailystruggle.rtp.common.configuration.ConfigDirectives inherited =
                    resolveInheritedDirectives(fileName, paramName);
            if (!inherited.options().isEmpty()
                    || (inherited.source() != null && !inherited.source().isEmpty())) {
                directives = inherited;
            }
        }
        if (!directives.options().isEmpty()) {
            return directives.options();
        }
        String source = directives.source();
        if (source == null || source.isEmpty()) return java.util.List.of();
        java.util.Collection<String> keys = null;
        try {
            String s = source.toLowerCase(java.util.Locale.ROOT);
            switch (s) {
                case "shape" ->
                        keys = stripYmlSuffix(
                                RTP.factoryMap.get(RTP.factoryNames.shape).map.keySet());
                case "vert" ->
                        keys = stripYmlSuffix(
                                RTP.factoryMap.get(RTP.factoryNames.vert).map.keySet());
                case "world" -> keys = RTP.serverAccessor.getRTPWorlds().stream()
                        .map(io.github.dailystruggle.rtp.api.world.RTPWorld::name)
                        .collect(java.util.stream.Collectors.toList());
                case "region" -> keys = RTP.selectionAPI.regionNames();
                default -> { /* unknown source — no finite domain */ }
            }
        } catch (RuntimeException ignored) {
            return java.util.List.of();
        }
        if (keys == null || keys.isEmpty()) return java.util.List.of();
        java.util.List<String> out = new java.util.ArrayList<>(keys);
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * Strip the internal {@code .yml}/{@code .YML} suffix that the
     * {@link io.github.dailystruggle.rtp.common.factory.Factory} keyset carries
     * (factory keys are stored upper-cased and {@code .YML}-suffixed, e.g.
     * {@code SQUARE.YML}). Shape/vert type names are discriminators, not files,
     * so the finite-value picker must present the bare type name
     * ({@code SQUARE}) rather than a spurious {@code SQUARE.yml}. Empty results
     * are skipped; casing is otherwise preserved.
     */
    private static java.util.List<String> stripYmlSuffix(
            java.util.Collection<String> names) {
        java.util.List<String> out = new java.util.ArrayList<>(names.size());
        for (String name : names) {
            if (name == null) continue;
            String stripped = name;
            if (stripped.length() >= 4
                    && stripped.regionMatches(true, stripped.length() - 4, ".yml", 0, 4)) {
                stripped = stripped.substring(0, stripped.length() - 4);
            }
            if (!stripped.isEmpty()) out.add(stripped);
        }
        return out;
    }

    /**
     * Resolve directives for a {@code paramName} whose owning block comment does
     * not live on the addressed file itself. This covers two related cases that
     * both arise for runtime-created / inherited multiconfig entries (e.g.
     * {@code regions/asdf}):
     *
     * <ul>
     *   <li><b>Materialized entries.</b> A multiconfig entry's schema is defined
     *       by the kind's {@code default} template. A runtime-created entry
     *       often carries no block comments of its own, so the same key's
     *       comment is read from {@code <kind>/default} (candidate A). This
     *       recovers the {@code @source: world}/{@code @source: region}
     *       discriminators on top-level keys.</li>
     *   <li><b>ADR-073 inheritance.</b> A block inherited via an {@code @<file>}
     *       reference token (e.g. a region file that ships {@code shape: "@config"})
     *       carries no sub-key comment in either the entry or the template, so
     *       the reference is followed to the owning file (candidate B), and -
     *       whether or not the block is a live reference token or has been
     *       materialized - the region/world schemas mirror
     *       {@code config.yml#defaults.<paramName>}, which is consulted as a
     *       final fallback (candidate C). This recovers the
     *       {@code @source: shape}/{@code @source: vert} discriminators on
     *       {@code shape.name} / {@code vert.name}.</li>
     * </ul>
     *
     * <p>Returns an empty
     * {@link io.github.dailystruggle.rtp.common.configuration.ConfigDirectives}
     * when no candidate declares a finite domain. No file I/O - all comments are
     * served from the in-memory cached YAML roots.
     */
    private static io.github.dailystruggle.rtp.common.configuration.ConfigDirectives
            resolveInheritedDirectives(String fileName, String paramName) {
        io.github.dailystruggle.rtp.common.configuration.ConfigDirectives empty =
                io.github.dailystruggle.rtp.common.configuration.ConfigDirectives.parse(null);
        if (paramName == null || paramName.isEmpty()) return empty;

        int slash = fileName == null ? -1 : fileName.indexOf('/');
        boolean multi = slash > 0 && slash < fileName.length() - 1;

        // Candidate A: multiconfig entry schemas are defined by the kind's
        // "default" template; a runtime-created entry often carries no block
        // comments of its own, so read the same key's comment from
        // "<kind>/default".
        if (multi) {
            String kind = fileName.substring(0, slash);
            String entry = fileName.substring(slash + 1);
            if (!stripYmlLower(entry).equals("default")) {
                io.github.dailystruggle.rtp.common.configuration.ConfigDirectives d =
                        directivesFor(kind + "/default", paramName);
                if (hasFiniteDomain(d)) return d;
            }
        }

        int dot = paramName.indexOf('.');
        if (dot <= 0) return empty;
        String parent = paramName.substring(0, dot);

        // Candidate B: follow an explicit @<file> reference on the parent block.
        try {
            String rawParent;
            if (multi) {
                rawParent = resolveCurrentMultiConfigValueAsString(
                        fileName.substring(0, slash), fileName.substring(slash + 1), parent);
            } else {
                rawParent = resolveCurrentConfigValueAsString(fileName, parent);
            }
            if (io.github.dailystruggle.rtp.common.configuration
                    .ConfigDefaultResolver.isReference(rawParent)) {
                String refFile = io.github.dailystruggle.rtp.common.configuration
                        .ConfigDefaultResolver.referencedFile(rawParent);
                if (refFile != null && !refFile.isEmpty()) {
                    String lookupKey = refFile.equals("config")
                            ? "defaults." + paramName
                            : paramName;
                    io.github.dailystruggle.rtp.common.configuration.ConfigDirectives d =
                            directivesFor(refFile, lookupKey);
                    if (hasFiniteDomain(d)) return d;
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to candidate C
        }

        // Candidate C: region/world block schemas mirror config.yml#defaults, so
        // a materialized (non-reference) inherited block still finds its
        // directive under config.yml#defaults.<paramName>.
        io.github.dailystruggle.rtp.common.configuration.ConfigDirectives fromConfig =
                directivesFor("config", "defaults." + paramName);
        if (hasFiniteDomain(fromConfig)) return fromConfig;

        return empty;
    }

    /**
     * Read the in-memory block comment for {@code key} in {@code fileName} and
     * parse it into {@link io.github.dailystruggle.rtp.common.configuration.ConfigDirectives}.
     * Returns empty directives when the file's YAML root is unavailable or the
     * comment lookup fails. No file I/O.
     */
    private static io.github.dailystruggle.rtp.common.configuration.ConfigDirectives
            directivesFor(String fileName, String key) {
        io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection root =
                resolveYamlRootForFile(fileName);
        if (root == null) {
            return io.github.dailystruggle.rtp.common.configuration.ConfigDirectives.parse(null);
        }
        String comment;
        try {
            comment = root.getComment(key);
        } catch (RuntimeException ignored) {
            return io.github.dailystruggle.rtp.common.configuration.ConfigDirectives.parse(null);
        }
        return io.github.dailystruggle.rtp.common.configuration.ConfigDirectives.parse(comment);
    }

    /**
     * @return {@code true} when {@code directives} declares a finite domain
     *     (a non-empty {@code @options} list or a non-empty {@code @source}).
     */
    private static boolean hasFiniteDomain(
            io.github.dailystruggle.rtp.common.configuration.ConfigDirectives directives) {
        return directives != null
                && (!directives.options().isEmpty()
                    || (directives.source() != null && !directives.source().isEmpty()));
    }

    /**
     * Resolve the loaded in-memory YAML root for {@code fileName}, accepting
     * both a bare single-config file name (matched against
     * {@link RTP#configs} {@code configParserMap}, {@code .yml}-tolerant) and a
     * {@code kind/entryName} multiconfig form (matched against the multiconfig
     * sub-parsers by entry name). Returns {@code null} when configs are not
     * loaded or no parser matches.
     */
    private static io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection
            resolveYamlRootForFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        if (RTP.configs == null) return null;
        try {
            int slash = fileName.indexOf('/');
            if (slash > 0 && slash < fileName.length() - 1) {
                String entry = stripYmlLower(fileName.substring(slash + 1));
                for (io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<?> mcp
                        : RTP.configs.multiConfigParserMap.values()) {
                    if (mcp == null) continue;
                    for (String name : mcp.listParsers()) {
                        io.github.dailystruggle.rtp.common.configuration.ConfigParser<?> sub =
                                mcp.getParser(name);
                        if (sub == null || sub.name == null) continue;
                        if (stripYmlLower(sub.name).equals(entry)) {
                            return sub.getYamlRoot();
                        }
                    }
                }
                return null;
            }
            String target = stripYmlLower(fileName);
            for (io.github.dailystruggle.rtp.common.configuration.ConfigParser<?> p
                    : RTP.configs.configParserMap.values()) {
                if (p == null || p.name == null) continue;
                if (stripYmlLower(p.name).equals(target)) {
                    return p.getYamlRoot();
                }
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static String stripYmlLower(String s) {
        if (s == null) return "";
        String v = s.toLowerCase(java.util.Locale.ROOT);
        if (v.endsWith(".yml")) v = v.substring(0, v.length() - 4);
        return v;
    }

    // ------------------------------------------------------------------------
    // PROPOSAL-rtp-menu-config-search.md — two dispatch arms.
    // OpenConfigSearchPrompt translates to the existing anvil-input flow per
    // §6 Q4 Decision A (renderer-emitted action -> server synthesizes a
    // PromptAnvilInput(["menu","config","search"], "query", "") and reuses
    // dispatchPromptAnvilInput's wiring). OpenConfigSearchResults gates on
    // rtp.config.view and routes through configSearchBuilder; shape mirrors
    // dispatchOpenConfigKey including the optional-builder / renderer guard,
    // null-model S-004 reject path, and renderer-throw guard.
    // ------------------------------------------------------------------------

    /**
     * Dispatch for {@link MenuAction.OpenConfigSearchPrompt}. Q4 Decision A:
     * the renderer emits this action when the search button is clicked, and
     * the server translates it into a
     * {@link MenuAction.PromptAnvilInput} routed through the existing
     * {@link #anvilInputOpener} (no new TreeCommand leaf required for the
     * prompt itself; the submit landing leaf is wired separately under
     * {@code /rtp menu config search}). Reuses the
     * {@link #dispatchPromptAnvilInput} reject paths.
     */
    // ADR-050 Stage 1b: package-private.
    boolean dispatchOpenConfigSearchPrompt(UUID senderId,
                                                   @Nullable Consumer<String> messageMethod) {
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu config-search prompt denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-search prompt rejected: permission denied",
                    messageMethod);
            return false;
        }
        // parentPath is expressed RELATIVE to `/rtp menu` -- the anvil
        // submit re-prefixes `/rtp menu` in AnvilInputSession.buildCommand,
        // so prepending "menu" here would synthesize a doubled token
        // (`/rtp menu menu config search query=<typed>`) and trip the
        // commands-api `invalidCommand` reject path on the second `menu`.
        // Every other PromptAnvilInput caller follows this contract; see
        // e.g. the STAGE-mode arm a few lines above.
        MenuAction.PromptAnvilInput prompt = new MenuAction.PromptAnvilInput(
                new String[]{"config", "search"}, "query", "");
        return dispatchPromptAnvilInput(senderId, prompt, messageMethod);
    }

    /**
     * Dispatch for {@link MenuAction.OpenConfigSearchResults}. Gates on
     * {@link #CONFIG_VIEW_PERMISSION}, then routes through
     * {@link #configSearchBuilder}'s {@code buildResults}. A {@code null}
     * return from the builder (e.g. empty / unresolvable query) is an S-004
     * reject path. Shape mirrors {@link #dispatchOpenConfigKey}.
     */
    // ADR-050 Stage 1b: package-private.
    boolean dispatchOpenConfigSearchResults(UUID senderId,
                                                    MenuAction.OpenConfigSearchResults search,
                                                    @Nullable Consumer<String> messageMethod) {
        if (renderer == null || configSearchBuilder == null) {
            RTP.log(Level.WARNING,
                    "menu config-search received with config-search builder disabled for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-search rejected: config-search builder disabled",
                    messageMethod);
            return false;
        }
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu config-search denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-search rejected: permission denied", messageMethod);
            return false;
        }
        String query = search.query();
        int page = search.page();
        MenuModel model;
        try {
            model = configSearchBuilder.buildResults(senderId, query, page);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu config-search builder failed for " + senderId
                            + " query=" + query + " page=" + page
                            + ": " + e.getMessage(), e);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-search rejected: builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            RTP.log(Level.WARNING,
                    "menu config-search unresolved (query=" + query
                            + ", page=" + page + ") for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-search rejected: unresolved (query=" + query
                            + ", page=" + page + ")", messageMethod);
            return false;
        }
        return MenuDrawer.draw(renderer, senderId, model, messageMethod,
                this::reject, "config-search",
                "query=" + query + " page=" + page);
    }

    // ------------------------------------------------------------------------
    // PROPOSAL-admin-panel.md v2 — two curated-page dispatch arms. Shape
    // mirrors dispatchOpenConfigSelector: optional builder + renderer guard,
    // S-004 reject on every failure path. OpenAdminPanel additionally gates
    // on rtp.menu.admin; OpenFrontPage is unconditionally callable (the front
    // page is the default landing for any menu viewer).
    // ------------------------------------------------------------------------

    /**
     * Returns {@code true} when {@code senderId} carries
     * {@link #ADMIN_MENU_PERMISSION}. Treats a null probe as deny-by-default
     * for the admin surface, matching {@link #hasConfigViewPermission}.
     */
    // ADR-050 split: delegates to {@link #permissionGates}.
    private boolean hasAdminMenuPermission(UUID senderId) {
        return permissionGates.hasAdminMenu(senderId);
    }

    /**
     * Dispatch for {@link MenuAction.OpenAdminPanel}
     * (PROPOSAL-admin-panel.md v2). Gates on {@link #ADMIN_MENU_PERMISSION},
     * then routes through {@link #curatedPageBuilder}'s
     * {@code buildAdminPanel}. All failure paths log WARN and reject with
     * {@code menuInvalid} (S-004).
     */
    // ADR-050 (Proposed 2026-05-24): package-private for the concrete
    // `/rtp menu admin` leaf. Permission gate stays inside.
    boolean dispatchOpenAdminPanel(UUID senderId,
                                           @Nullable Consumer<String> messageMethod) {
        if (renderer == null || curatedPageBuilder == null) {
            RTP.log(Level.WARNING,
                    "menu admin-panel received with curated-page builder disabled for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu admin-panel rejected: curated-page builder disabled",
                    messageMethod);
            return false;
        }
        if (!hasAdminMenuPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu admin-panel denied: " + senderId
                            + " lacks " + ADMIN_MENU_PERMISSION);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu admin-panel rejected: permission denied", messageMethod);
            return false;
        }
        MenuModel model;
        try {
            model = curatedPageBuilder.buildAdminPanel(senderId);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu admin-panel builder failed for " + senderId
                            + ": " + e.getMessage(), e);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu admin-panel rejected: builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            reject(senderId, CommandMessages.menuInvalid,
                    "menu admin-panel rejected: builder returned null model",
                    messageMethod);
            return false;
        }
        return MenuDrawer.draw(renderer, senderId, model, messageMethod,
                this::reject, "admin-panel");
    }

    /**
     * Dispatch for {@link MenuAction.OpenVisualizations}. Gates on
     * {@link #ADMIN_MENU_PERMISSION} (same surface as
     * {@link #dispatchOpenAdminPanel}), then routes through the curated-page
     * builder's {@code buildVisualizations}. All failure paths log WARN and
     * reject with {@code menuInvalid} (S-004).
     */
    // ADR-050 (Proposed 2026-05-24): package-private for the concrete
    // `/rtp menu visualizations` and `/rtp visualization` leaves.
    // Permission gate (rtp.menu.admin) stays inside.
    boolean dispatchOpenVisualizations(UUID senderId,
                                               @Nullable Consumer<String> messageMethod) {
        if (renderer == null || curatedPageBuilder == null) {
            RTP.log(Level.WARNING,
                    "menu visualizations received with curated-page builder disabled for "
                            + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu visualizations rejected: curated-page builder disabled",
                    messageMethod);
            return false;
        }
        if (!hasAdminMenuPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu visualizations denied: " + senderId
                            + " lacks " + ADMIN_MENU_PERMISSION);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu visualizations rejected: permission denied", messageMethod);
            return false;
        }
        MenuModel model;
        try {
            model = curatedPageBuilder.buildVisualizations(senderId);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu visualizations builder failed for " + senderId
                            + ": " + e.getMessage(), e);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu visualizations rejected: builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            reject(senderId, CommandMessages.menuInvalid,
                    "menu visualizations rejected: builder returned null model",
                    messageMethod);
            return false;
        }
        return MenuDrawer.draw(renderer, senderId, model, messageMethod,
                this::reject, "visualizations");
    }

    /**
     * Kind-scoped variant of {@link #dispatchOpenVisualizations}: opens the
     * per-kind region picker (one row per configured region, each row
     * dispatching {@link MapDispatch} for the given {@code kind}). Used by
     * the top-level chart-kind picker's row clicks, which land here via
     * each visualization leaf's no-region selector fallback. Gated on
     * {@link #ADMIN_MENU_PERMISSION}; all failure paths log WARN and
     * reject with {@code menuInvalid} (S-004).
     */
    boolean dispatchOpenVisualizationRegions(UUID senderId,
                                             ChartSpec.Kind kind,
                                             @Nullable Consumer<String> messageMethod) {
        if (kind == null) {
            RTP.log(Level.WARNING,
                    "menu visualization-regions rejected: null kind for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu visualization-regions rejected: null kind", messageMethod);
            return false;
        }
        if (renderer == null || curatedPageBuilder == null) {
            RTP.log(Level.WARNING,
                    "menu visualization-regions received with curated-page builder disabled for "
                            + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu visualization-regions rejected: curated-page builder disabled",
                    messageMethod);
            return false;
        }
        if (!hasAdminMenuPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu visualization-regions denied: " + senderId
                            + " lacks " + ADMIN_MENU_PERMISSION);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu visualization-regions rejected: permission denied", messageMethod);
            return false;
        }
        MenuModel model;
        try {
            model = curatedPageBuilder.buildVisualizationRegions(senderId, kind);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu visualization-regions builder failed for " + senderId
                            + " kind=" + kind + ": " + e.getMessage(), e);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu visualization-regions rejected: builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            reject(senderId, CommandMessages.menuInvalid,
                    "menu visualization-regions rejected: builder returned null model",
                    messageMethod);
            return false;
        }
        return MenuDrawer.draw(renderer, senderId, model, messageMethod,
                this::reject, "visualization-regions[" + kind + "]");
    }

    /**
     * Dispatch for {@link MenuAction.OpenFrontPage}
     * (PROPOSAL-admin-panel.md v2). No permission gate: the curated front page
     * is the default landing for any menu viewer. All failure paths log WARN
     * and reject with {@code menuInvalid} (S-004).
     */
    // ADR-050 (Proposed 2026-05-24): package-private for the concrete
    // `/rtp menu front` leaf.
    boolean dispatchOpenFrontPage(UUID senderId,
                                          @Nullable Consumer<String> messageMethod) {
        if (renderer == null || curatedPageBuilder == null) {
            RTP.log(Level.WARNING,
                    "menu front-page received with curated-page builder disabled for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu front-page rejected: curated-page builder disabled",
                    messageMethod);
            return false;
        }
        MenuModel model;
        try {
            model = curatedPageBuilder.buildFrontPage(senderId);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu front-page builder failed for " + senderId
                            + ": " + e.getMessage(), e);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu front-page rejected: builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            reject(senderId, CommandMessages.menuInvalid,
                    "menu front-page rejected: builder returned null model",
                    messageMethod);
            return false;
        }
        return MenuDrawer.draw(renderer, senderId, model, messageMethod,
                this::reject, "front-page");
    }

    /**
     * Probes the {@code rtp.info} permission for {@code senderId}. Returns
     * {@code false} on a {@link RuntimeException} from the probe (S-007:
     * an exception is treated as a denial).
     */
    // ADR-050 split: delegates to {@link #permissionGates}.
    private boolean hasInfoPermission(UUID senderId) {
        return permissionGates.hasInfo(senderId);
    }

    /**
     * Dispatch for {@link MenuAction.OpenInfo}
     * (PROPOSAL-info-as-book.md section 4.6). Gates on {@code rtp.info},
     * then routes through {@link #infoBookBuilder}'s
     * {@link MenuInfoBookBuilder#build} and hands the resulting
     * {@link MenuModel} to the renderer. All failure paths log WARN and
     * reject with {@code menuInvalid} (S-004).
     */
    // ADR-050 Stage 1b: package-private.
    boolean dispatchOpenInfo(UUID senderId,
                                     MenuAction.OpenInfo action,
                                     @Nullable Consumer<String> messageMethod) {
        if (renderer == null || infoBookBuilder == null) {
            RTP.log(Level.WARNING,
                    "menu info-book received with info-book builder disabled for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu info-book rejected: info-book builder disabled",
                    messageMethod);
            return false;
        }
        if (!hasInfoPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu info-book denied: " + senderId + " lacks rtp.info");
            reject(senderId, CommandMessages.menuInvalid,
                    "menu info-book rejected: permission denied", messageMethod);
            return false;
        }
        MenuModel model;
        try {
            model = infoBookBuilder.build(senderId, action.scope());
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu info-book builder failed for " + senderId
                            + ": " + e.getMessage(), e);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu info-book rejected: builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            reject(senderId, CommandMessages.menuInvalid,
                    "menu info-book rejected: builder returned null model",
                    messageMethod);
            return false;
        }
        return MenuDrawer.draw(renderer, senderId, model, messageMethod,
                this::reject, "info-book");
    }

    /**
     * Dispatch for {@link MenuAction.SwitchInfoToText}
     * (PROPOSAL-info-as-book.md section 4.6). Gates on {@code rtp.info}, then
     * re-enters the chat path by synthesising the equivalent {@code /rtp info}
     * {@link MenuAction.RunRtpCommand}. No book is rendered; the player sees
     * the standard chat output. Failures log WARN + reject (S-004).
     */
    // ADR-050 Stage 1b: package-private.
    boolean dispatchSwitchInfoToText(UUID senderId,
                                             MenuAction.SwitchInfoToText action,
                                             @Nullable Consumer<String> messageMethod) {
        if (!hasInfoPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu info-switch-to-text denied: " + senderId + " lacks rtp.info");
            reject(senderId, CommandMessages.menuInvalid,
                    "menu info-switch-to-text rejected: permission denied",
                    messageMethod);
            return false;
        }
        // Build the equivalent /rtp info argv for the requested scope and
        // dispatch through the existing RunRtpCommand arm so locale, colour,
        // permission, and pipeline behaviour stay identical to a typed
        // /rtp info <scope:name> invocation.
        String[] args;
        switch (action.scope().kind()) {
            case GLOBAL -> args = new String[]{"info"};
            case WORLD  -> args = new String[]{"info", "world=" + action.scope().name()};
            case REGION -> args = new String[]{"info", "region=" + action.scope().name()};
            default     -> {
                RTP.log(Level.WARNING,
                        "menu info-switch-to-text: unknown scope kind "
                                + action.scope().kind() + " for " + senderId);
                reject(senderId, CommandMessages.menuInvalid,
                        "menu info-switch-to-text rejected: unknown scope",
                        messageMethod);
                return false;
            }
        }
        return dispatchRun(senderId,
                new MenuAction.RunRtpCommand(args),
                messageMethod);
    }

    /**
     * Dispatch for {@link MenuAction.OpenMap} (ADR-047 / REQ-RTP-MAP-006).
     *
     * <ol>
     *   <li>Gates on {@link #ADMIN_MENU_PERMISSION}: charts surface admin-
     *       facing data ({@code MemoryShape.badKeysSnapshot} today; broader
     *       {@code MetricsSnapshot} integration in Stage 3 of
     *       {@code CHECKLIST-metrics-to-maps.md}). Denial logs WARN +
     *       rejects with {@code menuInvalid} (S-004).</li>
     *   <li>Builds a {@link ChartSpec} inline from the action's
     *       {@code (kind, regionName)} payload (ADR-050 Stage 3β: the
     *       prior {@code ChartSpecTokens} round-trip is gone). Invalid
     *       inputs collapse to {@code menuInvalid} (S-004).</li>
     *   <li>Hands off to {@link MapDispatch#paint(ChartSpec, UUID)}, which
     *       owns the {@link CommandMessages#mapBindingMissing} /
     *       {@code mapResolverMissing} / {@code mapUnavailable} /
     *       {@code mapBusy} viewer-facing surfaces. We return {@code true} on
     *       a successful paint and {@code false} otherwise; the viewer has
     *       already been notified through {@code MapDispatch} in the failure
     *       case.</li>
     * </ol>
     */
    private boolean dispatchOpenMap(UUID senderId,
                                    MenuAction.OpenMap action,
                                    @Nullable Consumer<String> messageMethod) {
        if (!hasAdminMenuPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu open-map denied: " + senderId
                            + " lacks " + ADMIN_MENU_PERMISSION);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu open-map rejected: permission denied",
                    messageMethod);
            return false;
        }
        // ADR-050 Stage 3β: build the ChartSpec inline from (kind, regionName);
        // the prior ChartSpecTokens round-trip is gone.
        ChartSpec spec;
        try {
            spec = ChartSpec.of(action.kind(), action.regionName());
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu open-map rejected: invalid ChartSpec (kind=" + action.kind()
                            + ", regionName='" + action.regionName() + "') for " + senderId
                            + ": " + e.getMessage(), e);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu open-map rejected: invalid ChartSpec",
                    messageMethod);
            return false;
        }
        try {
            // MapDispatch.paint owns the configurable viewer-facing failure
            // surfaces (mapBindingMissing / mapResolverMissing /
            // mapUnavailable / mapBusy) and returns false after notifying the
            // viewer through MessagesKeys. We do not reject() on its false
            // return: that would double-message the player.
            return MapDispatch.paint(spec, senderId);
        } catch (RuntimeException e) {
            // Defensive S-004: MapDispatch.paint is supposed to catch its
            // own RuntimeExceptions, but if a downstream binding leaks one
            // we still surface it through the configurable channel.
            RTP.log(Level.WARNING,
                    "menu open-map MapDispatch.paint threw for " + senderId
                            + ": " + e.getMessage(), e);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu open-map rejected: dispatch failure", messageMethod);
            return false;
        }
    }

    /**
     * Shared finalisation step for both {@link #openPage} (no-token root) and
     * {@link #dispatchOpen} (token-bearing OpenMenu): invoke the configured
     * {@link #pageBuilder} for {@code target} carrying the {@code assembledPath},
     * then hand the resulting {@link MenuModel} to the {@link #renderer}. All
     * failure paths log WARN + reject with {@code menuInvalid} (S-004); the
     * renderer is never invoked with a null model.
     *
     * <p>Mirror entrypoint: route to the curated config-subtree dispatchers when
     * the assembled path lands inside {@code /rtp config ...}, so the
     * args-form mirror walk opens the *same* menu page that the click-driven
     * navigation would (curated file / key pages built by
     * {@link #configSubtreeBuilder}). All other paths fall through to
     * {@link #renderAt}'s generic {@code pageBuilder} render. Staged
     * parameter segments (e.g. {@code teleportDelay:1}) influence config-key
     * routing: their presence on a config-file leaf elevates the render from
     * a file page to a key page on the first staged parameter.
     */
    boolean renderForPath(UUID senderId,
                          TreeCommand target,
                          java.util.List<String> assembledPath,
                          java.util.Map<String, java.util.List<String>> parameterValues,
                          int pageIndex,
                          @Nullable Consumer<String> messageMethod) {
        // Mirror-walk routing into curated config pages. Previously disabled
        // (TODO(menu-anvil-reopen)) because anvil-confirm landed the player
        // back on the value-picker with no Run/Back affordance. With the
        // staging-cart wired (Mode.STAGE on PromptAnvilInput + AnvilInputSession
        // STAGE-mode reopen to `/rtp config <file>`), the loop is broken at
        // the anvil-confirm site: STAGE-mode confirms stage into the cart and
        // reopen the curated file page, never landing back on buildParamPicker.
        // RUN-mode confirms still run-and-close as before. Re-enabling so the
        // args-form mirror walk opens the same curated page as the click flow.
        if (renderer != null
                && configSubtreeBuilder != null
                && !assembledPath.isEmpty()
                && "config".equalsIgnoreCase(assembledPath.get(0))) {
            if (assembledPath.size() == 1) {
                return dispatchOpenConfigSelector(senderId, messageMethod);
            }
            // `/rtp menu config search query=<typed>` lands here via the
            // anvil-input submit from OpenConfigSearchPrompt (see
            // PROPOSAL-rtp-menu-config-search.md §6 Q4 Decision A). Route
            // to the search-results dispatcher instead of falling through
            // to the config-file/key dispatchers, which would treat
            // `search` as a config file name and re-open the per-key
            // anvil editor for `query`.
            if (assembledPath.size() >= 2
                    && "search".equalsIgnoreCase(assembledPath.get(1))) {
                String query = "";
                if (parameterValues != null) {
                    java.util.List<String> qArgs = parameterValues.get(
                            io.github.dailystruggle.rtp.common.commands.config.ConfigSearchSubCmd.PARAM_QUERY);
                    if (qArgs != null && !qArgs.isEmpty() && qArgs.get(0) != null) {
                        query = qArgs.get(0);
                    }
                }
                return dispatchOpenConfigSearchResults(senderId,
                        new MenuAction.OpenConfigSearchResults(query, 0),
                        messageMethod);
            }
            String fileName = assembledPath.get(1);
            // Multi-config directory routing (2026-05-23, follow-up to
            // ADR-043 / multiconfig-menu wiring). When `assembledPath[1]`
            // names a MultiConfigParser kind (`regions`, `worlds`, future
            // `effects`) AND a third segment is present, the user is
            // addressing a per-entry editor (e.g.
            // `/rtp menu config regions default`). Route those to
            // `dispatchOpenMultiConfigEntry` so the entry page is built by
            // `MultiConfigMenuBuilder.buildEntry` (the cart-aware
            // synthetic-fileName editor) - NOT through the generic
            // TreeCommand pageBuilder render, which would land on the
            // per-region `SubConfigCmd`'s flat-config view and produce a
            // visually distinct (and feature-poor) page.
            //
            // Without this, two regressions appeared:
            //   - issue A: built-in `default` rendered via the generic
            //     tree walker (CommandTreeMenuBuilder.buildConfigFile),
            //     while runtime-added `default1234` rendered via
            //     MultiConfigMenuBuilder.buildEntry - two visibly
            //     different editor styles for the "same kind" of entry.
            //   - issues B/C: the STAGE-mode reopen from AnvilInputSession
            //     emits `/rtp menu config regions default` to land back
            //     on the entry editor with the cart surfaced; without
            //     this branch the walker fell through to
            //     dispatchOpenConfigFile("regions") and rejected with
            //     "unknown file 'regions'".
            if (assembledPath.size() >= 3
                    && resolveMultiConfigParser(fileName) != null) {
                String entryName = assembledPath.get(2);
                return dispatchOpenMultiConfigEntry(senderId,
                        new MenuAction.OpenMultiConfigEntry(fileName, entryName),
                        messageMethod);
            }
            String stagedParam = null;
            if (parameterValues != null) {
                for (java.util.Map.Entry<String, java.util.List<String>> e
                        : parameterValues.entrySet()) {
                    String key = e.getKey();
                    if (key == null) continue;
                    if (PARAM_PAGE.equalsIgnoreCase(key)) continue;
                    java.util.List<String> vs = e.getValue();
                    if (vs == null || vs.isEmpty()) continue;
                    stagedParam = key;
                    break;
                }
            }
            // Per commands-api contract (commands-api/docs/README.md):
            // `name=value` segments are parameters, not bare path elements.
            // The mirror leaf hands us a sub-command-only path plus the
            // parsed parameterValues map; routing to OpenConfigKey must read
            // the parameter name from the map (the `stagedParam` branch
            // below), never from assembledPath. Treating assembledPath.get(2)
            // as a key name surfaced `canceldistance=5` to dispatchOpenConfigKey
            // as a single opaque token (the regression the user diagnosed
            // 2026-05-22).
            if (stagedParam != null) {
                return dispatchOpenConfigKey(senderId,
                        new MenuAction.OpenConfigKey(fileName, stagedParam),
                        messageMethod);
            }
            return dispatchOpenConfigFile(senderId,
                    new MenuAction.OpenConfigFile(fileName), messageMethod);
        }
        return renderAt(senderId, target, assembledPath, pageIndex, messageMethod);
    }

    /**
     * Re-render helper for stage / unstage / discard / apply paths.
     * Detects the slash-aware "<kind>/<entryName>" synthetic fileName
     * introduced 2026-05-23 for multiconfig entry editing and routes
     * back into {@link #dispatchOpenMultiConfigEntry}; otherwise falls
     * through to the flat-config {@link #dispatchOpenConfigFile}. Used
     * so cart operations on a multiconfig entry re-open the entry page
     * (with the updated cart surfaced) instead of the unrelated flat-
     * config selector that {@code OpenConfigFile("regions/default")}
     * would otherwise try to resolve.
     */
    // Package-private so the concrete ConfigCmd leaf (in this package) can
    // call it for slash-bearing `file=<kind>/<entry>` reopens after a STAGE
    // anvil-confirm. ADR-050.
    boolean reopenAfterCartOp(UUID senderId,
                                      String fileName,
                                      @Nullable Consumer<String> messageMethod) {
        int slash = fileName == null ? -1 : fileName.indexOf('/');
        if (slash > 0 && slash < fileName.length() - 1) {
            String kind = fileName.substring(0, slash);
            String entryName = fileName.substring(slash + 1);
            return dispatchOpenMultiConfigEntry(senderId,
                    new MenuAction.OpenMultiConfigEntry(kind, entryName),
                    messageMethod);
        }
        return dispatchOpenConfigFile(senderId,
                new MenuAction.OpenConfigFile(fileName), messageMethod);
    }

    boolean renderAt(UUID senderId,
                     TreeCommand target,
                     java.util.List<String> assembledPath,
                     int pageIndex,
                     @Nullable Consumer<String> messageMethod) {
        MenuModel model;
        MenuOpenRequest open = new MenuOpenRequest(senderId, Math.max(0, pageIndex));
        try {
            model = pageBuilder.build(target, open, assembledPath);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu open-page failed for " + senderId
                            + " node=" + target.name() + ": " + e.getMessage(), e);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu open rejected: page builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            reject(senderId, CommandMessages.menuInvalid,
                    "menu open rejected: builder returned null model", messageMethod);
            return false;
        }
        return MenuDrawer.draw(renderer, senderId, model, messageMethod,
                this::reject, "open", "node=" + target.name());
    }

    /**
     * Dispatch for {@link MenuAction.StageConfigValue}: route the
     * {@code (fileName, paramName, value)} triple into the player's cart,
     * then re-render the curated {@code /rtp config <file>} page so the
     * staged entry surfaces under "Pending changes". Gates on
     * {@link #CONFIG_VIEW_PERMISSION}. The cart is single-file-scoped, so a
     * stage for a different file than the existing cart silently replaces
     * the cart (matching the user-confirmed proposal). All failure paths
     * log WARN and reject with {@code menuInvalid} (S-004).
     */
    // ADR-050 Stage 1b: package-private.
    boolean dispatchStageConfigValue(UUID senderId,
                                             MenuAction.StageConfigValue stage,
                                             @Nullable Consumer<String> messageMethod) {
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu config-stage denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-stage rejected: permission denied", messageMethod);
            return false;
        }
        try {
            stageInCart(senderId, stage.fileName(), stage.paramName(), stage.value());
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu config-stage failed for " + senderId
                            + " file=" + stage.fileName()
                            + " key=" + stage.paramName()
                            + ": " + e.getMessage(), e);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-stage rejected: cart failure", messageMethod);
            return false;
        }
        // Re-render the curated file page so the player sees the updated
        // cart. Slash-aware re-render (2026-05-23): when fileName is a
        // "<kind>/<entryName>" synthetic for multiconfig entry editing,
        // route back through dispatchOpenMultiConfigEntry instead.
        return reopenAfterCartOp(senderId, stage.fileName(), messageMethod);
    }

    /**
     * Dispatch for {@link MenuAction.UnstageConfigValue}: remove the named
     * entry from the player's cart and re-render the curated
     * {@code /rtp config <file>} page. Gates on
     * {@link #CONFIG_VIEW_PERMISSION}. No-op when the cart is empty, scoped
     * to a different file, or does not contain {@code paramName}.
     */
    // ADR-050 Stage 1b: package-private.
    boolean dispatchUnstageConfigValue(UUID senderId,
                                               MenuAction.UnstageConfigValue unstage,
                                               @Nullable Consumer<String> messageMethod) {
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu config-unstage denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-unstage rejected: permission denied", messageMethod);
            return false;
        }
        try {
            unstageInCart(senderId, unstage.fileName(), unstage.paramName());
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu config-unstage failed for " + senderId
                            + " file=" + unstage.fileName()
                            + " key=" + unstage.paramName()
                            + ": " + e.getMessage(), e);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-unstage rejected: cart failure", messageMethod);
            return false;
        }
        return reopenAfterCartOp(senderId, unstage.fileName(), messageMethod);
    }

    /**
     * Dispatch for {@link MenuAction.ApplyStagedConfig}: pop the player's
     * cart for {@code fileName} and execute the batched command
     * {@code /rtp config <fileName> <k1>=<v1> <k2>=<v2> ...} as the player.
     * Gates on {@link #CONFIG_VIEW_PERMISSION}. An empty cart (or a cart
     * scoped to a different file) is an S-004 reject path so the user gets
     * feedback that nothing applied; this matches the existing dispatch-arm
     * shape. The cart is cleared atomically before the underlying command
     * runs, so a failed apply does not leave the cart half-applied.
     */
    // ADR-050 Stage 1b: package-private.
    boolean dispatchApplyStagedConfig(UUID senderId,
                                              MenuAction.ApplyStagedConfig apply,
                                              @Nullable Consumer<String> messageMethod) {
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu config-apply denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-apply rejected: permission denied", messageMethod);
            return false;
        }
        LinkedHashMap<String, String> entries = applyCart(senderId, apply.fileName());
        if (entries.isEmpty()) {
            RTP.log(Level.WARNING,
                    "menu config-apply: empty cart for " + senderId
                            + " file=" + apply.fileName());
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-apply rejected: empty cart for '" + apply.fileName() + "'",
                    messageMethod);
            return false;
        }
        // Build args. For flat configs: ["config", fileName, "k=v"...].
        // For multiconfig entries (slash-aware, 2026-05-23): the fileName
        // is "<kind>/<entryName>" and the CLI shape is
        // /rtp config <kind> <entryName> k=v ..., so the slash splits
        // into two args. The commands-api parameter parser accepts
        // multiple `k=v` pairs on a single command line, so a single
        // batched dispatch suffices.
        String fn = apply.fileName();
        int applySlash = fn.indexOf('/');
        String[] args;
        if (applySlash > 0 && applySlash < fn.length() - 1) {
            args = new String[3 + entries.size()];
            args[0] = "config";
            args[1] = fn.substring(0, applySlash);
            args[2] = fn.substring(applySlash + 1);
            int i = 3;
            for (Map.Entry<String, String> e : entries.entrySet()) {
                args[i++] = e.getKey() + "=" + e.getValue();
            }
        } else {
            args = new String[2 + entries.size()];
            args[0] = "config";
            args[1] = fn;
            int i = 2;
            for (Map.Entry<String, String> e : entries.entrySet()) {
                args[i++] = e.getKey() + "=" + e.getValue();
            }
        }
        boolean ok = dispatchRun(senderId, new MenuAction.RunRtpCommand(args), messageMethod);
        // Deliberately do NOT re-open the editor after a successful apply.
        // The underlying config write/reload propagates asynchronously, so an
        // immediate re-open would re-render the page from the pre-apply value
        // and show the operator the old setting (a confusing stale read).
        // Waiting for the reload before re-opening is clunky and racy from
        // rtp-core, so apply instead leaves the book closed; the dispatched
        // `/rtp config ...` command already emits its own success/feedback
        // message. Stage / unstage / discard still re-open because they only
        // mutate the in-memory cart, which is updated synchronously.
        return ok;
    }

    /**
     * Dispatch for {@link MenuAction.DiscardStagedConfig}: drop the player's
     * cart for {@code fileName} without applying any edits, then re-render
     * the curated {@code /rtp config <file>} page. Gates on
     * {@link #CONFIG_VIEW_PERMISSION}.
     */
    // ADR-050 Stage 1b: package-private.
    boolean dispatchDiscardStagedConfig(UUID senderId,
                                                MenuAction.DiscardStagedConfig discard,
                                                @Nullable Consumer<String> messageMethod) {
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu config-discard denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu config-discard rejected: permission denied", messageMethod);
            return false;
        }
        // applyCart pops and returns; we discard the result.
        applyCart(senderId, discard.fileName());
        return reopenAfterCartOp(senderId, discard.fileName(), messageMethod);
    }

    // ------------------------------------------------------------------------
    // CHECKLIST-multiconfig-menu step 7 (Path Z scope):
    // three dispatch arms for the generic MultiConfig submenu surface.
    // Entry page is browse-only this slice; per-key staging deferred.
    // ------------------------------------------------------------------------

    /**
     * Inject (or replace) the {@link io.github.dailystruggle.rtp.common.commands.menu.multiconfig.MultiConfigMenuBuilder}
     * used by the three new dispatch arms. Pass {@code null} to disable the
     * MultiConfig submenu surface; in that state, inbound tokens for the
     * three new {@link MenuAction} variants reject with {@code menuInvalid}
     * + WARN (S-004), matching the absent-builder fallback shape used by
     * the other curated submenus.
     */
    public void setMultiConfigBuilder(
            @Nullable io.github.dailystruggle.rtp.common.commands.menu.multiconfig.MultiConfigMenuBuilder builder) {
        this.multiConfigBuilder = builder;
    }

    /**
     * Resolve a {@link io.github.dailystruggle.rtp.common.configuration.MultiConfigParser}
     * by its case-insensitive {@code name}. Returns {@code null} when no
     * registered multi-config parser matches; the dispatch arms treat that
     * as an S-004 reject path (token forged with an unknown kind, or the
     * matching parser has been unregistered since the token was minted).
     */
    private static @Nullable io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<?> resolveMultiConfigParser(
            String parserKind) {
        if (parserKind == null || parserKind.isEmpty()) return null;
        if (RTP.configs == null) return null;
        String target = parserKind.toLowerCase(java.util.Locale.ROOT);
        try {
            for (io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<?> p
                    : RTP.configs.multiConfigParserMap.values()) {
                if (p == null || p.name == null) continue;
                if (p.name.toLowerCase(java.util.Locale.ROOT).equals(target)) {
                    return p;
                }
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    /**
     * Dispatch for {@link MenuAction.OpenMultiConfigSelector}. Gates on
     * {@link #CONFIG_VIEW_PERMISSION}. When the consumed token carries the
     * "toggle remove-mode" sentinel ({@code parserKind} is prefixed with
     * {@code "!toggle:"}), the per-viewer flag for the underlying kind is
     * flipped before the selector is re-rendered. All failure paths log
     * WARN + reject with {@code menuInvalid} (S-004).
     */
    // ADR-050 Stage 1b: package-private.
    boolean dispatchOpenMultiConfigSelector(UUID senderId,
                                                    MenuAction.OpenMultiConfigSelector open,
                                                    @Nullable Consumer<String> messageMethod) {
        if (renderer == null || multiConfigBuilder == null) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-selector received with multiconfig disabled for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu multiconfig-selector rejected: multiconfig disabled", messageMethod);
            return false;
        }
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-selector denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu multiconfig-selector rejected: permission denied", messageMethod);
            return false;
        }
        String rawKind = open.parserKind();
        String effectiveKind = rawKind;
        boolean isToggle = false;
        if (rawKind != null && rawKind.startsWith("!toggle:")) {
            effectiveKind = rawKind.substring("!toggle:".length());
            isToggle = true;
        }
        io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<?> parser =
                resolveMultiConfigParser(effectiveKind);
        if (parser == null) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-selector unknown parserKind '"
                            + effectiveKind + "' for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu multiconfig-selector rejected: unknown parser kind", messageMethod);
            return false;
        }
        // Apply the toggle flip (if any) before computing removeMode for
        // the render so the user sees the new state in the same click.
        final String fk = effectiveKind;
        if (isToggle) {
            removeModeKinds.compute(senderId, (id, s) -> {
                Set<String> set = (s != null) ? s : ConcurrentHashMap.newKeySet();
                String k = fk.toLowerCase(java.util.Locale.ROOT);
                if (!set.add(k)) {
                    set.remove(k);
                }
                return set.isEmpty() ? null : set;
            });
        }
        boolean removeMode = isRemoveMode(senderId, effectiveKind);
        MenuModel model;
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            MenuModel m = multiConfigBuilder.buildSelector(
                    effectiveKind, (io.github.dailystruggle.rtp.common.configuration.MultiConfigParser) parser,
                    removeMode, senderId);
            model = m;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-selector builder failed for " + senderId
                            + " kind=" + effectiveKind + ": " + e.getMessage(), e);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu multiconfig-selector rejected: builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            reject(senderId, CommandMessages.menuInvalid,
                    "menu multiconfig-selector rejected: builder returned null model",
                    messageMethod);
            return false;
        }
        return MenuDrawer.draw(renderer, senderId, model, messageMethod,
                this::reject, "multiconfig-selector",
                "kind=" + effectiveKind);
    }

    /**
     * Read the per-viewer remove-mode flag for {@code parserKind} (case-
     * insensitive). Package-private for the dispatch tests.
     */
    boolean isRemoveMode(UUID viewer, String parserKind) {
        if (viewer == null || parserKind == null) return false;
        Set<String> set = removeModeKinds.get(viewer);
        if (set == null) return false;
        return set.contains(parserKind.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Dispatch for {@link MenuAction.OpenMultiConfigEntry}. Gates on
     * {@link #CONFIG_VIEW_PERMISSION}. Resolves the parser by kind, then
     * routes through {@link io.github.dailystruggle.rtp.common.commands.menu.multiconfig.MultiConfigMenuBuilder#buildEntry}.
     * An unknown kind or unknown entry name is an S-004 reject path.
     */
    // ADR-050 Stage 1b: package-private.
    boolean dispatchOpenMultiConfigEntry(UUID senderId,
                                                 MenuAction.OpenMultiConfigEntry open,
                                                 @Nullable Consumer<String> messageMethod) {
        if (renderer == null || multiConfigBuilder == null) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-entry received with multiconfig disabled for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu multiconfig-entry rejected: multiconfig disabled", messageMethod);
            return false;
        }
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-entry denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu multiconfig-entry rejected: permission denied", messageMethod);
            return false;
        }
        String kind = open.parserKind();
        String entry = open.entryName();
        io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<?> parser =
                resolveMultiConfigParser(kind);
        if (parser == null) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-entry unknown parserKind '" + kind + "' for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu multiconfig-entry rejected: unknown parser kind", messageMethod);
            return false;
        }
        MenuModel model;
        try {
            // Pass the viewer's cart snapshot scoped to the synthetic
            // "<kind>/<entry>" fileName so the entry page surfaces
            // Pending + Apply + Discard rows (the cart-aware
            // buildEntry overload landed 2026-05-23 to mirror the
            // flat-config page).
            String syntheticFileName = kind + "/" + entry;
            LinkedHashMap<String, String> cartSnap =
                    snapshotCart(senderId, syntheticFileName);
            @SuppressWarnings({"unchecked", "rawtypes"})
            MenuModel m = multiConfigBuilder.buildEntry(
                    kind, entry,
                    (io.github.dailystruggle.rtp.common.configuration.MultiConfigParser) parser,
                    senderId,
                    cartSnap);
            model = m;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-entry builder failed for " + senderId
                            + " kind=" + kind + " entry=" + entry + ": " + e.getMessage(), e);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu multiconfig-entry rejected: builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            reject(senderId, CommandMessages.menuInvalid,
                    "menu multiconfig-entry rejected: builder returned null model",
                    messageMethod);
            return false;
        }
        return MenuDrawer.draw(renderer, senderId, model, messageMethod,
                this::reject, "multiconfig-entry",
                "kind=" + kind + " entry=" + entry);
    }

    /**
     * Dispatch for {@link MenuAction.MultiConfigMutate}. Gates on
     * {@link #CONFIG_VIEW_PERMISSION}. Mutates the parser:
     * <ul>
     *   <li>ADD: {@link io.github.dailystruggle.rtp.common.configuration.MultiConfigParser#addParser(String)}
     *       (no-op if the entry already exists), then invokes
     *       {@link io.github.dailystruggle.rtp.common.commands.menu.multiconfig.NetherEndConfigAmender}
     *       when the entry name ends with {@code _nether} or {@code _the_end}
     *       (regions kind only). The nether/end amender is currently region-
     *       specific by design (it lives in {@code SubConfigCmd}); for other
     *       kinds (worlds, future) it is a no-op call site.</li>
     *   <li>REMOVE: re-checks the registered
     *       {@link io.github.dailystruggle.rtp.common.commands.menu.multiconfig.MultiConfigRemovalGuard}
     *       server-side. Locked entries are an S-004 reject path even if
     *       the client sent a forged token. Otherwise calls
     *       {@link io.github.dailystruggle.rtp.common.configuration.MultiConfigParser#removeParser(String)}.</li>
     * </ul>
     * On success, re-renders the selector page for the same kind so the
     * mutation is visible. The per-viewer remove-mode flag is cleared on a
     * successful REMOVE (so the next click is a normal navigation by
     * default).
     */
    // ADR-050 Stage 1b: package-private.
    boolean dispatchMultiConfigMutate(UUID senderId,
                                              MenuAction.MultiConfigMutate mutate,
                                              @Nullable Consumer<String> messageMethod) {
        if (renderer == null || multiConfigBuilder == null) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-mutate received with multiconfig disabled for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu multiconfig-mutate rejected: multiconfig disabled", messageMethod);
            return false;
        }
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-mutate denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu multiconfig-mutate rejected: permission denied", messageMethod);
            return false;
        }
        String kind = mutate.parserKind();
        String entry = mutate.entryName();
        MenuAction.MultiConfigMutate.Op op = mutate.op();
        io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<?> parser =
                resolveMultiConfigParser(kind);
        if (parser == null) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-mutate unknown parserKind '" + kind + "' for " + senderId);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu multiconfig-mutate rejected: unknown parser kind", messageMethod);
            return false;
        }
        if (op == MenuAction.MultiConfigMutate.Op.REMOVE) {
            // Server-side guard re-check: never trust the client to honour
            // the locked-row UI suppression.
            io.github.dailystruggle.rtp.common.commands.menu.multiconfig.MultiConfigRemovalGuard guard =
                    io.github.dailystruggle.rtp.common.commands.menu.multiconfig.MultiConfigRemovalGuards.get(kind);
            if (guard.isLocked(entry)) {
                RTP.log(Level.WARNING,
                        "menu multiconfig-mutate REMOVE rejected: '" + entry
                                + "' is locked under kind '" + kind + "' for " + senderId
                                + " (reason: " + guard.reason(entry) + ")");
                reject(senderId, CommandMessages.menuInvalid,
                        "menu multiconfig-mutate rejected: entry locked", messageMethod);
                return false;
            }
            try {
                parser.removeParser(entry);
            } catch (RuntimeException e) {
                RTP.log(Level.WARNING,
                        "menu multiconfig-mutate REMOVE failed for " + senderId
                                + " kind=" + kind + " entry=" + entry + ": " + e.getMessage(), e);
                reject(senderId, CommandMessages.menuInvalid,
                        "menu multiconfig-mutate rejected: remove failure", messageMethod);
                return false;
            }
            // Mirror SubConfigCmd's TreeCommand maintenance: drop the
            // per-entry child SubConfigCmd from the parent's commandLookup
            // so subsequent /rtp config <kind> <entry> ... no longer
            // resolves (and the menu's TreeCommand path-walk for
            // `<kind>/<entry>` reports unknown segment, the truthful
            // post-remove state). Without this the stale child remained
            // navigable after a menu-driven REMOVE.
            unregisterParentChild(kind, entry);
            // Symmetric eager mirror unregister - see the ADD branch
            // comment for rationale.
            unregisterMirrorChild(kind, entry);
            // Clear remove-mode for this kind so the next click is normal nav.
            removeModeKinds.computeIfPresent(senderId, (id, s) -> {
                s.remove(kind.toLowerCase(java.util.Locale.ROOT));
                return s.isEmpty() ? null : s;
            });
        } else {
            // ADD
            try {
                parser.addParser(entry);
            } catch (RuntimeException e) {
                RTP.log(Level.WARNING,
                        "menu multiconfig-mutate ADD failed for " + senderId
                                + " kind=" + kind + " entry=" + entry + ": " + e.getMessage(), e);
                reject(senderId, CommandMessages.menuInvalid,
                        "menu multiconfig-mutate rejected: add failure", messageMethod);
                return false;
            }
            // Region-specific nether/end seed amendment: mirror SubConfigCmd's
            // post-create amend so a newly added region for a _nether /
            // _the_end world gets the LINEAR/maxY=128/!skylight defaults
            // applied. The amender is a no-op for non-matching world names.
            // For kinds other than "regions" this branch is a no-op call.
            String lowerKind = kind.toLowerCase(java.util.Locale.ROOT);
            if (lowerKind.equals("regions")) {
                tryAmendNetherEndSeed(parser, entry);
            }
            // Mirror SubConfigCmd lines 234-237 (the `/rtp config <kind>
            // add=<entry>` path): after parser.addParser() succeeds, also
            // register a child SubConfigCmd on the parent
            // `/rtp config <kind>` TreeCommand. Without this the new entry
            // exists on disk + in the parser map but the command tree
            // doesn't know about it, so the menu's path walker
            // (dispatchOpenConfigKey) reports
            //   "menu anvil-input path segment '<entry>' did not resolve
            //    to a TreeCommand under <kind>"
            // and rejects the click as `unknown path segment`. This is
            // the same regression a /rtp config regions add=foo call
            // would suffer if SubConfigCmd skipped its addSubCommand
            // step.
            registerParentChild(kind, parser, entry);
            // Also eagerly mirror the new child onto the `/rtp menu config
            // <kind>` mirror subtree so navigation through the menu sees
            // it without relying on a lazy lookup-time merge - the lazy
            // path was a second source of truth and introduced subtle
            // ordering bugs when callers of `addSubCommand` raced with
            // the merge-on-read fallback. Now both the real tree and the
            // mirror tree are mutated eagerly and symmetrically on ADD
            // (and REMOVE below).
        }
        // Re-render the selector so the user sees the new entry list.
        return dispatchOpenMultiConfigSelector(senderId,
                new MenuAction.OpenMultiConfigSelector(kind), messageMethod);
    }

    /**
     * Best-effort post-ADD amendment for a region whose name matches the
     * vanilla nether / the-end suffix conventions. Delegates to the
     * {@link io.github.dailystruggle.rtp.common.commands.menu.multiconfig.NetherEndConfigAmender}
     * helper (extracted in step 3 of CHECKLIST-multiconfig-menu) using an
     * empty {@code parameterValues} map - we just want the LINEAR / maxY=128
     * / !requireskylight seed defaults applied, not a user-supplied override.
     * Failures are logged but not propagated; the region row still appears
     * in the selector after a failed amend.
     */
    private static void tryAmendNetherEndSeed(
            io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<?> parser,
            String entry) {
        if (entry == null) return;
        String lower = entry.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith("_nether") && !lower.endsWith("_the_end")) return;
        try {
            io.github.dailystruggle.rtp.common.configuration.ConfigParser<?> region =
                    parser.getParser(entry);
            if (region == null) return;
            io.github.dailystruggle.rtp.api.world.RTPWorld world = null;
            try {
                world = RTP.serverAccessor.getRTPWorld(entry);
            } catch (RuntimeException ignored) {
                // serverAccessor null in tests; amender tolerates null world
            }
            @SuppressWarnings({"unchecked", "rawtypes"})
            io.github.dailystruggle.rtp.common.configuration.ConfigParser raw = region;
            io.github.dailystruggle.rtp.common.commands.menu.multiconfig.NetherEndConfigAmender
                    .amend(new java.util.HashMap<>(), raw, world);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-mutate ADD post-amend failed for entry="
                            + entry + ": " + e.getMessage(), e);
        }
    }

    /**
     * Locate the parent {@code SubConfigCmd} for {@code /rtp config <kind>}
     * by walking {@code RTP.baseCommand -> "config" -> "<kind>"}. Returns
     * {@code null} when any link in the chain is missing (e.g. the base
     * command isn't wired in tests, or the platform hasn't finished
     * registering {@code ConfigCmd.addCommands()} yet). The lookup is
     * case-insensitive on the {@code kind} input: {@code TreeCommand}
     * stores keys uppercased (see {@code ConfigCmd.java:67}), so we
     * upper-case before the second {@code get}.
     */
    private static @Nullable io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand
            resolveParentConfigCmd(String kind) {
        if (kind == null || kind.isEmpty()) return null;
        if (RTP.baseCommand == null) return null;
        try {
            CommandsAPICommand configCmd =
                    RTP.baseCommand.getCommandLookup().get("CONFIG");
            if (!(configCmd instanceof io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand configTree)) {
                return null;
            }
            CommandsAPICommand kindCmd = configTree.getCommandLookup()
                    .get(kind.toUpperCase(java.util.Locale.ROOT));
            if (kindCmd instanceof io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand kindTree) {
                return kindTree;
            }
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * After a menu-driven ADD on the {@code <kind>} multi-config parser
     * succeeds, mirror the post-{@code parser.addParser(target)} steps
     * from {@code SubConfigCmd} lines 234-237: resolve the freshly added
     * {@code ConfigParser}, construct a child {@code SubConfigCmd} bound
     * to the {@code /rtp config <kind>} parent, prime its parameters,
     * and register it via {@code addSubCommand}. Failures are logged
     * WARN and swallowed; the parser-side ADD already succeeded, the
     * row will still appear in the selector, and a {@code /rtp reload}
     * would rebuild the tree from scratch.
     */
    private void registerParentChild(
            String kind,
            io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<?> parser,
            String entry) {
        if (kind == null || entry == null || parser == null) return;
        io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand parent =
                resolveParentConfigCmd(kind);
        if (parent == null) return;
        try {
            io.github.dailystruggle.rtp.common.configuration.ConfigParser<?> child = parser.getParser(entry);
            if (child == null) return;
            io.github.dailystruggle.rtp.common.commands.config.SubConfigCmd childCmd =
                    new io.github.dailystruggle.rtp.common.commands.config.SubConfigCmd(
                            parent, child.name, child);
            childCmd.addParameters();
            parent.addSubCommand(childCmd);
            // Parity with SubConfigCmd.addParameters() / ADD branch:
            // register a bare (suffix-stripped) {@link
            // SubConfigCmd.Alias} so `/rtp config <kind> <entry> ...`
            // resolves without the .yml suffix. Route through
            // `addSubCommand` (the supported registration API) rather
            // than touching the parent's `commandLookup` directly -
            // a previous version of this helper poked the map straight
            // from outside the owning class, which both bypassed the
            // framework's bookkeeping and made the registration
            // invisible to anything that walks `getCommandLookup`'s
            // structured shape (e.g. mirror snapshotters).
            String bare = child.name.replace(".yml", "").replace(".YML", "");
            if (!bare.equalsIgnoreCase(child.name)) {
                parent.addSubCommand(
                        new io.github.dailystruggle.rtp.common.commands.config.SubConfigCmd.Alias(
                                parent, bare, childCmd));
            }
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-mutate ADD tree-sync failed for kind="
                            + kind + " entry=" + entry + ": " + e.getMessage(), e);
        }
        // Eagerly mirror the freshly registered child(ren) onto the
        // `/rtp menu config <kind>` shadow subtree so menu navigation
        // resolves the new entry without any lazy merge-on-read in
        // `MenuMirrorSubcommand.getCommandLookup()`. Failures here are
        // logged WARN and swallowed for the same reason as the real-tree
        // branch above: a `/rtp reload` would rebuild both trees.
        registerMirrorChild(kind, entry);
    }

    /**
     * Walk {@code RTP.baseCommand -> "menu" -> "config" -> "<kind>"} and
     * return the {@link MenuMirrorSubcommand} parent for the multi-config
     * subtree, or {@code null} when any link is missing. The mirror tree
     * is seeded asynchronously by {@link #seedMirrorTree()} after a 10-
     * tick delay, so this returns {@code null} when called before the
     * seed pulse has run (e.g. in a unit test that doesn't tick the
     * scheduler) - the caller treats that as a soft failure and only
     * logs WARN, matching the real-tree counterpart.
     */
    private @Nullable MenuMirrorSubcommand resolveMenuMirrorParent(String kind) {
        if (kind == null || kind.isEmpty()) return null;
        try {
            CommandsAPICommand configMirror =
                    getCommandLookup().get("CONFIG");
            if (!(configMirror instanceof MenuMirrorSubcommand configMirrorTree)) {
                return null;
            }
            CommandsAPICommand kindMirror = configMirrorTree.getCommandLookup()
                    .get(kind.toUpperCase(java.util.Locale.ROOT));
            if (kindMirror instanceof MenuMirrorSubcommand kindMirrorTree) {
                return kindMirrorTree;
            }
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Mint and {@link TreeCommand#addSubCommand} a fresh
     * {@link MenuMirrorSubcommand} under the {@code /rtp menu config
     * <kind>} mirror parent that targets the newly registered child
     * {@link io.github.dailystruggle.rtp.common.commands.config.SubConfigCmd}
     * on the real {@code /rtp config <kind>} tree (and its bare-name
     * {@code Alias}, if distinct). Required so that the player command
     * {@code /rtp menu config <kind> <entry> ...} walks through the mirror
     * into a leaf that triggers {@link #renderForPath} with the matching
     * path - without it the mirror would only learn about the new entry
     * via a lazy lookup-time merge, which we deliberately removed as
     * part of dropping the second source of truth.
     */
    private void registerMirrorChild(String kind, String entry) {
        if (kind == null || entry == null) return;
        MenuMirrorSubcommand mirrorParent = resolveMenuMirrorParent(kind);
        if (mirrorParent == null) return;
        io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand realParent =
                resolveParentConfigCmd(kind);
        if (realParent == null) return;
        try {
            Map<String, CommandsAPICommand> realChildren = realParent.getCommandLookup();
            if (realChildren == null) return;
            Map<String, CommandsAPICommand> mirrorChildren = mirrorParent.getCommandLookup();
            String bare = entry.replace(".yml", "").replace(".YML", "");
            String[] candidateKeys = bare.equalsIgnoreCase(entry)
                    ? new String[]{entry, entry + ".yml"}
                    : new String[]{bare, bare + ".yml"};
            for (String candidate : candidateKeys) {
                String key = candidate.toUpperCase(java.util.Locale.ROOT);
                if (mirrorChildren.containsKey(key)) continue;
                CommandsAPICommand realChild = realChildren.get(key);
                if (!(realChild instanceof io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand realChildTree)) {
                    continue;
                }
                List<String> childPath = List.of("config", kind, candidate);
                mirrorParent.addSubCommand(
                        new MenuMirrorSubcommand(this, mirrorParent, realChildTree, childPath));
            }
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-mutate ADD mirror-sync failed for kind="
                            + kind + " entry=" + entry + ": " + e.getMessage(), e);
        }
    }

    /**
     * Symmetric counterpart to {@link #registerMirrorChild}: drop the
     * mirror children for {@code <entry>} (both bare and {@code .yml}-
     * suffixed forms, uppercased) from the {@code /rtp menu config
     * <kind>} mirror parent so post-REMOVE menu walks miss truthfully.
     */
    private void unregisterMirrorChild(String kind, String entry) {
        if (kind == null || entry == null) return;
        MenuMirrorSubcommand mirrorParent = resolveMenuMirrorParent(kind);
        if (mirrorParent == null) return;
        try {
            Map<String, CommandsAPICommand> lookup = mirrorParent.getCommandLookup();
            String bare = entry.replace(".yml", "").replace(".YML", "");
            lookup.remove(bare);
            lookup.remove(bare.toUpperCase(java.util.Locale.ROOT));
            lookup.remove(bare + ".yml");
            lookup.remove((bare + ".yml").toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-mutate REMOVE mirror-sync failed for kind="
                            + kind + " entry=" + entry + ": " + e.getMessage(), e);
        }
    }

    /**
     * Symmetric counterpart to {@link #registerParentChild}: after a
     * menu-driven REMOVE on the {@code <kind>} multi-config parser
     * succeeds, drop the per-entry child from the parent's
     * {@code commandLookup} so subsequent path-walk lookups for
     * {@code /rtp config <kind> <entry> ...} miss (matching the
     * post-remove disk state). Mirrors {@code SubConfigCmd} line 226
     * ({@code commandLookup.remove(target)}). {@code TreeCommand}
     * stores keys uppercased, so probe both the raw entry name and
     * its {@code .yml}-suffixed form (uppercased) - {@code SubConfigCmd}
     * registers under {@code configParser.name} which carries the
     * {@code .yml} suffix.
     */
    private void unregisterParentChild(String kind, String entry) {
        if (kind == null || entry == null) return;
        io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand parent =
                resolveParentConfigCmd(kind);
        if (parent == null) return;
        try {
            Map<String, CommandsAPICommand> lookup = parent.getCommandLookup();
            String bare = entry.replace(".yml", "").replace(".YML", "");
            lookup.remove(bare);
            lookup.remove(bare.toUpperCase(java.util.Locale.ROOT));
            lookup.remove(bare + ".yml");
            lookup.remove((bare + ".yml").toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-mutate REMOVE tree-sync failed for kind="
                            + kind + " entry=" + entry + ": " + e.getMessage(), e);
        }
    }

    private boolean dispatchRun(UUID senderId, MenuAction.RunRtpCommand run,
                                @Nullable Consumer<String> messageMethod) {
        String[] args = run.args();
        Consumer<String> sink = messageMethod != null
                ? messageMethod
                : msg -> RTP.serverAccessor.sendMessage(RTPAPI.serverId, senderId, msg, null);
        Predicate<String> permissionProbe = permissionProbeFactory.apply(senderId);
        if (permissionProbe == null) permissionProbe = perm -> true;
        try {
            rtpRoot.onCommand(senderId,
                    permissionProbe,
                    sink,
                    args,
                    0,
                    null);
            return true;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu redeem dispatch failed for " + senderId
                            + " args=" + java.util.Arrays.toString(args) + ": " + e.getMessage(), e);
            reject(senderId, CommandMessages.menuInvalid,
                    "menu redeem rejected: dispatch failure",
                    messageMethod);
            return false;
        }
    }

    /**
     * Extracts the zero-based page index from the parsed {@code page} parameter.
     * Wire format is 1-indexed ({@code page:1} = first page); we subtract one
     * before returning. Missing, empty, non-numeric, or {@code < 1} values
     * collapse to {@code 0} (first page) so a malformed click degrades
     * gracefully — the parameter-level predicate already rejects clearly
     * invalid inputs at the commands-api parser, this is a defensive backstop.
     */
    static int extractPageIndex(Map<String, List<String>> parameterValues) {
        if (parameterValues == null) return 0;
        List<String> vs = parameterValues.get(PARAM_PAGE);
        if (vs == null || vs.isEmpty() || vs.get(0) == null) return 0;
        try {
            int oneBased = Integer.parseInt(vs.get(0));
            return oneBased < 1 ? 0 : oneBased - 1;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }


    /**
     * ADR-050 Stage 1b: package-private wrapper around the private
     * {@link #reject} so concrete-command leaves
     * (see {@link MenuConcreteCommandLeavesB}) can reject malformed inputs
     * (missing required parameters) before constructing a {@code MenuAction}
     * record whose canonical constructor would throw
     * {@code IllegalArgumentException} on empty strings.
     */
    void rejectMenuInvalid(@Nullable UUID senderId,
                           String logMessage,
                           @Nullable Consumer<String> messageMethod) {
        reject(senderId, CommandMessages.menuInvalid, logMessage, messageMethod);
    }

    private void reject(@Nullable UUID senderId,
                        Enum<?> key,
                        String logMessage,
                        @Nullable Consumer<String> messageMethod) {
        // S-004: never silently swallow. The log is unconditional; the user
        // message goes through the standard route.
        RTP.log(Level.WARNING, logMessage);
        if (senderId == null) return;
        String userMsg = msg(key, defaultFor(key));
        if (messageMethod != null) {
            messageMethod.accept(userMsg);
        } else {
            RTP.serverAccessor.sendMessage(RTPAPI.serverId, senderId, userMsg, null);
        }
    }

    private static String defaultFor(Enum<?> key) {
        if (key == CommandMessages.menuInvalid) return "Invalid menu command.";
        if (key == CommandMessages.menuUnknownPlayer) return "Menus may only be used by online players.";
        return key.name();
    }
}
