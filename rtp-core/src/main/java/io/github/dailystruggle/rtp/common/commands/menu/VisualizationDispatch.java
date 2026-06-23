package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.common.commands.maps.MapDispatch;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
/**
 * Self-contained dispatcher for the {@code /rtp visualization &lt;kind&gt;}
 * sub-command family. Owns nothing menu/cart-related: it is intentionally
 * decoupled from {@link MenuRedeemSubcommand} so that visualization wiring
 * does not accrete onto the legacy redeem god-class (which the ADR-050
 * Stage 3β cleanup did not split). New visualization kinds belong here, not
 * on the redeem subcommand.
 *
 * <p>Each entry point:
 * <ol>
 *   <li>Gates on {@link MenuPermissionGates#ADMIN_MENU_PERMISSION} (chart
 *       data surfaces admin-facing internals). Denial logs WARN and rejects
 *       through {@link CommandMessages#menuInvalid} (S-004).</li>
 *   <li>Builds a {@link ChartSpec} from {@code (kind, regionName)} inline.
 *       Invalid inputs reject through {@code menuInvalid}.</li>
 *   <li>Hands off to {@link MapDispatch#paint(ChartSpec, UUID)}, which owns
 *       the configurable viewer-facing failure surfaces
 *       ({@link CommandMessages#mapBindingMissing} /
 *       {@code mapResolverMissing} / {@code mapUnavailable} /
 *       {@code mapBusy}).</li>
 * </ol>
 *
 * <p>Package-private: only the visualization leaves in
 * {@link MenuConcreteCommandLeaves} construct one.
 */
final class VisualizationDispatch {

    private final MenuPermissionGates permissionGates;

    VisualizationDispatch(Function<UUID, Predicate<String>> probeFactory) {
        Objects.requireNonNull(probeFactory, "probeFactory");
        this.permissionGates = new MenuPermissionGates(probeFactory);
    }

