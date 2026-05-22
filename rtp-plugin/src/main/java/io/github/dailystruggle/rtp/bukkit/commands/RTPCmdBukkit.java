package io.github.dailystruggle.rtp.bukkit.commands;

import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.*;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.bukkit.events.TeleportCommandFailEvent;
import io.github.dailystruggle.rtp.bukkit.events.TeleportCommandSuccessEvent;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.api.menu.MenuConsumerProfile;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.api.menu.MenuTokenRegistry;
import io.github.dailystruggle.rtp.common.commands.RTPCmd;
import io.github.dailystruggle.rtp.common.commands.menu.AdminPanelBuilder;
import io.github.dailystruggle.rtp.common.commands.menu.CommandTreeMenuBuilder;
import io.github.dailystruggle.rtp.common.commands.menu.FrontPageBuilder;
import io.github.dailystruggle.rtp.common.commands.menu.LocalMenuTokenRegistry;
import io.github.dailystruggle.rtp.common.commands.menu.MenuRedeemSubcommand;
import io.github.dailystruggle.rtp.common.commands.scan.ScanCmd;
import io.github.dailystruggle.rtp.bukkit.commands.test.BukkitTestCmd;
import io.github.dailystruggle.rtp.common.commands.info.InfoCmd;
import io.github.dailystruggle.rtp.common.commands.parameters.RegionParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.ShapeParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.VertParameter;
import io.github.dailystruggle.rtp.common.commands.reload.ReloadCmd;
import io.github.dailystruggle.rtp.common.commands.config.ConfigCmd;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import java.util.Locale;
import java.util.function.Function;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class RTPCmdBukkit extends BukkitBaseRTPCmd implements RTPCmd {
  // for optimizing parameters,

  private final Semaphore senderChecksGuard = new Semaphore(1);
  private final List<Predicate<CommandSender>> senderChecks = new ArrayList<>();

  public RTPCmdBukkit(Plugin plugin) {
    super(plugin, null);

    // Route reply messages through SendMessage so raw templates (placeholders,
    // '&' colour codes, hex tokens, PAPI) are formatted at the platform
    // boundary instead of leaking to console as e.g. "&c[P0] ...".
    // The default in BukkitTreeCommand is sender::sendMessage which bypasses
    // formatting entirely.
    this.messageMethodFactory =
        sender -> msg -> io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.sendMessage(sender, msg);

    // region name parameter
    // filter by region exists and sender permission. L6 Slice H2: validator
    // also accepts qualified `server:region` syntax when the named peer is
    // reachable per the live PeerRegionRegistry (looked up dynamically via
    // NetworkModeBootstrap.LIVE so this code path works whether network
    // mode boots before or after command registration). Extras supplier
    // surfaces peer-qualified entries in tab-completion.
    RegionParameter regionParameter =
        new RegionParameter(
            "rtp.region",
            "select a region to teleport to",
            (uuid, s) -> {
              if (s == null) return false;
              RTPCommandSender sender = RTP.serverAccessor.getSender(uuid);
              // Path 1: bare local region name (pre-H2 behaviour).
              if (RTP.selectionAPI.regionNames().contains(s)) {
                return sender.hasPermission("rtp.regions." + s);
              }
              // Path 2: qualified `server:region` (H2). Parse strictly; on
              // any malformed input fall through to reject.
              io.github.dailystruggle.rtp.bukkit.network.NetworkRouter.ParsedRegion parsed;
              try {
                parsed = io.github.dailystruggle.rtp.bukkit.network.NetworkRouter.parseRegionArgQualified(s);
              } catch (IllegalArgumentException malformed) {
                return false;
              }
              if (parsed == null || parsed.serverHint() == null) return false;
              io.github.dailystruggle.rtp.bukkit.network.NetworkModeBootstrap live =
                  io.github.dailystruggle.rtp.bukkit.network.NetworkModeBootstrap.LIVE;
              if (live == null) return false;
              io.github.dailystruggle.rtp.bukkit.network.PeerRegionRegistry registry =
                  live.peerRegionRegistry();
              if (registry == null) return false;
              if (!registry.isReachableHardPin(parsed.serverHint(), parsed.regionKey())) {
                return false;
              }
              // Permission is keyed on the bare region name, not the
              // qualified form, so an operator's existing
              // `rtp.regions.default` grant covers `backend-a:default`.
              return sender.hasPermission("rtp.regions." + parsed.regionKey());
            },
            () -> {
              io.github.dailystruggle.rtp.bukkit.network.NetworkModeBootstrap live =
                  io.github.dailystruggle.rtp.bukkit.network.NetworkModeBootstrap.LIVE;
              if (live == null) return java.util.Set.of();
              io.github.dailystruggle.rtp.bukkit.network.PeerRegionRegistry registry =
                  live.peerRegionRegistry();
              return registry == null ? java.util.Set.<String>of() : registry.peerEntries();
            });
    regionParameter.put(
        "world",
        new io.github.dailystruggle.rtp.common.commands.parameters.WorldParameter(
            "rtp.params",
            "modify xz selection",
            (uuid, s) ->
                (Bukkit.getWorld(s) != null)
                    & RTP.serverAccessor.getSender(uuid).hasPermission("rtp.worlds." + s)));
    regionParameter.put(
        "price",
        new FloatParameter(
            "rtp.params",
            "modify xz selection",
            (uuid, s) -> {
              try {
                Double.parseDouble(s);
                return true;
              } catch (NumberFormatException exception) {
                return false;
              }
            }));
    regionParameter.put(
        "worldborderoverride",
        new BooleanParameter(
            "rtp.params",
            "modify xz selection",
            (uuid, s) -> (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))));
    regionParameter.put(
        "shape",
        new ShapeParameter(
            "rtp.params",
            "modify xz selection",
            (uuid, s) -> RTP.factoryMap.get(RTP.factoryNames.shape).contains(s)));
    regionParameter.put(
        "vert",
        new VertParameter(
            "rtp.params",
            "modify y selection",
            (uuid, s) -> RTP.factoryMap.get(RTP.factoryNames.vert).contains(s)));

    addParameter("region", regionParameter);

    addParameter(
        "biome",
        new io.github.dailystruggle.rtp.common.commands.parameters.BiomeParameter(
            "rtp.biome",
            "select a biome to teleport to",
            (uuid, s) -> {
              RTPCommandSender sender = RTP.serverAccessor.getSender(uuid);
              // Biome keys are upper-cased with the root locale to avoid locale-dependent
              // case folding (e.g. Turkish 'i' -> 'İ'), which would otherwise miss biomes
              // such as ICE_SPIKES on operators running a tr_TR JVM.
              return (sender.hasPermission("rtp.biome.*") || sender.hasPermission("rtp.biome." + s))
                      && RTP.serverAccessor.getBiomes().contains(s.toUpperCase(java.util.Locale.ROOT));
            }));

    // target player parameter
    // filter by player exists and player permission
    addParameter(
        "player",
        new OnlinePlayerParameter(
            "rtp.other",
            "teleport someone else",
            (sender, s) -> {
              if (!sender.hasPermission("rtp.other")) return false;
              Player player = Bukkit.getPlayer(s);
              return player != null && player.getName().equalsIgnoreCase(s) && !player.hasPermission("rtp.notme");
            }));

    // world name parameter
    // filter by world exists and sender permission
    addParameter(
        "world",
        new WorldParameter(
            "rtp.world",
            "select a world to teleport to",
            (sender, s) -> {
                org.bukkit.World world = Bukkit.getWorld(s);
                return world != null && world.getName().equalsIgnoreCase(s) && sender.hasPermission("rtp.worlds." + s);
            }));

    addParameter(
        "toggletargetperms",
        new BooleanParameter(
            "rtp.params",
            "check player's perms when running this command",
            (sender, s) -> sender.hasPermission("rtp.params") && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))));

    addSubCommand(new ReloadCmd(this));
    // /rtp help intentionally NOT registered as a subcommand: when no HELP
    // subcommand exists, commands-api's TreeCommand auto-dispatches the
    // built-in help() listing (see TreeCommand line 231), which covers
    // every registered subcommand instead of only those that happen to
    // have a MessagesKeys enum value. Clickable suggestions are restored
    // by the help-line wrapping in the messageMethod consumer below.
    addSubCommand(new ConfigCmd(this));
    addSubCommand(new ScanCmd(this));
    addSubCommand(new InfoCmd(this));
    addSubCommand(new BukkitTestCmd(this));

    // /rtp menu — generalized menu subcommand (ADR-035 / ADR-044).
    // Restores the Stage 3.1 / 4.2.d / nav-5b wiring that
    // CHECKLIST-generalized-menu.md and CHECKLIST-menu-navigation.md describe.
    // The page builder reflects the live /rtp tree via CommandTreeMenuBuilder
    // (filtered by the viewer's permissions, so inaccessible rows are hidden,
    // not greyed). The renderer is resolved per `menu.renderer` config order,
    // reflectively (rtp-plugin does not link rtp-paper-common at compile
    // time — see build.gradle). If no renderer can be constructed the
    // subcommand stays registered and rejects with the configurable
    // `menuInvalid` message so /rtp menu is at least *recognised* on every
    // platform (REQ-RTP-S-007 / F-013).
    final MenuTokenRegistry menuTokenRegistry = new LocalMenuTokenRegistry();
    final Function<UUID, Predicate<String>> menuPermissionProbe =
        viewer -> perm -> {
          if (perm == null || perm.isEmpty()) return true;
          if (viewer.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
            return true;
          }
          Player p = Bukkit.getPlayer(viewer);
          return p != null && p.hasPermission(perm);
        };
    final MenuRenderer menuRenderer = selectMenuRenderer(menuTokenRegistry);
    final FrontPageBuilder frontPageBuilder = new FrontPageBuilder(menuTokenRegistry);
    final AdminPanelBuilder adminPanelBuilder = new AdminPanelBuilder(menuTokenRegistry);
    final MenuRedeemSubcommand.MenuPageBuilder menuPageBuilder =
        (node, open, assembledPath) -> {
          // Stage B: the root /rtp menu page is the curated front page, not
          // the flat reflector. Any descended node (assembledPath non-empty)
          // or any node that isn't this command root falls through to the
          // existing CommandTreeMenuBuilder reflector. Visibility / row
          // selection is handled inside FrontPageBuilder via the viewer's
          // permission probe.
          if (node == this && assembledPath.isEmpty()) {
            return frontPageBuilder.build(
                node, open.viewer(), menuPermissionProbe.apply(open.viewer()));
          }
          return new CommandTreeMenuBuilder(menuTokenRegistry)
              .build(
                  node,
                  open.viewer(),
                  menuPermissionProbe.apply(open.viewer()),
                  MenuConsumerProfile.defaultProfile(),
                  assembledPath);
        };
    // Stage A.2: wire the param-picker builder so OpenParamPicker redeems
    // resolve to a value-picker sub-page instead of the "picker-page
    // disabled" reject path (see MenuRedeemSubcommand.dispatchOpenParamPicker).
    final MenuRedeemSubcommand.MenuParamPickerBuilder menuParamPickerBuilder =
        (parent, viewer, parentPath, paramName) ->
            new CommandTreeMenuBuilder(menuTokenRegistry)
                .buildParamPicker(
                    parent,
                    viewer,
                    menuPermissionProbe.apply(viewer),
                    MenuConsumerProfile.defaultProfile(),
                    parentPath,
                    paramName);
    // ADR-045: try to wire an anvil-GUI input opener for the
    // "type a custom value..." picker row. Reflective for the same reason
    // as the renderer — rtp-plugin doesn't link rtp-paper-common at compile
    // time. Null is the documented disabled state; redeem rejects with the
    // configurable menuInvalid message in that case.
    final MenuRedeemSubcommand.AnvilInputOpener anvilOpener =
        selectAnvilOpener(plugin);
    // PROPOSAL-config-view-as-book v3.7 — wire the production config-subtree
    // builder so OpenConfigSelector / OpenConfigFile tokens redeem to the
    // curated book pages instead of the "config-subtree disabled" reject.
    // Selector enumerates every parser registered in RTP.configs; per-file
    // pages resolve the parser by its baseline name (with or without the
    // .yml suffix). buildKey is left null-returning for now: the per-key
    // value-picker integration with the live SubConfigCmd node is a
    // follow-up slice (PROPOSAL v3.7 §3.3 deferred); until then a key click
    // falls through to the existing "unknown" reject path with the
    // configurable menuInvalid message.
    final MenuRedeemSubcommand.MenuConfigSubtreeBuilder configSubtreeBuilder =
        new MenuRedeemSubcommand.MenuConfigSubtreeBuilder() {
          @Override
          public io.github.dailystruggle.rtp.api.menu.MenuModel buildSelector(UUID viewer) {
            List<String> fileNames = new ArrayList<>();
            try {
              for (ConfigParser<?> parser : RTP.configs.configParserMap.values()) {
                if (parser == null || parser.name == null) continue;
                String n = parser.name;
                if (n.toLowerCase(Locale.ROOT).endsWith(".yml")) {
                  n = n.substring(0, n.length() - 4);
                }
                if (!n.isEmpty()) fileNames.add(n);
              }
            } catch (RuntimeException e) {
              RTP.log(Level.WARNING,
                  "menu config-selector: failed to enumerate parsers: " + e.getMessage(), e);
            }
            java.util.Collections.sort(fileNames);
            return new CommandTreeMenuBuilder(menuTokenRegistry)
                .buildConfigSelector(viewer, fileNames);
          }

          @Override
          public io.github.dailystruggle.rtp.api.menu.MenuModel buildFile(
              UUID viewer, String fileName) {
            ConfigParser<?> parser = resolveParserByFileName(fileName);
            if (parser == null) return null;
            return new CommandTreeMenuBuilder(menuTokenRegistry)
                .buildConfigFile(viewer, stripYml(fileName), parser,
                    new java.util.LinkedHashMap<>());
          }

          @Override
          public io.github.dailystruggle.rtp.api.menu.MenuModel buildFile(
              UUID viewer, String fileName,
              java.util.LinkedHashMap<String, String> cartSnapshot) {
            // Item 6 of the staging-cart redesign: cart-aware rendering of
            // /rtp config <file>. cartSnapshot is the viewer's currently
            // staged (paramName -> typed value) pairs for this file; empty
            // when no cart is active. Forwarded into buildConfigFile so the
            // Changeable list filters staged keys and Pending + Apply +
            // Discard rows are appended.
            ConfigParser<?> parser = resolveParserByFileName(fileName);
            if (parser == null) return null;
            return new CommandTreeMenuBuilder(menuTokenRegistry)
                .buildConfigFile(viewer, stripYml(fileName), parser,
                    cartSnapshot == null
                        ? new java.util.LinkedHashMap<>()
                        : cartSnapshot);
          }

          @Override
          public io.github.dailystruggle.rtp.api.menu.MenuModel buildKey(
              UUID viewer, String fileName, String paramName) {
            // PROPOSAL v3.7 §3.3 — resolve the live SubConfigCmd node for
            // <fileName> and reuse buildParamPicker so suggested values
            // write back via /rtp config <fileName>.yml <paramName>:<value>
            // (matches the SubConfigCmd.onCommand parameterValues path:
            // per-key parameters live directly on the SubConfigCmd, there
            // is no intermediate "set" subcommand).
            //
            // Walk: this (rtp root) -> "config" subcmd -> "<fileName>.yml"
            // SubConfigCmd. commands-api keys subcommand lookup by upper-
            // case name (TreeCommand#addSubCommand line 30). For flat
            // configs the SubConfigCmd directly holds the per-key
            // CommandParameters (added in SubConfigCmd#addParameters
            // ~lines 384-448). For MultiConfigParser configs (regions,
            // worlds) the SubConfigCmd's children are nested SubConfigCmds
            // keyed by region/world id and the root MultiConfig node has
            // no per-key params -- buildParamPicker degrades to the
            // back+header+type-fallback layout in that case (a follow-up
            // slice will add a region/world id selector page for those).
            //
            // The parentPath that we hand to buildParamPicker is what
            // PromptAnvilInput will carry; dispatchPromptAnvilInput walks
            // it against the live TreeCommand graph from rtpRoot, so its
            // segments must match the actual subcommand names (uppercase
            // lookup is applied by the dispatcher). The SubConfigCmd is
            // registered under its parser's name, which includes the
            // ".yml" suffix; using the bare basename here would fail the
            // walk with "unknown path segment '<fileName>' under config".
            CommandsAPICommand configCmd = getCommandLookup().get("CONFIG");
            if (!(configCmd
                instanceof io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand configTree)) {
              return null;
            }
            String subKey = (stripYml(fileName) + ".yml").toUpperCase(Locale.ROOT);
            CommandsAPICommand subCmd = configTree.getCommandLookup().get(subKey);
            String subSegment = stripYml(fileName) + ".yml";
            if (subCmd == null) {
              // Some baseline registrations historically used the bare
              // file name without .yml; try that as a fallback and adjust
              // the parentPath segment accordingly so the dispatcher walk
              // matches the actual subcommand name.
              subCmd = configTree.getCommandLookup().get(
                  stripYml(fileName).toUpperCase(Locale.ROOT));
              if (subCmd != null) {
                subSegment = stripYml(fileName);
              }
            }
            if (!(subCmd
                instanceof io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand parent)) {
              return null;
            }
            List<String> parentPath = List.of("config", subSegment);
            return new CommandTreeMenuBuilder(menuTokenRegistry)
                .buildParamPicker(
                    parent,
                    viewer,
                    menuPermissionProbe.apply(viewer),
                    MenuConsumerProfile.defaultProfile(),
                    parentPath,
                    paramName);
          }
        };
    // PROPOSAL-admin-panel.md v2 — wire the curated admin-panel and front-page
    // builders so OpenAdminPanel / OpenFrontPage tokens redeem to the curated
    // book pages instead of the "curated-page builder disabled" reject path.
    // buildFrontPage delegates to the existing FrontPageBuilder (same closure
    // shape as the no-token open-page branch above); buildAdminPanel routes
    // through the new AdminPanelBuilder. The rtp.menu.admin gate is enforced
    // in MenuRedeemSubcommand.dispatchOpenAdminPanel, not here.
    final io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand rtpRoot = this;
    final MenuRedeemSubcommand.MenuCuratedPageBuilder curatedPageBuilder =
        new MenuRedeemSubcommand.MenuCuratedPageBuilder() {
          @Override
          public io.github.dailystruggle.rtp.api.menu.MenuModel buildAdminPanel(UUID viewer) {
            return adminPanelBuilder.build(
                rtpRoot, viewer, menuPermissionProbe.apply(viewer));
          }

          @Override
          public io.github.dailystruggle.rtp.api.menu.MenuModel buildFrontPage(UUID viewer) {
            return frontPageBuilder.build(
                rtpRoot, viewer, menuPermissionProbe.apply(viewer));
          }
        };
    // PROPOSAL-rtp-menu-config-search.md slice 5b — production
    // MenuConfigSearchBuilder. Walks RTP.configs via the already-landed
    // ConfigSearchResultsBuilder, renders a paginated MenuModel where each
    // hit row is a gray-base line with an off-blue highlight overlay at the
    // raw-offset match ranges (constraint §2.8: raw text is displayed so
    // operators see color codes verbatim and can edit them). Each row click
    // resolves to OpenConfigKey(file, key) so the matched key opens
    // directly in the existing per-key editor.
    final MenuRedeemSubcommand.MenuConfigSearchBuilder configSearchBuilder =
        (UUID viewer, String query, int page) -> {
          if (query == null) query = "";
          String safeQuery = query;
          int safePage = Math.max(1, page);
          java.util.List<io.github.dailystruggle.rtp.common.menu.search.ConfigSearchResultsBuilder.Hit>
              hits;
          try {
            hits =
                io.github.dailystruggle.rtp.common.menu.search.ConfigSearchResultsBuilder.search(
                    safeQuery);
          } catch (RuntimeException e) {
            RTP.log(
                Level.WARNING,
                "menu config-search: search failed for query='" + safeQuery + "': " + e.getMessage(),
                e);
            return null;
          }

          final int rowsPerPage = 8;
          final String backLabel = "&7« back";
          final io.github.dailystruggle.rtp.api.menu.MenuLine backRow =
              io.github.dailystruggle.rtp.api.menu.MenuLine.of(
                  new io.github.dailystruggle.rtp.api.menu.MenuFragment(
                      backLabel,
                      null,
                      new io.github.dailystruggle.rtp.api.menu.MenuAction.OpenConfigSelector()));
          final io.github.dailystruggle.rtp.api.menu.MenuLine headerRow =
              io.github.dailystruggle.rtp.api.menu.MenuLine.of(
                  new io.github.dailystruggle.rtp.api.menu.MenuFragment(
                      "&1&lconfig search: &9" + safeQuery, null, null));

          java.util.List<io.github.dailystruggle.rtp.api.menu.MenuPage> pages = new ArrayList<>();
          java.util.List<io.github.dailystruggle.rtp.api.menu.MenuLine> lines = new ArrayList<>();
          lines.add(backRow);
          lines.add(headerRow);
          if (hits.isEmpty()) {
            lines.add(
                io.github.dailystruggle.rtp.api.menu.MenuLine.of(
                    new io.github.dailystruggle.rtp.api.menu.MenuFragment(
                        "&7(no matches)", null, null)));
            pages.add(new io.github.dailystruggle.rtp.api.menu.MenuPage(lines));
          } else {
            for (var hit : hits) {
              io.github.dailystruggle.rtp.api.menu.MenuAction click =
                  new io.github.dailystruggle.rtp.api.menu.MenuAction.OpenConfigKey(
                      hit.fileName(), hit.keyName());
              java.util.List<io.github.dailystruggle.rtp.api.menu.MenuFragment> frags =
                  new ArrayList<>();
              // Row prefix: "<file>/<key>: "
              String prefix = "&8" + hit.fileName() + "&7/&2" + hit.keyName() + "&7: ";
              // Build value fragments: gray base, with off-blue (&9&l) runs
              // overlaid at raw match ranges. For key-name matches no value
              // highlight ranges exist, so the value is plain gray.
              String raw = hit.rawValue();
              java.util.List<int[]> ranges = hit.matchRanges();
              if (ranges == null || ranges.isEmpty()) {
                frags.add(
                    new io.github.dailystruggle.rtp.api.menu.MenuFragment(
                        prefix + "&7" + raw, null, click));
              } else {
                // First fragment carries the prefix + any leading gray text
                // before the first match. Subsequent fragments alternate
                // highlight / gray runs. All share the same click action so
                // a click anywhere on the row opens the matched key.
                int cursor = 0;
                StringBuilder head = new StringBuilder(prefix).append("&7");
                int[] first = ranges.get(0);
                if (first[0] > 0) head.append(raw, 0, first[0]);
                frags.add(
                    new io.github.dailystruggle.rtp.api.menu.MenuFragment(
                        head.toString(), null, click));
                cursor = first[0];
                for (int i = 0; i < ranges.size(); i++) {
                  int[] r = ranges.get(i);
                  // highlight run
                  frags.add(
                      new io.github.dailystruggle.rtp.api.menu.MenuFragment(
                          "&9&l" + raw.substring(r[0], r[1]) + "&r&7", null, click));
                  cursor = r[1];
                  int nextStart =
                      (i + 1 < ranges.size()) ? ranges.get(i + 1)[0] : raw.length();
                  if (nextStart > cursor) {
                    frags.add(
                        new io.github.dailystruggle.rtp.api.menu.MenuFragment(
                            raw.substring(cursor, nextStart), null, click));
                  }
                }
              }
              lines.add(new io.github.dailystruggle.rtp.api.menu.MenuLine(frags));
              if (lines.size() - 2 >= rowsPerPage) {
                pages.add(new io.github.dailystruggle.rtp.api.menu.MenuPage(lines));
                lines = new ArrayList<>();
                lines.add(backRow);
                lines.add(headerRow);
              }
            }
            if (lines.size() > 2) {
              pages.add(new io.github.dailystruggle.rtp.api.menu.MenuPage(lines));
            }
          }

          // Mint single-use tokens for every clickable fragment so the
          // BookMenuRenderer's click-event arm can resolve them.
          for (var p : pages) {
            for (var line : p.lines()) {
              for (var frag : line.fragments()) {
                var action = frag.action();
                if (action != null) {
                  menuTokenRegistry.mint(viewer, action, java.time.Duration.ofMinutes(5));
                }
              }
            }
          }

          // Clamp page index to available pages (renderer pagination is
          // book-side; we serve a single MenuModel with all pages).
          if (pages.isEmpty()) return null;
          return new io.github.dailystruggle.rtp.api.menu.MenuModel(
              "config:search:" + safeQuery, pages);
        };

    // PROPOSAL-info-as-book.md section 4.6 — curated /rtp info book builder.
    // The closure captures the live /rtp tree (this) and the shared token
    // registry, and is invoked synchronously per click; InfoBookBuilder itself
    // installs the RTP.messageTap, drives InfoCmd, paginates, and mints
    // refresh / switch-to-chat tokens against menuTokenRegistry.
    final io.github.dailystruggle.rtp.common.commands.menu.InfoBookBuilder
        infoBookBuilderImpl =
            new io.github.dailystruggle.rtp.common.commands.menu.InfoBookBuilder(
                menuTokenRegistry);
    final MenuRedeemSubcommand.MenuInfoBookBuilder infoBookBuilder =
        (UUID viewer,
            io.github.dailystruggle.rtp.api.menu.MenuAction.InfoScopeToken scope) ->
            infoBookBuilderImpl.build(this, viewer, scope);

    final MenuRedeemSubcommand menuRedeem =
        new MenuRedeemSubcommand(
            this,
            menuTokenRegistry,
            menuPermissionProbe,
            menuRenderer,
            menuPageBuilder,
            menuParamPickerBuilder,
            anvilOpener,
            configSubtreeBuilder,
            curatedPageBuilder,
            configSearchBuilder,
            infoBookBuilder);
    // Staging-cart wiring: bind the anvil opener's cart sink to this redeem
    // instance so STAGE-mode anvil confirms push into the per-player cart
    // on this backend (see docs/dev/scratch/CHECKLIST-config-staging-cart.md).
    // The sink is a per-instance method handle exposed by MenuRedeemSubcommand
    // and survives the lifetime of the subcommand. Legacy AnvilInputOpener
    // implementations (test scaffolds) default-no-op `setCartSink`.
    if (anvilOpener != null) {
      anvilOpener.setCartSink(menuRedeem.cartSink());
    }
    addSubCommand(menuRedeem);

    // PROPOSAL-admin-panel-prefabs.md v3.1 (Session 4b D1) — register the
    // top-level `/rtp admin` verb whose bare form opens the curated admin
    // panel (the same MenuModel /rtp menu's "Admin panel" entry row opens
    // via OpenAdminPanel), and host the `prefab` subtree under it. The
    // panel-opener is a Consumer<UUID> that mirrors
    // MenuRedeemSubcommand#dispatchOpenAdminPanel: permission-probe on
    // rtp.menu.admin, build via adminPanelBuilder, render via the resolved
    // MenuRenderer. The bare /rtp admin form's own commands-api permission
    // gate (rtp.menu.admin, see AdminCmd.PERMISSION) is the primary check;
    // the probe inside the opener is defence in depth so a stale closure
    // can't bypass the gate. When the renderer is null (no menu renderer
    // available on this platform) the opener is null and the bare form
    // rejects with the configurable menuInvalid message per S-004 / S-007.
    final MenuRenderer adminMenuRenderer = menuRenderer;
    final AdminPanelBuilder adminPanelBuilderRef = adminPanelBuilder;
    final Consumer<UUID> openAdminPanel;
    if (adminMenuRenderer == null) {
      openAdminPanel = null;
    } else {
      openAdminPanel =
          viewer -> {
            if (viewer == null) return;
            Predicate<String> probe = menuPermissionProbe.apply(viewer);
            try {
              if (probe == null || !probe.test("rtp.menu.admin")) {
                RTP.log(
                    Level.WARNING,
                    "/rtp admin opener: " + viewer + " lacks rtp.menu.admin");
                return;
              }
            } catch (RuntimeException e) {
              RTP.log(
                  Level.WARNING,
                  "/rtp admin opener: permission probe threw for " + viewer
                      + ": " + e.getMessage(),
                  e);
              return;
            }
            io.github.dailystruggle.rtp.api.menu.MenuModel model;
            try {
              model = adminPanelBuilderRef.build(this, viewer, probe);
            } catch (RuntimeException e) {
              RTP.log(
                  Level.WARNING,
                  "/rtp admin opener: admin-panel builder failed for " + viewer
                      + ": " + e.getMessage(),
                  e);
              return;
            }
            if (model == null) {
              RTP.log(
                  Level.WARNING,
                  "/rtp admin opener: admin-panel builder returned null model for " + viewer);
              return;
            }
            try {
              adminMenuRenderer.render(viewer, model);
            } catch (RuntimeException e) {
              RTP.log(
                  Level.WARNING,
                  "/rtp admin opener: renderer failed for " + viewer
                      + ": " + e.getMessage(),
                  e);
            }
          };
    }
    final io.github.dailystruggle.rtp.common.commands.admin.AdminCmd adminCmd =
        new io.github.dailystruggle.rtp.common.commands.admin.AdminCmd(this, openAdminPanel);
    adminCmd.addSubCommand(
        new io.github.dailystruggle.rtp.common.commands.prefab.PrefabCommand(adminCmd));
    addSubCommand(adminCmd);

    // PROPOSAL-rtp-menu-config-search.md slice 5b — wire the search-leaf
    // Handler now that the renderer + builder are constructed. ConfigCmd
    // registers ConfigSearchSubCmd via its own 5-tick deferred addCommands;
    // schedule our handler attachment at 8 ticks so it lands after ConfigCmd
    // but before the menu mirror seeds at 10 ticks. The handler runs the
    // search builder and renders the resulting MenuModel directly (the
    // anvil opener submits "/rtp menu config search query:<typed>" which
    // walks through MenuMirrorSubcommand to this leaf rather than the
    // MenuRedeemSubcommand dispatch path).
    final MenuRedeemSubcommand.MenuConfigSearchBuilder finalSearchBuilder = configSearchBuilder;
    final MenuRenderer finalRenderer = menuRenderer;
    RTP.getInstance()
        .miscAsyncTasks
        .add(
            new io.github.dailystruggle.rtp.common.tasks.RTPRunnable(
                () -> {
                  CommandsAPICommand configCmd = getCommandLookup().get("CONFIG");
                  if (!(configCmd
                      instanceof io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand configTree)) {
                    return;
                  }
                  CommandsAPICommand searchCmd = configTree.getCommandLookup().get("SEARCH");
                  if (!(searchCmd
                      instanceof io.github.dailystruggle.rtp.common.commands.config.ConfigSearchSubCmd searchLeaf)) {
                    return;
                  }
                  if (finalRenderer == null || finalSearchBuilder == null) return;
                  searchLeaf.setHandler(
                      (UUID callerId, String query) -> {
                        var model = finalSearchBuilder.buildResults(callerId, query, 1);
                        if (model == null) {
                          RTP.log(
                              Level.WARNING,
                              "config search: empty model for query='" + query + "'");
                          return;
                        }
                        finalRenderer.render(callerId, model);
                      });
                },
                8));
  }


  /**
   * Resolves a {@link ConfigParser} by its baseline file name (case-insensitive,
   * with or without the {@code .yml} suffix). Returns {@code null} when no
   * parser matches; callers route that to the configurable {@code menuInvalid}
   * reject (S-004 spirit).
   */
  private static @org.jetbrains.annotations.Nullable ConfigParser<?> resolveParserByFileName(
      String fileName) {
    if (fileName == null || fileName.isEmpty()) return null;
    String wanted = stripYml(fileName).toLowerCase(Locale.ROOT);
    try {
      for (ConfigParser<?> parser : RTP.configs.configParserMap.values()) {
        if (parser == null || parser.name == null) continue;
        String n = stripYml(parser.name).toLowerCase(Locale.ROOT);
        if (n.equals(wanted)) return parser;
      }
    } catch (RuntimeException e) {
      RTP.log(Level.WARNING,
          "menu config: failed to resolve parser '" + fileName + "': " + e.getMessage(), e);
    }
    return null;
  }

  private static String stripYml(String s) {
    if (s == null) return "";
    return s.toLowerCase(Locale.ROOT).endsWith(".yml")
        ? s.substring(0, s.length() - 4)
        : s;
  }

  /**
   * ADR-045 — instantiates the Paper {@code AnvilInputSession} reflectively
   * and registers it as a Bukkit listener bound to the RTP plugin. Returns
   * {@code null} when the Paper-side class is not on the runtime classpath
   * (e.g. plain Spigot), or instantiation / registration failed; callers
   * tolerate {@code null} (the picker row falls back to the configurable
   * {@code menuInvalid} reject when the player clicks it).
   */
  private static @org.jetbrains.annotations.Nullable
      MenuRedeemSubcommand.AnvilInputOpener selectAnvilOpener(Plugin plugin) {
    final String className =
        "io.github.dailystruggle.rtp.paper.menu.AnvilInputSession";
    try {
      Class<?> cls = Class.forName(className);
      Object instance = cls.getConstructor().newInstance();
      cls.getMethod("register", Plugin.class).invoke(instance, plugin);
      return (MenuRedeemSubcommand.AnvilInputOpener) instance;
    } catch (ClassNotFoundException cnfe) {
      RTP.log(
          Level.INFO,
          "menu anvil-input unavailable on this platform (" + className + ")");
      return null;
    } catch (ReflectiveOperationException roe) {
      RTP.log(
          Level.WARNING,
          "failed to instantiate menu anvil-input opener: " + roe.getMessage(),
          roe);
      return null;
    }
  }

  /**
   * Resolves the {@link MenuRenderer} from the {@code menu.renderer} config
   * list (first-wins; unknown / failing ids logged at WARNING and skipped).
   * Returns {@code null} if the list is empty, missing, or no listed id could
   * be instantiated — callers must tolerate {@code null} (the redeem path
   * degrades to the configurable {@code menuInvalid} message in that case).
   *
   * <p>Reflection-based on purpose: {@code rtp-plugin} does not declare a
   * compile-time dependency on {@code rtp-paper-common} (see this module's
   * {@code build.gradle}); the Paper {@code BookMenuRenderer} class is only
   * present transitively on the runtime classpath when a {@code rtp-paper-*}
   * adapter is loaded.
   */
  private static @org.jetbrains.annotations.Nullable MenuRenderer selectMenuRenderer(
      MenuTokenRegistry registry) {
    Object raw = null;
    try {
      @SuppressWarnings("unchecked")
      ConfigParser<ConfigKeys> menuConfig =
          (ConfigParser<ConfigKeys>) RTP.configs.getParser(ConfigKeys.class);
      if (menuConfig != null) {
        Object menuBlock = menuConfig.getConfigValue(ConfigKeys.menu, null);
        if (menuBlock instanceof Map<?, ?> map) {
          raw = map.get("renderer");
        } else {
          raw = menuBlock;
        }
      }
    } catch (RuntimeException ignored) {
      // Config not yet initialised (early-boot) or parser absent — fall
      // through to the default below.
    }
    List<String> ids = new ArrayList<>();
    if (raw instanceof List<?> list) {
      for (Object o : list) {
        if (o != null) ids.add(String.valueOf(o).trim().toLowerCase(Locale.ROOT));
      }
    } else if (raw instanceof String s && !s.isBlank()) {
      ids.add(s.trim().toLowerCase(Locale.ROOT));
    }
    if (ids.isEmpty()) {
      // Default-of-defaults: try `book`. Operators who explicitly want no
      // renderer can set `menu.renderer: []`.
      ids.add("book");
    }
    for (String id : ids) {
      MenuRenderer r = tryInstantiateRenderer(id, registry);
      if (r != null) return r;
    }
    RTP.log(
        Level.WARNING,
        "menu.renderer list exhausted (" + ids + "); /rtp menu open-page disabled");
    return null;
  }

  private static @org.jetbrains.annotations.Nullable MenuRenderer tryInstantiateRenderer(
      String id, MenuTokenRegistry registry) {
    final String className;
    switch (id) {
      case "book":
        className = "io.github.dailystruggle.rtp.paper.menu.BookMenuRenderer";
        break;
      default:
        RTP.log(Level.WARNING, "unknown menu.renderer id: " + id);
        return null;
    }
    try {
      Class<?> cls = Class.forName(className);
      return (MenuRenderer) cls
          .getConstructor(MenuTokenRegistry.class)
          .newInstance(registry);
    } catch (ClassNotFoundException cnfe) {
      RTP.log(
          Level.WARNING,
          "menu.renderer '" + id + "' unavailable on this platform (" + className + ")");
      return null;
    } catch (ReflectiveOperationException roe) {
      RTP.log(
          Level.WARNING,
          "failed to instantiate menu.renderer '" + id + "': " + roe.getMessage(),
          roe);
      return null;
    }
  }

  public void addSenderCheck(Predicate<CommandSender> senderCheck) {
    try {
      senderChecksGuard.acquire();
      senderChecks.add(senderCheck);
    } catch (InterruptedException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    } finally {
      senderChecksGuard.release();
    }
  }

  @Override
  public boolean onCommand(CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {


    boolean valid = true;
    for (Predicate<CommandSender> commandSenderPredicate : senderChecks) {
      valid &= commandSenderPredicate.test(sender);
    }
    if (!valid) {

      return false;
    }

    UUID senderUuid = sender instanceof Player
        ? ((Player) sender).getUniqueId()
        : io.github.dailystruggle.rtp.api.RTPAPI.serverId;

    return onCommand(
        RTP.serverAccessor.getSender(senderUuid),
        this,
        label,
        args);
  }

  @Override
  public boolean onCommand(
      UUID senderId,
      Map<String, List<String>> parameterValues,
      CommandsAPICommand nextCommand) {
    return onCommand(senderId, parameterValues, nextCommand, null);
  }

  @Override
  public boolean onCommand(
      UUID senderId,
      Map<String, List<String>> parameterValues,
      CommandsAPICommand nextCommand,
      java.util.function.Consumer<String> messageMethod) {

    if (nextCommand != null) return true;

    boolean valid = true;
    CommandSender sender =
        senderId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)
            ? Bukkit.getConsoleSender()
            : Bukkit.getPlayer(senderId);
    if (sender == null) {

        return false;
    }

    for (Predicate<CommandSender> commandSenderPredicate : senderChecks) {
      valid &= commandSenderPredicate.test(sender);
    }
    if (!valid) {

      return false;
    }

    // Route the messageMethod Consumer through SendMessage so any raw template
    // it receives from RTPCmd.compute is formatted (placeholders, '&' colour
    // codes, hex tokens, PAPI) before reaching the player/console. This keeps
    // formatting at the platform boundary and out of rtp-core. See REQ-RTP-F-013
    // and the AGENTS.md "Color handling" guidance.
    final CommandSender finalSender = sender;
    // Pattern: lines emitted by commands-api's built-in TreeCommand#help()
    // for each subcommand look like
    //     "  - /<full command> <subname>\n    <description>"
    // (TreeCommand.java lines 422-425). Detect those and render them via
    // SendMessage's hover/click overload so /rtp help rows remain clickable
    // (SUGGEST_COMMAND) the way the removed HelpCmd used to render them.
    // Non-matching lines (root "Command:" header, "Subcommands:" /
    // "Parameters:" section labels, parameter rows) fall through to the
    // plain-string sink.
    final java.util.regex.Pattern helpSubcommandLine =
        java.util.regex.Pattern.compile("^\\s+-\\s+(/\\S[^\\r\\n]*?)\\s*(?:\\R[\\s\\S]*)?$");
    final io.github.dailystruggle.rtp.api.entity.RTPCommandSender rtpSender =
        RTP.serverAccessor.getSender(senderId);
    java.util.function.Consumer<String> wrapped =
        msg -> {
          if (msg != null && rtpSender != null) {
            java.util.regex.Matcher m = helpSubcommandLine.matcher(msg);
            if (m.matches()) {
              String click = m.group(1).trim();
              io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage
                  .sendMessage(rtpSender, msg, click, click);
              return;
            }
          }
          io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.sendMessage(finalSender, msg);
        };

    return compute(senderId, parameterValues, nextCommand, wrapped);
  }

  @Override
  public boolean onCommand(
      CommandSender sender,
      Map<String, List<String>> parameterValues,
      CommandsAPICommand nextCommand) {

    if (nextCommand != null) return true;

    boolean valid = true;
    for (Predicate<CommandSender> commandSenderPredicate : senderChecks) {
      valid &= commandSenderPredicate.test(sender);
    }
    if (!valid) {

      return false;
    }
    UUID uuid =
        sender instanceof Player
            ? ((Player) sender).getUniqueId()
            : io.github.dailystruggle.rtp.api.RTPAPI.serverId;
    return compute(uuid, parameterValues, nextCommand); // todo:async
  }

  @Override
  public void successEvent(RTPCommandSender sender, RTPPlayer player) {
    TeleportCommandSuccessEvent event = new TeleportCommandSuccessEvent(sender, player);
    Bukkit.getPluginManager().callEvent(event);
  }

  @Override
  public void failEvent(RTPCommandSender sender, String msg) {
    TeleportCommandFailEvent event = new TeleportCommandFailEvent(sender, msg);
    Bukkit.getPluginManager().callEvent(event);
  }
}
