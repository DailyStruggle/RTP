package io.github.dailystruggle.effectsapi.LocalEffects;

import io.github.dailystruggle.effectsapi.Effect;
import io.github.dailystruggle.effectsapi.LocalEffects.enums.NoteTypeNames;
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

        Sound sound = Sound.valueOf("BLOCK_NOTE_PLING");
        switch (instrument) {
            case BASS_DRUM:
                sound = Sound.valueOf("BLOCK_NOTE_BASEDRUM");
                break;
            case SNARE_DRUM:
                sound = Sound.valueOf("BLOCK_NOTE_SNARE");
                break;
            case BASS_GUITAR:
                sound = Sound.valueOf("BLOCK_NOTE_BASS");
                break;
            case FLUTE:
                sound = Sound.valueOf("BLOCK_NOTE_FLUTE");
                break;
            case BELL:
                sound = Sound.valueOf("BLOCK_NOTE_BELL");
                break;
            case GUITAR:
                sound = Sound.valueOf("BLOCK_NOTE_GUITAR");
                break;
            case CHIME:
                sound = Sound.valueOf("BLOCK_NOTE_CHIME");
                break;
            case XYLOPHONE:
                sound = Sound.valueOf("BLOCK_NOTE_XYLOPHONE");
                break;
        }

        Objects.requireNonNull(location.getWorld()).playSound(location,sound,1.0f,noteNumber);
    }

    @Override
    public void setData(String... data) {
        if(data.length>0) this.data.put(NoteTypeNames.TYPE, data[0]);
        if(data.length>1) this.data.put(NoteTypeNames.TONE, data[1]);
        this.data = fixData(this.data);
    }

    @Override
    public String toPermission() {
        return this.data.get(NoteTypeNames.TYPE).toString().replaceAll("\\.*", "") +
                this.data.get(NoteTypeNames.TONE).toString().replaceAll("\\.*", "");
    }
}