    /**
     * Dispatch for {@code /rtp visualization bad-locations region=&lt;name&gt;}:
     * paints the per-region {@link ChartSpec.Kind#REGION_BAD_LOCATIONS_SHAPE}
     * chart through {@link MapDispatch}. Returns {@code true} on a successful
     * paint, {@code false} on every reject path (caller has already been
     * notified through the configurable channel).
     */
    boolean paintBadLocations(UUID viewer,
                              String regionName,
                              @Nullable Consumer<String> messageMethod) {
        RTP.log(Level.FINE,
                "[viz/bad-locations] dispatch entry: viewer=" + viewer
                        + " region=" + regionName);
        if (viewer == null) {
            RTP.log(Level.WARNING,
                    "visualization bad-locations rejected: null viewer");
            return false;
        }
        if (regionName == null || regionName.isEmpty()) {
            reject(viewer, "visualization bad-locations rejected: empty regionName",
                    messageMethod);
            return false;
        }
        if (!permissionGates.hasAdminMenu(viewer)) {
            RTP.log(Level.WARNING,
                    "visualization bad-locations denied: " + viewer
                            + " lacks " + MenuPermissionGates.ADMIN_MENU_PERMISSION);
            reject(viewer, "visualization bad-locations rejected: permission denied",
                    messageMethod);
            return false;
        }
        ChartSpec spec;
        try {
            spec = ChartSpec.of(ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE, regionName);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "visualization bad-locations rejected: invalid ChartSpec (region='"
                            + regionName + "') for " + viewer + ": " + e.getMessage(), e);
            reject(viewer, "visualization bad-locations rejected: invalid ChartSpec",
                    messageMethod);
            return false;
        }
        try {
            // MapDispatch.paint owns the configurable viewer-facing failure
            // surfaces; do not double-message on its false return.
            RTP.log(Level.FINE,
                    "[viz/bad-locations] handing off to MapDispatch.paint for "
                            + viewer + " region=" + regionName);
            boolean ok = MapDispatch.paint(spec, viewer);
            RTP.log(Level.FINE,
                    "[viz/bad-locations] MapDispatch.paint returned " + ok
                            + " for viewer=" + viewer);
            return ok;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "visualization bad-locations MapDispatch.paint threw for " + viewer
                            + ": " + e.getMessage(), e);
            reject(viewer, "visualization bad-locations rejected: dispatch failure",
                    messageMethod);
            return false;
        }
    }

    /**
     * Dispatch for {@code /rtp visualization biomes region=&lt;name&gt;}:
     * paints the per-region {@link ChartSpec.Kind#REGION_BIOMES} chart
     * through {@link MapDispatch}. Mirrors {@link #paintBadLocations} in
     * every respect except the chart kind; the gate, validation, and
     * failure-message surfaces are identical.
     */
    boolean paintBiomes(UUID viewer,
                        String regionName,
                        @Nullable Consumer<String> messageMethod) {
        RTP.log(Level.FINE,
                "[viz/biomes] dispatch entry: viewer=" + viewer
                        + " region=" + regionName);
        if (viewer == null) {
            RTP.log(Level.WARNING,
                    "visualization biomes rejected: null viewer");
            return false;
        }
        if (regionName == null || regionName.isEmpty()) {
            reject(viewer, "visualization biomes rejected: empty regionName",
                    messageMethod);
            return false;
        }
        if (!permissionGates.hasAdminMenu(viewer)) {
            RTP.log(Level.WARNING,
                    "visualization biomes denied: " + viewer
                            + " lacks " + MenuPermissionGates.ADMIN_MENU_PERMISSION);
            reject(viewer, "visualization biomes rejected: permission denied",
                    messageMethod);
            return false;
        }
        ChartSpec spec;
        try {
            spec = ChartSpec.of(ChartSpec.Kind.REGION_BIOMES, regionName);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "visualization biomes rejected: invalid ChartSpec (region='"
                            + regionName + "') for " + viewer + ": " + e.getMessage(), e);
            reject(viewer, "visualization biomes rejected: invalid ChartSpec",
                    messageMethod);
            return false;
        }
        try {
            RTP.log(Level.FINE,
                    "[viz/biomes] handing off to MapDispatch.paint for "
                            + viewer + " region=" + regionName);
            boolean ok = MapDispatch.paint(spec, viewer);
            RTP.log(Level.FINE,
                    "[viz/biomes] MapDispatch.paint returned " + ok
                            + " for viewer=" + viewer);
            return ok;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "visualization biomes MapDispatch.paint threw for " + viewer
                            + ": " + e.getMessage(), e);
            reject(viewer, "visualization biomes rejected: dispatch failure",
                    messageMethod);
            return false;
        }
    }

    /**
     * Dispatch for {@code /rtp visualization sparkline}: paints the
     * global {@link ChartSpec.Kind#METRIC_SPARKLINE} chart (MSPT + heap)
     * through {@link MapDispatch}. No region; the resolver ignores
     * {@code ChartSpec#regionName}.
     */
    boolean paintSparkline(UUID viewer, @Nullable Consumer<String> messageMethod) {
        RTP.log(Level.FINE,
                "[viz/sparkline] dispatch entry: viewer=" + viewer);
        if (viewer == null) {
            RTP.log(Level.WARNING, "visualization sparkline rejected: null viewer");
            return false;
        }
        if (!permissionGates.hasAdminMenu(viewer)) {
            RTP.log(Level.WARNING,
                    "visualization sparkline denied: " + viewer
                            + " lacks " + MenuPermissionGates.ADMIN_MENU_PERMISSION);
            reject(viewer, "visualization sparkline rejected: permission denied",
                    messageMethod);
            return false;
        }
        ChartSpec spec;
        try {
            // regionName is required non-null by ChartSpec; pass empty
            // string since the resolver ignores it.
            spec = ChartSpec.of(ChartSpec.Kind.METRIC_SPARKLINE, "");
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "visualization sparkline rejected: invalid ChartSpec for "
                            + viewer + ": " + e.getMessage(), e);
            reject(viewer, "visualization sparkline rejected: invalid ChartSpec",
                    messageMethod);
            return false;
        }
        try {
            RTP.log(Level.FINE,
                    "[viz/sparkline] handing off to MapDispatch.paint for " + viewer);
            boolean ok = MapDispatch.paint(spec, viewer);
            RTP.log(Level.FINE,
                    "[viz/sparkline] MapDispatch.paint returned " + ok
                            + " for viewer=" + viewer);
            return ok;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "visualization sparkline MapDispatch.paint threw for " + viewer
                            + ": " + e.getMessage(), e);
            reject(viewer, "visualization sparkline rejected: dispatch failure",
                    messageMethod);
            return false;
        }
    }

    /**
     * S-004 reject through {@link CommandMessages#menuInvalid}. Logs WARN
     * unconditionally; the viewer-facing message goes through the supplied
     * {@code messageMethod} when present, falling back to
     * {@link RTP#serverAccessor}. Mirrors {@code MenuRedeemSubcommand#reject}
     * but does not depend on it.
     */
    private static void reject(UUID viewer,
                               String logMessage,
                               @Nullable Consumer<String> messageMethod) {
        RTP.log(Level.WARNING, logMessage);
        String userMsg = resolveMessage(CommandMessages.menuInvalid, "Invalid menu command.");
        if (messageMethod != null) {
            messageMethod.accept(userMsg);
        } else if (RTP.serverAccessor != null) {
            RTP.serverAccessor.sendMessage(RTPAPI.serverId, viewer, userMsg, null);
        }
    }

    /**
     * Reads the configurable value for {@code key} from the active
     * {@code MessagesKeys} parser, falling back to {@code defaultValue} when
     * the config is not available (e.g. during early bootstrap or in unit
     * tests). Mirrors {@code BaseRTPCmd#msg} without inheriting from it.
     */
    @SuppressWarnings("unchecked")
    private static String resolveMessage(Enum<?> key, String defaultValue) {
        try {
            if (RTP.configs == null) return defaultValue;
            Object value = RTP.configs.getConfigValue(key, defaultValue);
            return value == null ? defaultValue : value.toString();
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }
}
