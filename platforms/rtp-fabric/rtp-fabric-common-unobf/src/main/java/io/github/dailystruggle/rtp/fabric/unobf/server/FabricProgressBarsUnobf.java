package io.github.dailystruggle.rtp.fabric.unobf.server;

import io.github.dailystruggle.rtp.api.server.ProgressBar;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Vanilla {@link ServerBossEvent} renderer for {@code RTPServerAccessor} progress bars on the
 * deobf MC 26.x runtime family. Sibling of {@code NeoForgeProgressBars}; lives in the unobf
 * carrier ({@code rtp-fabric-common-unobf}, Loom-built with no {@code mappings} line) so its
 * {@code net.minecraft.*} constant-pool entries are mojmap names that link cleanly on the
 * deobf 26.x runtime, where the obf carrier's intermediary-remapped {@code ServerBossEvent}
 * calls in {@code FabricServerAccessor} fail to resolve and silently no-op.
 *
 * <p>Invoked from the per-version adapter ({@code V26_x_R1FabricVersionAdapter}) via
 * {@code FabricVersionAdapter#dispatchProgressBars}. All state is held here and accessed only
 * on the server thread (the core {@code ScanProgressBars} driver runs on the main/server-thread
 * timer). Progress-bar feedback is non-essential, so this never throws S-004-relevant failures.
 */
public final class FabricProgressBarsUnobf {
    /** Active boss-bars keyed by caller-chosen id. Accessed only on the server thread. */
    private final Map<String, ServerBossEvent> activeProgressBars = new HashMap<>();

    /**
     * Reconciles the displayed bars against {@code bars}.
     *
     * @param server          the running server (for player lookup)
     * @param eligibleViewers maps a viewer-permission string to the set of online uuids that
     *                        may see the bar (blank/{@code null} permission = everyone)
     * @param bars            desired bar state keyed by stable id
     */
    public void update(MinecraftServer server,
                       Function<String, Set<UUID>> eligibleViewers,
                       Map<String, ProgressBar> bars) {
        if (server == null || bars == null || bars.isEmpty()) {
            clear();
            return;
        }

        // Hide bars that are no longer requested.
        Set<String> stale = new HashSet<>();
        for (String id : activeProgressBars.keySet()) {
            if (!bars.containsKey(id)) stale.add(id);
        }
        for (String id : stale) {
            ServerBossEvent bar = activeProgressBars.remove(id);
            if (bar != null) bar.removeAllPlayers();
        }

        for (Map.Entry<String, ProgressBar> entry : bars.entrySet()) {
            String id = entry.getKey();
            ProgressBar spec = entry.getValue();
            if (spec == null) continue;

            BossEvent.BossBarColor color = barColorFromTemplate(spec.title());
            String title = sanitizeBarTitle(spec.title());
            float progress = (float) Math.max(0.0, Math.min(1.0, spec.progress()));

            ServerBossEvent bar = activeProgressBars.get(id);
            if (bar == null) {
                bar = new ServerBossEvent(UUID.randomUUID(), Component.literal(title), color, BossEvent.BossBarOverlay.PROGRESS);
                activeProgressBars.put(id, bar);
            } else {
                bar.setName(Component.literal(title));
                bar.setColor(color);
            }
            bar.setProgress(progress);

            // Reconcile visible players against the bar's viewer permission.
            Set<UUID> eligibleIds = eligibleViewers.apply(spec.viewerPermission());
            Set<ServerPlayer> eligible = new HashSet<>();
            for (UUID uuid : eligibleIds) {
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) eligible.add(sp);
            }
            Set<ServerPlayer> current = new HashSet<>(bar.getPlayers());
            for (ServerPlayer sp : eligible) {
                if (!current.contains(sp)) bar.addPlayer(sp);
            }
            for (ServerPlayer sp : current) {
                if (!eligible.contains(sp)) bar.removePlayer(sp);
            }
        }
    }

    /** Hides and discards every active bar. */
    public void clear() {
        for (ServerBossEvent bar : activeProgressBars.values()) {
            bar.removeAllPlayers();
        }
        activeProgressBars.clear();
    }

    /**
     * Strips legacy {@code &x} color codes and {@code #RRGGBB} hex codes from a bar title
     * (boss-bar titles render as plain text) and truncates to a sane length.
     */
    private static String sanitizeBarTitle(String title) {
        if (title == null) return "";
        String out = title.replaceAll("&[0-9a-fA-FklmnorKLMNOR]", "").replaceAll("#[0-9a-fA-F]{6}", "");
        return out.length() > 64 ? out.substring(0, 64) : out;
    }

    /**
     * Maps the first legacy color code ({@code &x}) found in {@code template} to a vanilla
     * {@link BossEvent.BossBarColor}. Returns {@code GREEN} when none is found.
     */
    private static BossEvent.BossBarColor barColorFromTemplate(String template) {
        if (template == null) return BossEvent.BossBarColor.GREEN;
        for (int i = 0; i + 1 < template.length(); i++) {
            if (template.charAt(i) != '&') continue;
            char c = Character.toLowerCase(template.charAt(i + 1));
            switch (c) {
                case '4':
                case 'c':
                    return BossEvent.BossBarColor.RED;
                case '6':
                case 'e':
                    return BossEvent.BossBarColor.YELLOW;
                case '2':
                case 'a':
                    return BossEvent.BossBarColor.GREEN;
                case '1':
                case '3':
                case '9':
                case 'b':
                    return BossEvent.BossBarColor.BLUE;
                case '5':
                    return BossEvent.BossBarColor.PURPLE;
                case 'd':
                    return BossEvent.BossBarColor.PINK;
                case 'f':
                case '7':
                case '8':
                case '0':
                    return BossEvent.BossBarColor.WHITE;
                default:
                    break;
            }
        }
        return BossEvent.BossBarColor.GREEN;
    }
}
