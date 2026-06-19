package io.github.dailystruggle.rtp.neoforge.server;

import io.github.dailystruggle.rtp.api.server.ProgressBar;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Vanilla {@link ServerBossEvent} renderer for {@code RTPServerAccessor} progress bars on
 * NeoForge. Split out of {@link NeoForgeServerAccessor} so the accessor itself never
 * references a Minecraft client-UI class ({@link Component}) at verify time: the accessor is
 * exercised by pure unit tests whose classpath omits those classes, and HotSpot would
 * otherwise {@code NoClassDefFoundError} on construction. This helper is loaded lazily, only
 * when a bar is actually rendered on a live server.
 *
 * <p>NeoForge ships Mojang-mapped names at runtime, so these typed calls bind directly with
 * no reflective dispatch.
 */
final class NeoForgeProgressBars {
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
    void update(MinecraftServer server,
                Function<String, Set<UUID>> eligibleViewers,
                Map<String, ProgressBar> bars) {
        if (bars == null || bars.isEmpty()) {
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
                bar = newServerBossEvent(Component.literal(title), color, BossEvent.BossBarOverlay.PROGRESS);
                if (bar == null) continue;
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

    /**
     * Resolved {@link ServerBossEvent} constructor, cached after first lookup. The constructor
     * signature drifts across MC revisions (the 1.21.x line the common module typechecks against
     * exposes {@code (Component, BossBarColor, BossBarOverlay)}; later lines such as MC 26.1 differ),
     * so the bytecode must not hard-link any single shape. {@code null} means "not yet resolved".
     */
    private static volatile Constructor<ServerBossEvent> resolvedCtor;
    /** Pre-built argument template for {@link #resolvedCtor}; the {@code Component} slot is filled per call. */
    private static volatile Object[] ctorArgTemplate;
    /** Index of the {@link Component} parameter within {@link #resolvedCtor}, or -1 if none. */
    private static volatile int ctorNameIndex = -1;
    /** Set once a resolution attempt has run (so a confirmed failure is not retried every tick). */
    private static volatile boolean ctorResolutionAttempted;

    /**
     * Constructs a {@link ServerBossEvent} tolerant of cross-version constructor drift. Resolves
     * (and caches) a public constructor by reflection: it accepts any constructor that exposes a
     * {@link Component}, a {@link BossEvent.BossBarColor} and a {@link BossEvent.BossBarOverlay}
     * parameter, filling any remaining parameters with sensible defaults ({@link UUID#randomUUID()}
     * for {@code UUID}, {@code 0f} for {@code float}, {@code false} for {@code boolean}, {@code null}
     * otherwise). This covers both the {@code (Component, BossBarColor, BossBarOverlay)} shape
     * (MC 1.21.x) and reordered / extended shapes on later lines such as MC 26.1. Returns
     * {@code null} if no usable constructor exists, so the caller can skip the bar rather than
     * crash the server tick.
     */
    @SuppressWarnings("unchecked")
    private static ServerBossEvent newServerBossEvent(Component name,
                                                      BossEvent.BossBarColor color,
                                                      BossEvent.BossBarOverlay overlay) {
        if (resolvedCtor == null && !ctorResolutionAttempted) {
            synchronized (NeoForgeProgressBars.class) {
                if (resolvedCtor == null && !ctorResolutionAttempted) {
                    Constructor<?> best = null;
                    Object[] bestArgs = null;
                    int bestNameIndex = -1;
                    int bestExtras = Integer.MAX_VALUE;
                    for (Constructor<?> c : ServerBossEvent.class.getDeclaredConstructors()) {
                        Class<?>[] p = c.getParameterTypes();
                        boolean hasName = false, hasColor = false, hasOverlay = false;
                        int nameIndex = -1;
                        int extras = 0;
                        Object[] args = new Object[p.length];
                        for (int i = 0; i < p.length; i++) {
                            Class<?> t = p[i];
                            if (t == Component.class && !hasName) {
                                hasName = true;
                                nameIndex = i;
                            } else if (t == BossEvent.BossBarColor.class && !hasColor) {
                                hasColor = true;
                                args[i] = color;
                            } else if (t == BossEvent.BossBarOverlay.class && !hasOverlay) {
                                hasOverlay = true;
                                args[i] = overlay;
                            } else if (t == UUID.class) {
                                args[i] = UUID.randomUUID();
                                extras++;
                            } else if (t == float.class) {
                                args[i] = 0f;
                                extras++;
                            } else if (t == boolean.class) {
                                args[i] = false;
                                extras++;
                            } else if (t.isPrimitive()) {
                                // Unsupported primitive (int/long/etc.) -- skip this constructor.
                                extras = Integer.MAX_VALUE;
                                break;
                            } else {
                                args[i] = null;
                                extras++;
                            }
                        }
                        if (hasName && hasColor && hasOverlay && extras < bestExtras) {
                            best = c;
                            bestArgs = args;
                            bestNameIndex = nameIndex;
                            bestExtras = extras;
                        }
                    }
                    if (best != null) {
                        best.setAccessible(true);
                        resolvedCtor = (Constructor<ServerBossEvent>) best;
                        ctorArgTemplate = bestArgs;
                        ctorNameIndex = bestNameIndex;
                    }
                    ctorResolutionAttempted = true;
                }
            }
        }
        Constructor<ServerBossEvent> ctor = resolvedCtor;
        if (ctor == null) return null;
        try {
            Object[] args = ctorArgTemplate.clone();
            args[ctorNameIndex] = name;
            // Give every bar a fresh UUID; boss bars are keyed by UUID on the client.
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof UUID) args[i] = UUID.randomUUID();
            }
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Hides and discards every active bar. */
    void clear() {
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
