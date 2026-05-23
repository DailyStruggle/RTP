package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuConsumerProfile;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuOpenRequest;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.api.menu.MenuTokenRegistry;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.maps.ChartSpecTokens;
import io.github.dailystruggle.rtp.common.commands.maps.MapDispatch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 *   <li>Atomic-consume via {@link MenuTokenRegistry#consume(UUID, String)} —
 *       this is the {@code senderUuid == storedPlayerUuid} check (the registry
 *       only returns a value when the caller owns the token).</li>
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

    /** Parameter name used when the user types {@code /rtp menu token:<value>}. */
    public static final String PARAM_TOKEN = "token";

    /**
     * Parameter name used when the user (or a renderer's pagination click)
     * types {@code /rtp menu page:<n>} (CHECKLIST item 5.3.b, D-005 approved
     * 2026-05-15). Values are 1-indexed on the wire and translated to a
     * zero-based {@link MenuOpenRequest#pageIndex()} before reaching the page
     * builder. Default (parameter absent or unparseable) is page 1 / index 0.
     */
    public static final String PARAM_PAGE = "page";

    private final MenuTokenRegistry tokenRegistry;
    private final TreeCommand rtpRoot;
    private final java.util.function.Function<UUID, Predicate<String>> permissionProbeFactory;
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

    /**
     * In-memory record of one player's pending config edits for a single
     * file. Mutable but always replaced wholesale via the cart map; never
     * leaked. See {@link #carts}.
     */
    private static final class Cart {
        final String fileName;
        final LinkedHashMap<String, String> entries = new LinkedHashMap<>();

        Cart(String fileName) {
            this.fileName = fileName;
        }
    }

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
     *
     * @return {@code true} if the anvil GUI was opened, {@code false} if the
     *         platform refused (player offline, no inventory subsystem, etc).
     *         The caller treats {@code false} as an S-004 reject path.
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
        /** Build the config-file selector page (v3.7 §3.1). */
        MenuModel buildSelector(UUID viewer);

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
     * @param parent        the {@code /rtp} root command (also used as the
     *                      dispatch target for {@link MenuAction.RunRtpCommand}).
     * @param tokenRegistry the registry that minted the inbound token.
     */
    public MenuRedeemSubcommand(TreeCommand parent, MenuTokenRegistry tokenRegistry) {
        this(parent, tokenRegistry, uuid -> perm -> true, null, null, null);
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
    public MenuRedeemSubcommand(TreeCommand parent, MenuTokenRegistry tokenRegistry,
                                java.util.function.Function<UUID, Predicate<String>> permissionProbeFactory) {
        this(parent, tokenRegistry, permissionProbeFactory, null, null, null);
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
    public MenuRedeemSubcommand(TreeCommand parent, MenuTokenRegistry tokenRegistry,
                                java.util.function.Function<UUID, Predicate<String>> permissionProbeFactory,
                                @Nullable MenuRenderer renderer,
                                @Nullable MenuPageBuilder pageBuilder) {
        this(parent, tokenRegistry, permissionProbeFactory, renderer, pageBuilder, null);
    }

    /**
     * Stage A.2 constructor: extends the renderer + page-builder wire-up with
     * an optional {@link MenuParamPickerBuilder} for the parameter-value
     * picker sub-page. Pass {@code null} for {@code paramPickerBuilder} to
     * keep the pre-Stage-A.2 behaviour (inbound {@link MenuAction.OpenParamPicker}
     * tokens reject with {@code menuInvalid} + WARN).
     */
    public MenuRedeemSubcommand(TreeCommand parent, MenuTokenRegistry tokenRegistry,
                                java.util.function.Function<UUID, Predicate<String>> permissionProbeFactory,
                                @Nullable MenuRenderer renderer,
                                @Nullable MenuPageBuilder pageBuilder,
                                @Nullable MenuParamPickerBuilder paramPickerBuilder) {
        this(parent, tokenRegistry, permissionProbeFactory,
                renderer, pageBuilder, paramPickerBuilder, null);
    }

    /**
     * ADR-045 constructor: adds an optional {@link AnvilInputOpener} for the
     * "type a custom value..." picker row. Pass {@code null} to keep the
     * pre-ADR-045 behaviour (inbound {@link MenuAction.PromptAnvilInput}
     * tokens reject with {@code menuInvalid} + WARN).
     */
    public MenuRedeemSubcommand(TreeCommand parent, MenuTokenRegistry tokenRegistry,
                                java.util.function.Function<UUID, Predicate<String>> permissionProbeFactory,
                                @Nullable MenuRenderer renderer,
                                @Nullable MenuPageBuilder pageBuilder,
                                @Nullable MenuParamPickerBuilder paramPickerBuilder,
                                @Nullable AnvilInputOpener anvilInputOpener) {
        this(parent, tokenRegistry, permissionProbeFactory,
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
    public MenuRedeemSubcommand(TreeCommand parent, MenuTokenRegistry tokenRegistry,
                                java.util.function.Function<UUID, Predicate<String>> permissionProbeFactory,
                                @Nullable MenuRenderer renderer,
                                @Nullable MenuPageBuilder pageBuilder,
                                @Nullable MenuParamPickerBuilder paramPickerBuilder,
                                @Nullable AnvilInputOpener anvilInputOpener,
                                @Nullable MenuConfigSubtreeBuilder configSubtreeBuilder) {
        this(parent, tokenRegistry, permissionProbeFactory,
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
    public MenuRedeemSubcommand(TreeCommand parent, MenuTokenRegistry tokenRegistry,
                                java.util.function.Function<UUID, Predicate<String>> permissionProbeFactory,
                                @Nullable MenuRenderer renderer,
                                @Nullable MenuPageBuilder pageBuilder,
                                @Nullable MenuParamPickerBuilder paramPickerBuilder,
                                @Nullable AnvilInputOpener anvilInputOpener,
                                @Nullable MenuConfigSubtreeBuilder configSubtreeBuilder,
                                @Nullable MenuCuratedPageBuilder curatedPageBuilder) {
        this(parent, tokenRegistry, permissionProbeFactory,
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
    public MenuRedeemSubcommand(TreeCommand parent, MenuTokenRegistry tokenRegistry,
                                java.util.function.Function<UUID, Predicate<String>> permissionProbeFactory,
                                @Nullable MenuRenderer renderer,
                                @Nullable MenuPageBuilder pageBuilder,
                                @Nullable MenuParamPickerBuilder paramPickerBuilder,
                                @Nullable AnvilInputOpener anvilInputOpener,
                                @Nullable MenuConfigSubtreeBuilder configSubtreeBuilder,
                                @Nullable MenuCuratedPageBuilder curatedPageBuilder,
                                @Nullable MenuConfigSearchBuilder configSearchBuilder) {
        this(parent, tokenRegistry, permissionProbeFactory,
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
    public MenuRedeemSubcommand(TreeCommand parent, MenuTokenRegistry tokenRegistry,
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
        this.tokenRegistry = Objects.requireNonNull(tokenRegistry, "tokenRegistry");
        this.permissionProbeFactory =
                Objects.requireNonNull(permissionProbeFactory, "permissionProbeFactory");
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
        // Register a hidden curated parameter so commands-api recognises
        //   /rtp menu token:<v>    → subcommand "menu" + param "token=<v>"
        // This is the only valid form on the `/rtp` root: `menu:<token>` at
        // root level would be parsed as parameter "menu" (which doesn't exist)
        // and yield msgBadParameter. BookMenuRenderer emits the form above.
        getParameterLookup().put(PARAM_TOKEN, new CommandParameter(PERMISSION,
                "menu redeem token (opaque)", (uuid, value) -> true) {
            @Override
            public java.util.Set<String> values() {
                return java.util.Collections.emptySet();
            }
        });
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

    private boolean dispatch(UUID senderId,
                             Map<String, List<String>> parameterValues,
                             @Nullable CommandsAPICommand nextCommand,
                             @Nullable Consumer<String> messageMethod) {
        if (senderId == null) {
            reject(null, MessagesKeys.menuUnknownPlayer,
                    "menu redeem rejected: no sender UUID", messageMethod);
            return false;
        }
        String token = extractToken(parameterValues);
        if (token == null || token.isEmpty()) {
            // No token → open-page path (CHECKLIST item 3.2 / 4.2.a). If a
            // renderer + builder were wired, reflect the targeted node and
            // hand the resulting MenuModel to the renderer. Otherwise the
            // existing menuInvalid + WARN fallback applies (backward
            // compatible with the pre-Stage-4 wire-up).
            if (renderer != null && pageBuilder != null) {
                // If the parser is about to descend into a mirror child
                // (e.g. `/rtp menu config regions default.yml size:500`),
                // returning `true` here lets the commands-api walker continue
                // through the mirror tree (TreeCommand.java:267-273). The
                // leaf mirror's onCommand will fire renderAt() with the full
                // assembled path including any staged `name:value` segments;
                // rendering here at depth 1 would only briefly flash a less-
                // specific menu page that the leaf render then overwrites
                // (wasteful work and a token-mint pulse on each intermediate
                // node).
                if (nextCommand instanceof MenuMirrorSubcommand) {
                    return true;
                }
                int pageIndex = extractPageIndex(parameterValues);
                return openPage(senderId, nextCommand, pageIndex, messageMethod);
            }
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu redeem rejected: missing token", messageMethod);
            return false;
        }
        Optional<MenuAction> consumed;
        try {
            consumed = tokenRegistry.consume(senderId, token);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu redeem failed for " + senderId + ": " + e.getMessage(), e);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu redeem rejected: registry failure", messageMethod);
            return false;
        }
        if (consumed.isEmpty()) {
            // Unknown / expired / wrong player — the registry collapses all three
            // into Optional.empty() per its contract. We surface the "expired" key
            // when the player still has outstanding tokens (more likely TTL),
            // otherwise "invalid" (more likely typo / replay / wrong player).
            MessagesKeys key = tokenRegistry.outstandingFor(senderId) > 0
                    ? MessagesKeys.menuExpired
                    : MessagesKeys.menuInvalid;
            reject(senderId, key,
                    "menu redeem rejected: token " + token + " not consumable",
                    messageMethod);
            return false;
        }
        MenuAction action = consumed.get();
        // RunRtpCommand → execute the assembled /rtp command tail.
        if (action instanceof MenuAction.RunRtpCommand run) {
            return dispatchRun(senderId, run, messageMethod);
        }
        // OpenMenu → server-side navigation (back / forward-descend). Resolves
        // the path against the live TreeCommand graph; never re-enters the
        // commands-api parser, which is what makes /rtp menu config /
        // /rtp menu config performance / ← back possible despite `config` not
        // being a parameter of the `menu` subcommand.
        if (action instanceof MenuAction.OpenMenu open) {
            return dispatchOpen(senderId, open, messageMethod);
        }
        // OpenParamPicker → server-side parameter-value picker page (Stage A.2).
        // Resolves the parent path against the live TreeCommand graph (same
        // pattern as OpenMenu) and hands off to paramPickerBuilder.
        if (action instanceof MenuAction.OpenParamPicker picker) {
            return dispatchOpenParamPicker(senderId, picker, messageMethod);
        }
        // PromptAnvilInput → open an anvil GUI on the clicking player and
        // let them type a free-form value (ADR-045). Hand off to the
        // platform-side AnvilInputOpener when wired; otherwise reject as a
        // protocol error (renderer should have fallen back to SuggestInput
        // before emitting this action on an unsupported platform).
        if (action instanceof MenuAction.PromptAnvilInput prompt) {
            return dispatchPromptAnvilInput(senderId, prompt, messageMethod);
        }
        // PROPOSAL-config-view-as-book.md v3.7 — curated config-subtree
        // pages. All three arms gate on rtp.config.view (S-004 reject path on
        // miss) and route through configSubtreeBuilder, which is responsible
        // for reading *live* ConfigParser state on each invocation (v3.6.3
        // post-write rebuild contract).
        if (action instanceof MenuAction.OpenConfigSelector) {
            return dispatchOpenConfigSelector(senderId, messageMethod);
        }
        if (action instanceof MenuAction.OpenConfigFile openFile) {
            return dispatchOpenConfigFile(senderId, openFile, messageMethod);
        }
        if (action instanceof MenuAction.OpenConfigKey openKey) {
            return dispatchOpenConfigKey(senderId, openKey, messageMethod);
        }
        // PROPOSAL-rtp-menu-config-search.md — config-search prompt + results.
        // The prompt arm delegates to the existing PromptAnvilInput dispatch
        // shape (Q4 Decision A): the renderer is responsible for minting
        // PromptAnvilInput(["menu","config","search"], "query", "") on the
        // search button click, so OpenConfigSearchPrompt reaching redeem is
        // a renderer that wants the server to translate it into the anvil
        // prompt flow itself.
        if (action instanceof MenuAction.OpenConfigSearchPrompt) {
            return dispatchOpenConfigSearchPrompt(senderId, messageMethod);
        }
        if (action instanceof MenuAction.OpenConfigSearchResults search) {
            return dispatchOpenConfigSearchResults(senderId, search, messageMethod);
        }
        // PROPOSAL-admin-panel.md v2 — curated admin panel and front page.
        // OpenAdminPanel gates on rtp.menu.admin in the dispatch arm; the
        // front-page action is unconditionally callable.
        if (action instanceof MenuAction.OpenAdminPanel) {
            return dispatchOpenAdminPanel(senderId, messageMethod);
        }
        if (action instanceof MenuAction.OpenFrontPage) {
            return dispatchOpenFrontPage(senderId, messageMethod);
        }
        // PROPOSAL-info-as-book.md section 4.6 — open the /rtp info book at
        // the supplied scope, or switch back to the chat presentation. Both
        // arms gate on rtp.info (S-004 reject path on miss) and run on the
        // dispatch thread (synchronous; InfoCmd does not block).
        if (action instanceof MenuAction.OpenInfo openInfo) {
            return dispatchOpenInfo(senderId, openInfo, messageMethod);
        }
        if (action instanceof MenuAction.SwitchInfoToText switchToText) {
            return dispatchSwitchInfoToText(senderId, switchToText, messageMethod);
        }
        // Config-menu staging-cart redesign (CHECKLIST-config-staging-cart.md):
        // four cart-affecting actions minted from the curated /rtp config
        // <file> page. Each gates on rtp.config.view (same surface as the
        // curated OpenConfig* arms) inside its dispatcher and re-renders the
        // file page so the user sees the updated Pending list.
        if (action instanceof MenuAction.StageConfigValue stage) {
            return dispatchStageConfigValue(senderId, stage, messageMethod);
        }
        if (action instanceof MenuAction.UnstageConfigValue unstage) {
            return dispatchUnstageConfigValue(senderId, unstage, messageMethod);
        }
        if (action instanceof MenuAction.ApplyStagedConfig apply) {
            return dispatchApplyStagedConfig(senderId, apply, messageMethod);
        }
        if (action instanceof MenuAction.DiscardStagedConfig discard) {
            return dispatchDiscardStagedConfig(senderId, discard, messageMethod);
        }
        // CHECKLIST-multiconfig-menu step 7 - three new arms for the generic
        // MultiConfig submenu (regions, worlds, future kinds). All three
        // gate on rtp.config.view in the dispatch arm. Path Z scope: the
        // entry page is browse-only; per-key staging is deferred to a
        // later slice.
        if (action instanceof MenuAction.OpenMultiConfigSelector openSel) {
            return dispatchOpenMultiConfigSelector(senderId, openSel, messageMethod);
        }
        if (action instanceof MenuAction.OpenMultiConfigEntry openEntry) {
            return dispatchOpenMultiConfigEntry(senderId, openEntry, messageMethod);
        }
        if (action instanceof MenuAction.MultiConfigMutate mutate) {
            return dispatchMultiConfigMutate(senderId, mutate, messageMethod);
        }
        // ADR-047 / REQ-RTP-MAP-006 declarative chart bridge. OpenMap routes
        // through ChartSpecTokens (consume on the dispatch thread) and
        // MapDispatch.paint to deliver a cartography map item to the viewer.
        // Permission-gated by rtp.admin in the dispatch arm; the inert-binding
        // gate (NoopMapBinding active) is enforced inside MapDispatch.paint.
        if (action instanceof MenuAction.OpenMap openMap) {
            return dispatchOpenMap(senderId, openMap, messageMethod);
        }
        // Renderer click effects (SuggestInput / ChangePage / OpenExternalUrl)
        // must never reach redeem per ADR-035 §3. If one does, the renderer
        // is buggy — refuse and log.
        RTP.log(Level.WARNING, "menu redeem received non-dispatchable action "
                + action.getClass().getSimpleName() + " for " + senderId);
        reject(senderId, MessagesKeys.menuInvalid,
                "menu redeem rejected: action kind not dispatchable",
                messageMethod);
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
    private boolean openPage(UUID senderId,
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
    private boolean dispatchOpen(UUID senderId,
                                 MenuAction.OpenMenu open,
                                 @Nullable Consumer<String> messageMethod) {
        if (renderer == null || pageBuilder == null) {
            // OpenMenu reached redeem but no renderer/builder is wired. Treat
            // as a protocol error (renderer minted it without us having one
            // to dispatch through) and refuse rather than silently no-op.
            RTP.log(Level.WARNING,
                    "menu open-action received with open-page disabled for " + senderId);
            reject(senderId, MessagesKeys.menuInvalid,
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
            if (!(next instanceof TreeCommand tc)) {
                RTP.log(Level.WARNING,
                        "menu open-action path segment '" + segment
                                + "' did not resolve to a TreeCommand under "
                                + target.name() + " for " + senderId);
                reject(senderId, MessagesKeys.menuInvalid,
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
    private boolean dispatchOpenParamPicker(UUID senderId,
                                            MenuAction.OpenParamPicker picker,
                                            @Nullable Consumer<String> messageMethod) {
        if (renderer == null || paramPickerBuilder == null) {
            RTP.log(Level.WARNING,
                    "menu param-picker received with picker-page disabled for " + senderId);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu param-picker rejected: picker-page disabled", messageMethod);
            return false;
        }
        String[] parentPath = picker.parentPath();
        TreeCommand target = rtpRoot;
        for (String segment : parentPath) {
            // Stage A.3: skip staged `name=value` parameter assignments —
            // they ride along in the assembled path without advancing the
            // command-node walk (see dispatchOpen).
            if (segment != null && segment.indexOf('=') >= 0) {
                continue;
            }
            CommandsAPICommand next = target.getCommandLookup()
                    .get(segment.toUpperCase(java.util.Locale.ROOT));
            if (!(next instanceof TreeCommand tc)) {
                RTP.log(Level.WARNING,
                        "menu param-picker path segment '" + segment
                                + "' did not resolve to a TreeCommand under "
                                + target.name() + " for " + senderId);
                reject(senderId, MessagesKeys.menuInvalid,
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
            reject(senderId, MessagesKeys.menuInvalid,
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
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu param-picker rejected: builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu param-picker rejected: builder returned null model",
                    messageMethod);
            return false;
        }
        try {
            renderer.render(senderId, model);
            return true;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu param-picker render failed for " + senderId
                            + " node=" + target.name() + " param=" + paramName
                            + ": " + e.getMessage(), e);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu param-picker rejected: renderer failure", messageMethod);
            return false;
        }
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
    private boolean dispatchPromptAnvilInput(UUID senderId,
                                             MenuAction.PromptAnvilInput prompt,
                                             @Nullable Consumer<String> messageMethod) {
        if (anvilInputOpener == null) {
            RTP.log(Level.WARNING,
                    "menu anvil-input received with anvil-input disabled for " + senderId);
            reject(senderId, MessagesKeys.menuInvalid,
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
            if (!(next instanceof TreeCommand tc)) {
                RTP.log(Level.WARNING,
                        "menu anvil-input path segment '" + segment
                                + "' did not resolve to a TreeCommand under "
                                + target.name() + " for " + senderId);
                reject(senderId, MessagesKeys.menuInvalid,
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
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu anvil-input rejected: opener failure", messageMethod);
            return false;
        }
        if (!opened) {
            RTP.log(Level.WARNING,
                    "menu anvil-input opener refused for " + senderId
                            + " node=" + target.name() + " param=" + paramName);
            reject(senderId, MessagesKeys.menuInvalid,
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
    private boolean hasConfigViewPermission(UUID senderId) {
        Predicate<String> probe = permissionProbeFactory.apply(senderId);
        if (probe == null) return false;
        try {
            return probe.test(CONFIG_VIEW_PERMISSION);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu config-view permission probe threw for " + senderId
                            + ": " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Dispatch for {@link MenuAction.OpenConfigSelector} (v3.7 §3.1). Gates
     * on {@link #CONFIG_VIEW_PERMISSION}, then routes through
     * {@link #configSubtreeBuilder}'s {@code buildSelector}. All failure paths
     * log WARN and reject with {@code menuInvalid} (S-004).
     */
    private boolean dispatchOpenConfigSelector(UUID senderId,
                                               @Nullable Consumer<String> messageMethod) {
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
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu config-selector rejected: config-subtree disabled", messageMethod);
            return false;
        }
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu config-selector denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu config-selector rejected: permission denied", messageMethod);
            return false;
        }
        MenuModel model;
        try {
            model = configSubtreeBuilder.buildSelector(senderId);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu config-selector builder failed for " + senderId
                            + ": " + e.getMessage(), e);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu config-selector rejected: builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu config-selector rejected: builder returned null model",
                    messageMethod);
            return false;
        }
        try {
            renderer.render(senderId, model);
            return true;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu config-selector render failed for " + senderId
                            + ": " + e.getMessage(), e);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu config-selector rejected: renderer failure", messageMethod);
            return false;
        }
    }

    /**
     * Dispatch for {@link MenuAction.OpenConfigFile} (v3.7 §3.2). Gates on
     * {@link #CONFIG_VIEW_PERMISSION}, then routes through
     * {@link #configSubtreeBuilder}'s {@code buildFile}. A {@code null}
     * return from the builder (unknown file name) is an S-004 reject path.
     */
    private boolean dispatchOpenConfigFile(UUID senderId,
                                           MenuAction.OpenConfigFile open,
                                           @Nullable Consumer<String> messageMethod) {
        if (renderer == null || configSubtreeBuilder == null) {
            RTP.log(Level.WARNING,
                    "menu config-file received with config-subtree disabled for " + senderId);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu config-file rejected: config-subtree disabled", messageMethod);
            return false;
        }
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu config-file denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu config-file rejected: permission denied", messageMethod);
            return false;
        }
        String fileName = open.fileName();
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
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu config-file rejected: builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            RTP.log(Level.WARNING,
                    "menu config-file unknown file '" + fileName
                            + "' for " + senderId);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu config-file rejected: unknown file '" + fileName + "'",
                    messageMethod);
            return false;
        }
        try {
            renderer.render(senderId, model);
            return true;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu config-file render failed for " + senderId
                            + " file=" + fileName + ": " + e.getMessage(), e);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu config-file rejected: renderer failure", messageMethod);
            return false;
        }
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
        if (data == null || data.isEmpty()) return "";
        for (java.util.Map.Entry<?, Object> e : data.entrySet()) {
            Enum<?> k = (Enum<?>) e.getKey();
            if (k == null) continue;
            if (k.name().equalsIgnoreCase(paramName)) {
                Object v = e.getValue();
                return v == null ? "" : String.valueOf(v);
            }
        }
        return "";
    }

    private boolean dispatchOpenConfigKey(UUID senderId,
                                          MenuAction.OpenConfigKey open,
                                          @Nullable Consumer<String> messageMethod) {
        // [diag-staging-cart] Trace OpenConfigKey dispatch so the operator
        // log shows whether the click reached the anvil-prompt entry point.
        RTP.log(Level.INFO,
                "[diag-staging-cart] dispatchOpenConfigKey entry viewer=" + senderId
                        + " file=" + open.fileName()
                        + " key=" + open.paramName()
                        + " anvilOpener=" + (anvilInputOpener != null ? "set" : "null"));
        if (anvilInputOpener == null) {
            RTP.log(Level.WARNING,
                    "menu config-key received with anvil-input disabled for " + senderId);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu config-key rejected: anvil-input disabled", messageMethod);
            return false;
        }
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu config-key denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu config-key rejected: permission denied", messageMethod);
            return false;
        }
        String fileName = open.fileName();
        String paramName = open.paramName();

        // Resolve the live `config` subtree and the per-file SubConfigCmd.
        // Both forms (`<file>.yml` and bare `<file>`) are accepted so the
        // dispatcher does not reject on baseline registrations that omit
        // the `.yml` suffix - mirrors the production buildKey path walk.
        CommandsAPICommand configCmd = rtpRoot.getCommandLookup()
                .get("CONFIG");
        if (!(configCmd instanceof TreeCommand configTree)) {
            RTP.log(Level.WARNING,
                    "menu config-key cannot resolve 'config' subcommand for " + senderId);
            reject(senderId, MessagesKeys.menuInvalid,
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
            reject(senderId, MessagesKeys.menuInvalid,
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
        MenuAction.PromptAnvilInput prompt = new MenuAction.PromptAnvilInput(
                new String[]{"config", subSegment},
                paramName,
                prefill,
                MenuAction.Mode.STAGE);
        return dispatchPromptAnvilInput(senderId, prompt, messageMethod);
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
    private boolean dispatchOpenConfigSearchPrompt(UUID senderId,
                                                   @Nullable Consumer<String> messageMethod) {
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu config-search prompt denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, MessagesKeys.menuInvalid,
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
    private boolean dispatchOpenConfigSearchResults(UUID senderId,
                                                    MenuAction.OpenConfigSearchResults search,
                                                    @Nullable Consumer<String> messageMethod) {
        if (renderer == null || configSearchBuilder == null) {
            RTP.log(Level.WARNING,
                    "menu config-search received with config-search builder disabled for " + senderId);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu config-search rejected: config-search builder disabled",
                    messageMethod);
            return false;
        }
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu config-search denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, MessagesKeys.menuInvalid,
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
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu config-search rejected: builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            RTP.log(Level.WARNING,
                    "menu config-search unresolved (query=" + query
                            + ", page=" + page + ") for " + senderId);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu config-search rejected: unresolved (query=" + query
                            + ", page=" + page + ")", messageMethod);
            return false;
        }
        try {
            renderer.render(senderId, model);
            return true;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu config-search render failed for " + senderId
                            + " query=" + query + " page=" + page
                            + ": " + e.getMessage(), e);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu config-search rejected: renderer failure", messageMethod);
            return false;
        }
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
    private boolean hasAdminMenuPermission(UUID senderId) {
        Predicate<String> probe = permissionProbeFactory.apply(senderId);
        if (probe == null) return false;
        try {
            return probe.test(ADMIN_MENU_PERMISSION);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu admin-panel permission probe threw for " + senderId
                            + ": " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Dispatch for {@link MenuAction.OpenAdminPanel}
     * (PROPOSAL-admin-panel.md v2). Gates on {@link #ADMIN_MENU_PERMISSION},
     * then routes through {@link #curatedPageBuilder}'s
     * {@code buildAdminPanel}. All failure paths log WARN and reject with
     * {@code menuInvalid} (S-004).
     */
    private boolean dispatchOpenAdminPanel(UUID senderId,
                                           @Nullable Consumer<String> messageMethod) {
        if (renderer == null || curatedPageBuilder == null) {
            RTP.log(Level.WARNING,
                    "menu admin-panel received with curated-page builder disabled for " + senderId);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu admin-panel rejected: curated-page builder disabled",
                    messageMethod);
            return false;
        }
        if (!hasAdminMenuPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu admin-panel denied: " + senderId
                            + " lacks " + ADMIN_MENU_PERMISSION);
            reject(senderId, MessagesKeys.menuInvalid,
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
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu admin-panel rejected: builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu admin-panel rejected: builder returned null model",
                    messageMethod);
            return false;
        }
        try {
            renderer.render(senderId, model);
            return true;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu admin-panel render failed for " + senderId
                            + ": " + e.getMessage(), e);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu admin-panel rejected: renderer failure", messageMethod);
            return false;
        }
    }

    /**
     * Dispatch for {@link MenuAction.OpenFrontPage}
     * (PROPOSAL-admin-panel.md v2). No permission gate: the curated front page
     * is the default landing for any menu viewer. All failure paths log WARN
     * and reject with {@code menuInvalid} (S-004).
     */
    private boolean dispatchOpenFrontPage(UUID senderId,
                                          @Nullable Consumer<String> messageMethod) {
        if (renderer == null || curatedPageBuilder == null) {
            RTP.log(Level.WARNING,
                    "menu front-page received with curated-page builder disabled for " + senderId);
            reject(senderId, MessagesKeys.menuInvalid,
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
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu front-page rejected: builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu front-page rejected: builder returned null model",
                    messageMethod);
            return false;
        }
        try {
            renderer.render(senderId, model);
            return true;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu front-page render failed for " + senderId
                            + ": " + e.getMessage(), e);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu front-page rejected: renderer failure", messageMethod);
            return false;
        }
    }

    /**
     * Probes the {@code rtp.info} permission for {@code senderId}. Returns
     * {@code false} on a {@link RuntimeException} from the probe (S-007:
     * an exception is treated as a denial).
     */
    private boolean hasInfoPermission(UUID senderId) {
        Predicate<String> probe = permissionProbeFactory.apply(senderId);
        if (probe == null) return false;
        try {
            return probe.test("rtp.info");
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu info permission probe threw for " + senderId
                            + ": " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Dispatch for {@link MenuAction.OpenInfo}
     * (PROPOSAL-info-as-book.md section 4.6). Gates on {@code rtp.info},
     * then routes through {@link #infoBookBuilder}'s
     * {@link MenuInfoBookBuilder#build} and hands the resulting
     * {@link MenuModel} to the renderer. All failure paths log WARN and
     * reject with {@code menuInvalid} (S-004).
     */
    private boolean dispatchOpenInfo(UUID senderId,
                                     MenuAction.OpenInfo action,
                                     @Nullable Consumer<String> messageMethod) {
        if (renderer == null || infoBookBuilder == null) {
            RTP.log(Level.WARNING,
                    "menu info-book received with info-book builder disabled for " + senderId);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu info-book rejected: info-book builder disabled",
                    messageMethod);
            return false;
        }
        if (!hasInfoPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu info-book denied: " + senderId + " lacks rtp.info");
            reject(senderId, MessagesKeys.menuInvalid,
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
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu info-book rejected: builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu info-book rejected: builder returned null model",
                    messageMethod);
            return false;
        }
        try {
            renderer.render(senderId, model);
            return true;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu info-book render failed for " + senderId
                            + ": " + e.getMessage(), e);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu info-book rejected: renderer failure", messageMethod);
            return false;
        }
    }

    /**
     * Dispatch for {@link MenuAction.SwitchInfoToText}
     * (PROPOSAL-info-as-book.md section 4.6). Gates on {@code rtp.info}, then
     * re-enters the chat path by synthesising the equivalent {@code /rtp info}
     * {@link MenuAction.RunRtpCommand}. No book is rendered; the player sees
     * the standard chat output. Failures log WARN + reject (S-004).
     */
    private boolean dispatchSwitchInfoToText(UUID senderId,
                                             MenuAction.SwitchInfoToText action,
                                             @Nullable Consumer<String> messageMethod) {
        if (!hasInfoPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu info-switch-to-text denied: " + senderId + " lacks rtp.info");
            reject(senderId, MessagesKeys.menuInvalid,
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
            case WORLD  -> args = new String[]{"info", "world:" + action.scope().name()};
            case REGION -> args = new String[]{"info", "region:" + action.scope().name()};
            default     -> {
                RTP.log(Level.WARNING,
                        "menu info-switch-to-text: unknown scope kind "
                                + action.scope().kind() + " for " + senderId);
                reject(senderId, MessagesKeys.menuInvalid,
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
     *   <li>Consumes the {@link ChartSpec} from the process-local
     *       {@link ChartSpecTokens#instance() ChartSpecTokens singleton}.
     *       Unknown / expired / mismatched-player tokens collapse to
     *       {@code menuInvalid} (S-004), matching the stale-menu-redeem
     *       behaviour.</li>
     *   <li>Hands off to {@link MapDispatch#paint(ChartSpec, UUID)}, which
     *       owns the {@link MessagesKeys#mapBindingMissing} /
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
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu open-map rejected: permission denied",
                    messageMethod);
            return false;
        }
        Optional<ChartSpec> resolved =
                ChartSpecTokens.instance().consume(senderId, action.chartSpecToken());
        if (resolved.isEmpty()) {
            RTP.log(Level.WARNING,
                    "menu open-map rejected: unknown / expired / mismatched ChartSpec token "
                            + action.chartSpecToken() + " for " + senderId);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu open-map rejected: unknown ChartSpec token",
                    messageMethod);
            return false;
        }
        try {
            // MapDispatch.paint owns the configurable viewer-facing failure
            // surfaces (mapBindingMissing / mapResolverMissing /
            // mapUnavailable / mapBusy) and returns false after notifying the
            // viewer through MessagesKeys. We do not reject() on its false
            // return: that would double-message the player.
            return MapDispatch.paint(resolved.get(), senderId);
        } catch (RuntimeException e) {
            // Defensive S-004: MapDispatch.paint is supposed to catch its
            // own RuntimeExceptions, but if a downstream binding leaks one
            // we still surface it through the configurable channel.
            RTP.log(Level.WARNING,
                    "menu open-map MapDispatch.paint threw for " + senderId
                            + ": " + e.getMessage(), e);
            reject(senderId, MessagesKeys.menuInvalid,
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
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu open rejected: page builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu open rejected: builder returned null model", messageMethod);
            return false;
        }
        try {
            renderer.render(senderId, model);
            return true;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu render failed for " + senderId
                            + " node=" + target.name() + ": " + e.getMessage(), e);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu open rejected: renderer failure", messageMethod);
            return false;
        }
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
    private boolean dispatchStageConfigValue(UUID senderId,
                                             MenuAction.StageConfigValue stage,
                                             @Nullable Consumer<String> messageMethod) {
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu config-stage denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, MessagesKeys.menuInvalid,
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
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu config-stage rejected: cart failure", messageMethod);
            return false;
        }
        // Re-render the curated file page so the player sees the updated cart.
        return dispatchOpenConfigFile(senderId,
                new MenuAction.OpenConfigFile(stage.fileName()), messageMethod);
    }

    /**
     * Dispatch for {@link MenuAction.UnstageConfigValue}: remove the named
     * entry from the player's cart and re-render the curated
     * {@code /rtp config <file>} page. Gates on
     * {@link #CONFIG_VIEW_PERMISSION}. No-op when the cart is empty, scoped
     * to a different file, or does not contain {@code paramName}.
     */
    private boolean dispatchUnstageConfigValue(UUID senderId,
                                               MenuAction.UnstageConfigValue unstage,
                                               @Nullable Consumer<String> messageMethod) {
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu config-unstage denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, MessagesKeys.menuInvalid,
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
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu config-unstage rejected: cart failure", messageMethod);
            return false;
        }
        return dispatchOpenConfigFile(senderId,
                new MenuAction.OpenConfigFile(unstage.fileName()), messageMethod);
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
    private boolean dispatchApplyStagedConfig(UUID senderId,
                                              MenuAction.ApplyStagedConfig apply,
                                              @Nullable Consumer<String> messageMethod) {
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu config-apply denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu config-apply rejected: permission denied", messageMethod);
            return false;
        }
        LinkedHashMap<String, String> entries = applyCart(senderId, apply.fileName());
        if (entries.isEmpty()) {
            RTP.log(Level.WARNING,
                    "menu config-apply: empty cart for " + senderId
                            + " file=" + apply.fileName());
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu config-apply rejected: empty cart for '" + apply.fileName() + "'",
                    messageMethod);
            return false;
        }
        // Build args: ["config", fileName, "k1=v1", "k2=v2", ...]. The
        // commands-api parameter parser accepts multiple `k=v` pairs on a
        // single command line (the user confirmed this on 2026-05-20 when
        // approving the staging-cart proposal), so a single batched
        // dispatch suffices — no per-key loop required.
        String[] args = new String[2 + entries.size()];
        args[0] = "config";
        args[1] = apply.fileName();
        int i = 2;
        for (Map.Entry<String, String> e : entries.entrySet()) {
            args[i++] = e.getKey() + "=" + e.getValue();
        }
        return dispatchRun(senderId, new MenuAction.RunRtpCommand(args), messageMethod);
    }

    /**
     * Dispatch for {@link MenuAction.DiscardStagedConfig}: drop the player's
     * cart for {@code fileName} without applying any edits, then re-render
     * the curated {@code /rtp config <file>} page. Gates on
     * {@link #CONFIG_VIEW_PERMISSION}.
     */
    private boolean dispatchDiscardStagedConfig(UUID senderId,
                                                MenuAction.DiscardStagedConfig discard,
                                                @Nullable Consumer<String> messageMethod) {
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu config-discard denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu config-discard rejected: permission denied", messageMethod);
            return false;
        }
        // applyCart pops and returns; we discard the result.
        applyCart(senderId, discard.fileName());
        return dispatchOpenConfigFile(senderId,
                new MenuAction.OpenConfigFile(discard.fileName()), messageMethod);
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
    private boolean dispatchOpenMultiConfigSelector(UUID senderId,
                                                    MenuAction.OpenMultiConfigSelector open,
                                                    @Nullable Consumer<String> messageMethod) {
        if (renderer == null || multiConfigBuilder == null) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-selector received with multiconfig disabled for " + senderId);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu multiconfig-selector rejected: multiconfig disabled", messageMethod);
            return false;
        }
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-selector denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, MessagesKeys.menuInvalid,
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
            reject(senderId, MessagesKeys.menuInvalid,
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
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu multiconfig-selector rejected: builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu multiconfig-selector rejected: builder returned null model",
                    messageMethod);
            return false;
        }
        try {
            renderer.render(senderId, model);
            return true;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-selector render failed for " + senderId
                            + " kind=" + effectiveKind + ": " + e.getMessage(), e);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu multiconfig-selector rejected: renderer failure", messageMethod);
            return false;
        }
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
    private boolean dispatchOpenMultiConfigEntry(UUID senderId,
                                                 MenuAction.OpenMultiConfigEntry open,
                                                 @Nullable Consumer<String> messageMethod) {
        if (renderer == null || multiConfigBuilder == null) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-entry received with multiconfig disabled for " + senderId);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu multiconfig-entry rejected: multiconfig disabled", messageMethod);
            return false;
        }
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-entry denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, MessagesKeys.menuInvalid,
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
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu multiconfig-entry rejected: unknown parser kind", messageMethod);
            return false;
        }
        MenuModel model;
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            MenuModel m = multiConfigBuilder.buildEntry(
                    kind, entry,
                    (io.github.dailystruggle.rtp.common.configuration.MultiConfigParser) parser,
                    senderId);
            model = m;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-entry builder failed for " + senderId
                            + " kind=" + kind + " entry=" + entry + ": " + e.getMessage(), e);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu multiconfig-entry rejected: builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu multiconfig-entry rejected: builder returned null model",
                    messageMethod);
            return false;
        }
        try {
            renderer.render(senderId, model);
            return true;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-entry render failed for " + senderId
                            + " kind=" + kind + " entry=" + entry + ": " + e.getMessage(), e);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu multiconfig-entry rejected: renderer failure", messageMethod);
            return false;
        }
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
    private boolean dispatchMultiConfigMutate(UUID senderId,
                                              MenuAction.MultiConfigMutate mutate,
                                              @Nullable Consumer<String> messageMethod) {
        if (renderer == null || multiConfigBuilder == null) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-mutate received with multiconfig disabled for " + senderId);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu multiconfig-mutate rejected: multiconfig disabled", messageMethod);
            return false;
        }
        if (!hasConfigViewPermission(senderId)) {
            RTP.log(Level.WARNING,
                    "menu multiconfig-mutate denied: " + senderId
                            + " lacks " + CONFIG_VIEW_PERMISSION);
            reject(senderId, MessagesKeys.menuInvalid,
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
            reject(senderId, MessagesKeys.menuInvalid,
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
                reject(senderId, MessagesKeys.menuInvalid,
                        "menu multiconfig-mutate rejected: entry locked", messageMethod);
                return false;
            }
            try {
                parser.removeParser(entry);
            } catch (RuntimeException e) {
                RTP.log(Level.WARNING,
                        "menu multiconfig-mutate REMOVE failed for " + senderId
                                + " kind=" + kind + " entry=" + entry + ": " + e.getMessage(), e);
                reject(senderId, MessagesKeys.menuInvalid,
                        "menu multiconfig-mutate rejected: remove failure", messageMethod);
                return false;
            }
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
                reject(senderId, MessagesKeys.menuInvalid,
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
            reject(senderId, MessagesKeys.menuInvalid,
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

    private static @Nullable String extractToken(Map<String, List<String>> parameterValues) {
        if (parameterValues == null) return null;
        List<String> vs = parameterValues.get(PARAM_TOKEN);
        if (vs != null && !vs.isEmpty() && vs.get(0) != null && !vs.get(0).isEmpty()) {
            return vs.get(0);
        }
        // Fallback: commands-api may key the bare arg under the literal name.
        vs = parameterValues.get("menu");
        if (vs != null && !vs.isEmpty() && vs.get(0) != null && !vs.get(0).isEmpty()) {
            return vs.get(0);
        }
        return null;
    }

    private void reject(@Nullable UUID senderId,
                        MessagesKeys key,
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

    private static String defaultFor(MessagesKeys key) {
        return switch (key) {
            case menuInvalid -> "Invalid menu token.";
            case menuExpired -> "That menu has expired — please re-open it.";
            case menuUnknownPlayer -> "Menus may only be used by online players.";
            default -> key.name();
        };
    }
}
