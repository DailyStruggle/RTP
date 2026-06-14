package io.github.dailystruggle.effectsapi.common.effects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.spi.HandleRegistry;
import io.github.dailystruggle.effectsapi.common.spi.LocationHandle;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;

import java.util.EnumMap;

/**
 * Common note effect implementation.
 *
 * <p>This effect is platform-neutral: it resolves a {@link PlayerHandle} (or a
 * {@link LocationHandle}) for its target and delegates the note emission to
 * {@link PlayerHandle#playNote}/{@link LocationHandle#playNote}. {@code playNote}
 * is a clientbound packet that does not require a real note block at the
 * location, so the historical place/restore-a-{@code NOTE_BLOCK} dance (which
 * tripped Paper's AsyncCatcher and Folia's region ownership checks) is gone and
 * the sound is now emitted directly from {@link #run()}.
 *
 * <p>The platform-typed instrument default ({@code TYPE}) is injected by the
 * platform initializer via the constructor, mirroring {@link SoundEffect}.
 */
public class NoteEffect extends Effect<NoteEffect.NoteKeys> {

    /** Positional / type-driven keys for {@code NoteEffect}. */
    public enum NoteKeys {
        TYPE,
        TONE
    }

    /**
     * @param defaultInstrument platform instrument default (e.g. Bukkit
     *                          {@code Instrument.PIANO}).
     */
    public NoteEffect(Object defaultInstrument) {
        super(new EnumMap<>(NoteKeys.class));
        EnumMap<NoteKeys, Object> data = getData();
        data.put(NoteKeys.TYPE, defaultInstrument);
        data.put(NoteKeys.TONE, 0);
        this.data = data;
        this.defaults = data.clone();
    }

    @Override
    public void run() {
        PlayerHandle ph = HandleRegistry.wrapPlayer(target);
        LocationHandle lh = (ph == null) ? HandleRegistry.wrapLocation(target) : null;

        int tone = 0;
        Object o = data.get(NoteKeys.TONE);
        if (o instanceof Number) tone = ((Number) o).intValue();
        Object instrument = data.get(NoteKeys.TYPE);

        if (ph != null) {
            ph.playNote(instrument, tone);
        } else if (lh != null) {
            lh.playNote(instrument, tone);
        }
    }

    private static final NoteKeys[] KEY_ORDER = { NoteKeys.TYPE, NoteKeys.TONE };

    @Override
    public void setData(String... data) {
        applyByType(KEY_ORDER, data);
    }

    @Override
    public String toPermission() {
        return this.data.get(NoteKeys.TYPE).toString().replaceAll("\\.*", "") +
                this.data.get(NoteKeys.TONE).toString().replaceAll("\\.*", "");
    }
}
