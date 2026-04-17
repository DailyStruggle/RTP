package io.github.dailystruggle.effectsapi.LocalEffects;

import io.github.dailystruggle.effectsapi.Effect;
import io.github.dailystruggle.effectsapi.LocalEffects.enums.FireworkTypeNames;
import io.github.dailystruggle.effectsapi.LocalEffects.enums.NoteTypeNames;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.NoteBlock;
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

public class NoteEffect extends Effect<NoteTypeNames> {
    private BukkitTask makeNoteSoundTask = null;
    private BukkitTask cleanupTask = null;
    private BlockData oldBlockData;

    public NoteEffect() throws IllegalArgumentException {
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
        NoteBlock noteData = (NoteBlock) Bukkit.createBlockData(Material.NOTE_BLOCK);
        noteData.setInstrument((Instrument) data.get(NoteTypeNames.TYPE));
        noteData.setNote(new Note((Integer) data.get(NoteTypeNames.TONE)));

        Block block = ((Location) target).getBlock();
        oldBlockData = block.getBlockData().clone();
        block.setBlockData(noteData);
    }

    @Override
    @NotNull
    public synchronized BukkitTask runTask(@NotNull Plugin plugin) {
        BukkitTask res = super.runTask(plugin);

        //delay 1 tick to ensure note block placement on client side
        makeNoteSoundTask = new MakeNoteSound().runTaskLater(plugin, 1);

        //delay 1 more tick to ensure note plays before removal
        cleanupTask = new Cleanup().runTaskLater(plugin, 2);
        return res;
    }

    @Override
    public void cancel() {
        if (makeNoteSoundTask != null) makeNoteSoundTask.cancel();
        if (cleanupTask != null) cleanupTask.cancel();
    }

    @Override
    public void setData(String... data) {
        if(data.length>0) this.data.put(NoteTypeNames.TYPE, data[0]);
        if(data.length>1) this.data.put(NoteTypeNames.TONE, data[1]);
        this.data = fixData(this.data);
    }

    private final class MakeNoteSound extends BukkitRunnable {
        @Override
        public void run() {
            if(target instanceof Entity) target = ((Entity) target).getLocation();
            Location location = ((Location) target);

            int tone = 0;
            Object o = data.get(NoteTypeNames.TONE);
            if(o instanceof Number) tone = ((Number) o).intValue();

            List<Player> players = Objects.requireNonNull(location.getWorld()).getPlayers()
                    .parallelStream().filter(player -> (player.getLocation().distance(location) < 48))
                    .collect(Collectors.toList());
            for (Player player : players) {
                player.playNote(location,
                        (Instrument) data.get(NoteTypeNames.TYPE),
                        new Note(tone));
            }
            makeNoteSoundTask = null;
        }
    }

    private final class Cleanup extends BukkitRunnable {
        @Override
        public void run() {
            Location location = ((Location) target);
            location.getBlock().setBlockData(oldBlockData);
            cleanupTask = null;
        }
    }

    @Override
    public String toPermission() {
        return this.data.get(NoteTypeNames.TYPE).toString().replaceAll("\\.*", "") +
                this.data.get(NoteTypeNames.TONE).toString().replaceAll("\\.*", "");
    }
}
