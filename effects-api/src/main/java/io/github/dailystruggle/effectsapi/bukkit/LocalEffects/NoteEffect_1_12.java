package io.github.dailystruggle.effectsapi.bukkit.LocalEffects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.enums.NoteTypeNames;
import org.bukkit.Instrument;
import org.bukkit.Location;
import org.bukkit.Note;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class NoteEffect_1_12 extends Effect<NoteTypeNames> {
    private BukkitTask makeNoteSoundTask = null;

    public NoteEffect_1_12() throws IllegalArgumentException {
        super(new EnumMap<>(NoteTypeNames.class));
        EnumMap<NoteTypeNames, Object> data = getData();
        data.put(NoteTypeNames.TYPE, Instrument.PIANO);
        data.put(NoteTypeNames.TONE, 0);
        this.data = data;
        this.defaults = data.clone();
    }

    /**
     * this is technically a runnable, so a run function needs to be filled out for what to do.
     * In this case,
     */
    @Override
    public void run() {
        if (target instanceof Entity) target = ((Entity) target).getLocation();
        Location location = (Location) target;

        Object o = this.data.get(NoteTypeNames.TYPE);
        if(o == null) return;

        Instrument instrument;
        if(o instanceof Instrument) instrument = (Instrument) o;
        else {
            try {
                instrument = Instrument.valueOf(o.toString());
            } catch (IllegalArgumentException e) {
                return;
            }
        }

        float noteNumber;
        o = data.get(NoteTypeNames.TONE);
        if(o instanceof Number) {
            noteNumber = ((Number) o).floatValue();
        }
        else {
            try {
                noteNumber = Integer.parseInt(o.toString());
            } catch (IllegalArgumentException e) {
                return;
            }
        }

        Sound sound = resolveNoteSound(instrument);
        if (sound == null) return;

        Objects.requireNonNull(location.getWorld()).playSound(location,sound,1.0f,noteNumber);
    }

    private static Sound resolveNoteSound(Instrument instrument) {
        String suffix;
        switch (instrument) {
            case BASS_DRUM: suffix = "BASEDRUM"; break;
            case SNARE_DRUM: suffix = "SNARE"; break;
            case BASS_GUITAR: suffix = "BASS"; break;
            case FLUTE: suffix = "FLUTE"; break;
            case BELL: suffix = "BELL"; break;
            case GUITAR: suffix = "GUITAR"; break;
            case CHIME: suffix = "CHIME"; break;
            case XYLOPHONE: suffix = "XYLOPHONE"; break;
            default: suffix = "PLING"; break;
        }
        // Try modern Folia/Paper enum name first (BLOCK_NOTE_BLOCK_*), then legacy (BLOCK_NOTE_*).
        Sound s = tryValueOf("BLOCK_NOTE_BLOCK_" + suffix);
        if (s == null) s = tryValueOf("BLOCK_NOTE_" + suffix);
        return s;
    }

    private static Sound tryValueOf(String name) {
        try {
            return Sound.valueOf(name);
        } catch (IllegalArgumentException | NoSuchFieldError e) {
            return null;
        }
    }

    @Override
    public void setData(String... data) {
        applyByType(KEY_ORDER, data);
    }

    private static final NoteTypeNames[] KEY_ORDER = { NoteTypeNames.TYPE, NoteTypeNames.TONE };

    @Override
    public String toPermission() {
        return this.data.get(NoteTypeNames.TYPE).toString().replaceAll("\\.*", "") +
                this.data.get(NoteTypeNames.TONE).toString().replaceAll("\\.*", "");
    }
}
