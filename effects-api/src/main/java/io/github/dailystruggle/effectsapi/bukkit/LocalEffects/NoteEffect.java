package io.github.dailystruggle.effectsapi.bukkit.LocalEffects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.enums.NoteTypeNames;
import org.bukkit.Instrument;
import org.bukkit.Location;
import org.bukkit.Note;
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

    public NoteEffect() throws IllegalArgumentException {
        super(new EnumMap<>(NoteTypeNames.class));
        EnumMap<NoteTypeNames, Object> data = getData();
        data.put(NoteTypeNames.TYPE, Instrument.PIANO);
        data.put(NoteTypeNames.TONE, 0);
        this.data = data;
        this.defaults = data.clone();
    }

    /**
     * Historically this placed a temporary {@code NOTE_BLOCK} at {@code target}
     * so the next-tick {@code MakeNoteSound} could play it; the block was
     * restored 1 tick later by a {@code Cleanup} task. That dance is no longer
     * needed: {@link Player#playNote(Location, Instrument, Note)} is a
     * clientbound packet that does not require a real note block at the
     * location, and the place/restore pair tripped Paper's AsyncCatcher
     * (`Asynchronous block remove!`) and Folia's region ownership check, while
     * also briefly mutating world state visible to other plugins.
     *
     * <p>{@code run()} is intentionally a no-op now; the sound is emitted by
     * the {@link MakeNoteSound} task scheduled in {@link #runTask(Plugin)}.
     */
    @Override
    public void run() {
        if (target instanceof Entity) target = ((Entity) target).getLocation();
        // No block placement: playNote does not require a NoteBlock at target.
    }

    /**
     * Bukkit-side scheduling helper. Replaces the prior {@code BukkitRunnable}
     * inheritance: callers (BukkitEffectsHandler, EffectsAPI commands) must
     * invoke this instead of {@code Bukkit.getScheduler().runTask(plugin, this)}
     * because {@code NoteEffect} additionally needs to schedule the
     * {@link MakeNoteSound} follow-up task one tick later.
     */
    @NotNull
    public synchronized BukkitTask runTask(@NotNull Plugin plugin) {
        BukkitTask res = org.bukkit.Bukkit.getScheduler().runTask(plugin, (Runnable) this);
        // 1-tick delay preserves prior timing relative to other postteleport effects.
        makeNoteSoundTask = new MakeNoteSound().runTaskLater(plugin, 1);
        return res;
    }

    /**
     * Bukkit-side cancel helper. {@link Effect} no longer extends
     * {@code BukkitRunnable}, so this is a plain instance method on
     * {@code NoteEffect} — only the deferred {@link MakeNoteSound} task needs
     * cancelling; the run() body is a no-op.
     */
    public void cancel() {
        if (makeNoteSoundTask != null) makeNoteSoundTask.cancel();
    }

    @Override
    public void setData(String... data) {
        applyByType(KEY_ORDER, data);
    }

    private static final NoteTypeNames[] KEY_ORDER = { NoteTypeNames.TYPE, NoteTypeNames.TONE };

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

    @Override
    public String toPermission() {
        return this.data.get(NoteTypeNames.TYPE).toString().replaceAll("\\.*", "") +
                this.data.get(NoteTypeNames.TONE).toString().replaceAll("\\.*", "");
    }
}
