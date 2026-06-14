package io.github.dailystruggle.effectsapi.common.effects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.spi.HandleRegistry;
import io.github.dailystruggle.effectsapi.common.spi.LocationHandle;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;
import java.util.EnumMap;

/**
 * Post-teleport glide effect (effects-api-ADR-001).
 *
 * <p>Lifts the player by {@code RELATIVE} blocks (clamped to {@code MAX}),
 * starts gliding, fires the platform glide event, and arms a watchdog that
 * forces a landing after {@code LANDINGTIMEOUT} ticks.
 *
 * <p>This effect is platform-neutral: it resolves a {@link PlayerHandle} for
 * its target and delegates the glide lifecycle to
 * {@link PlayerHandle#startGlide}. All platform-specific work (teleport,
 * events, safety listener, scheduling) lives in the handle implementation, so
 * the native player object never leaks into this class.
 */
public class GlideEffect extends Effect<GlideEffect.GlideKeys> {

    /**
     * Positional / type-driven keys for {@code GlideEffect}.
     *
     * <p>Order is the legacy positional order consumed by
     * {@code Effect#setData(String...)}; type-driven adaptive parsing
     * (effects-api-ADR-002) further allows skipped earlier keys to be filled by
     * later tokens.
     */
    public enum GlideKeys {
        /** Block used to synthesize an emergency platform on shutdown when no safe block is below the player. */
        SHUTDOWNPLATFORMMATERIAL,
        /** Blocks above the player's current Y to lift them when glide starts. */
        RELATIVE,
        /** Hard ceiling on the lift target Y. */
        MAX,
        /** Maximum duration of the glide in ticks before forced landing. */
        LANDINGTIMEOUT,
        /** If false, firework rockets are suppressed for the duration of the glide. */
        ALLOWFIREWORKS,
        /** If true, on shutdown still-gliding players are placed at the highest safe block below them. */
        PLACEONSHUTDOWN,
        /** World allow-list sentinel ("*" or a specific world name). */
        WORLD
    }

    public GlideEffect() {
        super(new EnumMap<>(GlideKeys.class));
        EnumMap<GlideKeys, Object> data = getData();
        data.put(GlideKeys.SHUTDOWNPLATFORMMATERIAL, "STONE");
        data.put(GlideKeys.RELATIVE, 75);
        data.put(GlideKeys.MAX, 320);
        data.put(GlideKeys.LANDINGTIMEOUT, Integer.MAX_VALUE);
        data.put(GlideKeys.ALLOWFIREWORKS, false);
        data.put(GlideKeys.PLACEONSHUTDOWN, true);
        data.put(GlideKeys.WORLD, "*");
        this.data = data;
        this.defaults = data.clone();
    }

    @Override
    public void run() {
        // Resolve a platform-neutral player handle for the target. Prefer a
        // direct player/entity target; otherwise recover the player standing at
        // the target location. The native player object stays inside the handle.
        PlayerHandle ph = HandleRegistry.wrapPlayer(target);
        if (ph == null) {
            LocationHandle lh = HandleRegistry.wrapLocation(target);
            if (lh != null) ph = HandleRegistry.playerAt(lh);
        }
        if (ph == null) return;

        int relative = intOf(GlideKeys.RELATIVE, 75);
        int max = intOf(GlideKeys.MAX, 320);
        int timeout = intOf(GlideKeys.LANDINGTIMEOUT, Integer.MAX_VALUE);
        boolean allowFireworks = boolOf(GlideKeys.ALLOWFIREWORKS, false);
        boolean placeOnShutdown = boolOf(GlideKeys.PLACEONSHUTDOWN, true);
        Object rawMat = data.get(GlideKeys.SHUTDOWNPLATFORMMATERIAL);
        String platformMat = rawMat != null ? rawMat.toString() : null;

        ph.startGlide(relative, max, timeout, allowFireworks, placeOnShutdown, platformMat);
    }

    private int intOf(GlideKeys key, int fallback) {
        Object o = data.get(key);
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) {
            try { return Integer.parseInt((String) o); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private boolean boolOf(GlideKeys key, boolean fallback) {
        Object o = data.get(key);
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof String) return Boolean.parseBoolean((String) o);
        return fallback;
    }

    @Override
    public void setData(String... data) {
        applyByType(KEY_ORDER, data);
    }

    private static final GlideKeys[] KEY_ORDER = {
            GlideKeys.SHUTDOWNPLATFORMMATERIAL,
            GlideKeys.RELATIVE,
            GlideKeys.MAX,
            GlideKeys.LANDINGTIMEOUT,
            GlideKeys.ALLOWFIREWORKS,
            GlideKeys.PLACEONSHUTDOWN,
            GlideKeys.WORLD
    };

    @Override
    public String toPermission() {
        StringBuilder sb = new StringBuilder();
        sb.append(this.data.get(GlideKeys.SHUTDOWNPLATFORMMATERIAL)).append('.');
        sb.append(this.data.get(GlideKeys.RELATIVE)).append('.');
        sb.append(this.data.get(GlideKeys.MAX)).append('.');
        sb.append(this.data.get(GlideKeys.LANDINGTIMEOUT)).append('.');
        sb.append(this.data.get(GlideKeys.ALLOWFIREWORKS)).append('.');
        sb.append(this.data.get(GlideKeys.PLACEONSHUTDOWN)).append('.');
        sb.append(this.data.get(GlideKeys.WORLD));
        return sb.toString();
    }
}
