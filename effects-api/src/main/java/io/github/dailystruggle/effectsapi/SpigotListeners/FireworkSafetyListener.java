package io.github.dailystruggle.effectsapi.SpigotListeners;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FireworkExplodeEvent;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

public class FireworkSafetyListener implements Listener {
    //number of detonations by firework id
    private static final ConcurrentHashMap<Integer, FireworkDetonation> fireworkDetonations = new ConcurrentHashMap<>();
    //temporarily store entity id within range of firework to cancel damage in another event
    //  the reason I set up safety this way instead of setInvulnerable is because:
    //    - I do not want a crash to cause permanent invulnerability.
    //    - I do not want to interfere with other FireworkTypeNames of damage.
    //  static because can be common between instances and should be accessible from FireworkEffect
    private static final ConcurrentSkipListSet<Integer> safeEntities = new ConcurrentSkipListSet<>();
    public final Plugin caller;

    public FireworkSafetyListener(Plugin caller) {
        this.caller = caller;
    }

    public static void addFirework(Integer fireworkId, Integer numExplosions, Boolean isSafe) {
        fireworkDetonations.put(fireworkId, new FireworkDetonation(fireworkId, numExplosions, isSafe));
    }

    //multiply explosions rather than fireworks, for fewer moving parts
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFireworkExplode(FireworkExplodeEvent event) {
        Integer fireworkId = event.getEntity().getEntityId();

        //comparator only uses id so this should return the actual option
        FireworkDetonation fireworkDetonation = fireworkDetonations.get(fireworkId);
        if (fireworkDetonation == null) return; //stop if firework was not on the list

        if (fireworkDetonation.isSafe) {
            Collection<Entity> entities = event.getEntity().getNearbyEntities(5, 5, 5);
            for (Entity entity : entities) {
                safeEntities.add(entity.getEntityId());
            }

            Bukkit.getScheduler().runTaskLater(caller, () -> {
                for (Entity entity : entities) {
                    safeEntities.remove(entity.getEntityId());
                }
            }, 1);
        }

        fireworkDetonations.remove(fireworkId); //remove from map to prevent recursion
        Location location = event.getEntity().getLocation();
        for (int i = 1; i < fireworkDetonation.numExplosions; i++) { //one already ran to trigger this, so start from 1
            Firework firework = (Firework) location.getWorld().spawnEntity(location, event.getEntityType());
            firework.setFireworkMeta(event.getEntity().getFireworkMeta());
            firework.detonate();
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFireworkDamage(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) return;
        if (!event.getCause().equals(EntityDamageEvent.DamageCause.ENTITY_EXPLOSION)) return;
        if (safeEntities.contains(event.getEntity().getEntityId())) event.setCancelled(true);
    }

    //container for firework data
    private static class FireworkDetonation implements Comparable<FireworkDetonation> {
        public Integer fireworkId; //entity id of firework
        public Integer numExplosions; //number of explosions to produce
        public Boolean isSafe; // do or don't protect entities within a 5-meter radius

        public FireworkDetonation() {

        }

        public FireworkDetonation(Integer fireworkId) {
            this.fireworkId = fireworkId;
            numExplosions = 1;
            isSafe = false;
        }

        public FireworkDetonation(Integer fireworkId, Integer numExplosions, Boolean isSafe) {
            this.fireworkId = fireworkId;
            this.numExplosions = numExplosions;
            this.isSafe = isSafe;
        }

        //compare only ID because there shouldn't be more than one entity with that ID
        @Override
        public int compareTo(FireworkDetonation o) {
            return this.fireworkId.compareTo(o.fireworkId);
        }
    }
}
