package leafcraft.rtp.customEventListeners;

import leafcraft.rtp.API.customEvents.RandomTeleportEvent;
import leafcraft.rtp.API.customEvents.TeleportCommandSuccessEvent;
import leafcraft.rtp.RTP;
import org.bukkit.*;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public class TeleportEffects implements Listener {
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTeleportCommand(TeleportCommandSuccessEvent event) {
        Bukkit.getScheduler().runTaskAsynchronously(RTP.getPlugin(), ()->applyEffects("rtp.effect.command.",event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRandomTeleport(RandomTeleportEvent event) {
        Bukkit.getScheduler().runTaskAsynchronously(RTP.getPlugin(), ()->applyEffects("rtp.effect.teleport.",event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRandomPreTeleport(RandomTeleportEvent event) {
        Bukkit.getScheduler().runTaskAsynchronously(RTP.getPlugin(), ()->applyEffects("rtp.effect.preTeleport.",event.getPlayer()));
    }

    private void applyEffects(String permission, Player player) {
        List<PotionEffect> potionEffects = new ArrayList<>();
        Map<Sound,float[]> sounds = new HashMap<>();
        List<Object[]> notes = new ArrayList<>();
        List<Object[]> particles = new ArrayList<>();
        List<Object[]> fireworks = new ArrayList<>();
        Set<PermissionAttachmentInfo> perms = player.getEffectivePermissions();

        for (PermissionAttachmentInfo perm : perms) {
            if (!perm.getValue()) continue;
            String node = perm.getPermission();
            if (node.startsWith(permission)) {
                String[] val = node.split("\\.");
                if (val[3] == null || val[3].equals("")) continue;

                switch (val[3].toLowerCase()) {
                    case "potion": {
                        if (val.length < 5 || val[4] == null || val[4].equals("")) continue;
                        PotionEffectType potionEffectType = PotionEffectType.getByName(val[4]);
                        if (potionEffectType == null) continue;
                        int duration = 255;
                        int amplifier = 1;
                        boolean ambient = false;
                        boolean potionParticles = true;
                        boolean icon = false;
                        if (val.length > 5 && val[5] != null) {
                            try {
                                duration = Integer.parseInt(val[5]);
                            } catch (NumberFormatException ignored) {
                                Bukkit.getLogger().warning("[rtp] invalid duration setting: " + val[5]);
                            }
                        }
                        if (val.length > 6 && val[6] != null) {
                            try {
                                amplifier = Integer.parseInt(val[6]);
                            } catch (NumberFormatException ignored) {
                                Bukkit.getLogger().warning("[rtp] invalid amplifier setting: " + val[6]);
                            }
                        }
                        if (val.length > 7 && val[7] != null) {
                            try {
                                ambient = Boolean.parseBoolean(val[7]);
                            } catch (NumberFormatException ignored) {
                                Bukkit.getLogger().warning("[rtp] invalid ambient setting: " + val[7]);
                            }
                        }
                        if (val.length > 8 && val[8] != null) {
                            try {
                                potionParticles = Boolean.parseBoolean(val[8]);
                            } catch (NumberFormatException ignored) {
                                Bukkit.getLogger().warning("[rtp] invalid potion particles setting: " + val[8]);
                            }
                        }
                        if (val.length > 9 && val[9] != null) {
                            try {
                                icon = Boolean.parseBoolean(val[9]);
                            } catch (NumberFormatException ignored) {
                                Bukkit.getLogger().warning("[rtp] invalid icon setting: " + val[9]);
                            }
                        }
                        potionEffects.add(new PotionEffect(potionEffectType, duration, amplifier, ambient, potionParticles, icon));
                        break;
                    }
                    case "note": {
                        Note note;
                        int tone = 0;
                        if (val.length > 5 && val[5] != null) {
                            try {
                                tone = Integer.parseInt(val[5]);
                            } catch (NumberFormatException exception) {
                                Bukkit.getLogger().warning("[rtp] invalid tone setting: " + val[5]);
                                continue;
                            }
                        }

                        Instrument instrument = Instrument.PIANO;
                        if (val.length > 6 && val[6] != null) {
                            try {
                                instrument = Instrument.valueOf(val[6]);
                            } catch (IllegalArgumentException exception) {
                                Bukkit.getLogger().warning("[rtp] invalid instrument: " + val[6]);
                                continue;
                            }
                        }

                        try {
                            note = new Note(tone);
                        } catch (IllegalArgumentException | NullPointerException exception) {
                            Bukkit.getLogger().warning("[rtp] invalid tone: " + tone);
                            continue;
                        }

                        notes.add(new Object[]{instrument,note});
                        break;
                    }
                    case "sound": {
                        Sound sound;
                        try {
                            sound = Sound.valueOf(val[4].toUpperCase());
                        } catch (IllegalArgumentException | NullPointerException exception) {
                            Bukkit.getLogger().warning("[rtp] invalid sound: " + val[4]);
                            continue;
                        }
                        int volume = 100;
                        int pitch = 100;
                        if (val.length > 5 && val[5] != null) {
                            try {
                                volume = Integer.parseInt(val[5]);
                            } catch (NumberFormatException ignored) {
                                Bukkit.getLogger().warning("[rtp] invalid volume setting: " + val[5]);
                            }
                        }
                        if (val.length > 6 && val[6] != null) {
                            try {
                                pitch = Integer.parseInt(val[6]);
                            } catch (NumberFormatException ignored) {
                                Bukkit.getLogger().warning("[rtp] invalid amplifier setting: " + val[6]);
                            }
                        }
                        float[] vals = {(((float) volume) / 100), (((float) pitch) / 100)};
                        sounds.put(sound, vals);
                        break;
                    }
                    case "particle": {
                        Particle particle;
                        try {
                            particle = Particle.valueOf(val[4].toUpperCase());
                        } catch (IllegalArgumentException | NullPointerException exception) {
                            Bukkit.getLogger().warning("[rtp] invalid particle type: " + val[4]);
                            continue;
                        }
                        int numParticles = 0;
                        if (val.length > 5 && val[5] != null) {
                            try {
                                numParticles = Integer.parseInt(val[5]);
                            } catch (NumberFormatException ignored) {
                                Bukkit.getLogger().warning("[rtp] invalid number of particles: " + val[5]);
                                continue;
                            }
                        }

                        particles.add(new Object[]{particle, numParticles});
                        break;
                    }
                    case "firework": {
                        int numFireworks = 0;
                        if (val.length > 4 && val[4] != null) {
                            try {
                                numFireworks = Integer.parseInt(val[4]);
                            } catch (NumberFormatException ignored) {
                                Bukkit.getLogger().warning("[rtp] invalid number of particles: " + val[5]);
                                continue;
                            }
                        }

                        Color color = Color.WHITE;
                        if (val.length > 5 && val[5] != null) {
                            try {
                                color = Color.fromRGB(Integer.parseInt(val[5],16));
                            } catch (NumberFormatException ignored) {
                                Bukkit.getLogger().warning("[rtp] invalid particle color: " + val[5]);
                                continue;
                            }
                        }

                        Color fade = Color.WHITE;
                        if (val.length > 6 && val[6] != null) {
                            try {
                                fade = Color.fromRGB(Integer.parseInt(val[6],16));
                            } catch (NumberFormatException ignored) {
                                Bukkit.getLogger().warning("[rtp] invalid particle color: " + val[5]);
                                continue;
                            }
                        }

                        boolean flicker = false;
                        if (val.length > 7 && val[7] != null) {
                            try {
                                flicker = Boolean.parseBoolean(val[7]);
                            } catch (NumberFormatException ignored) {
                                Bukkit.getLogger().warning("[rtp] invalid flicker setting: " + val[6]);
                                continue;
                            }
                        }

                        boolean trail = false;
                        if (val.length > 8 && val[8] != null) {
                            try {
                                trail = Boolean.parseBoolean(val[8]);
                            } catch (NumberFormatException ignored) {
                                Bukkit.getLogger().warning("[rtp] invalid trail setting: " + val[7]);
                                continue;
                            }
                        }

                        fireworks.add(new Object[]{numFireworks, color, fade, flicker, trail});
                        break;
                    }
                }
            }
        }

        for(Map.Entry<Sound,float[]> entry : sounds.entrySet()) {
            player.playSound(player.getLocation(),entry.getKey(),entry.getValue()[0],entry.getValue()[1]);
        }
        player.addPotionEffects(potionEffects);
        for(Object[] particle : particles) {
            Particle p = (Particle) particle[0];
            player.spawnParticle(p,player.getLocation(), (Integer) particle[1]);
        }
        for(Object[] note : notes) {
            player.playNote(player.getLocation(),(Instrument) note[0],(Note) note[1]);
        }
        if(fireworks.size()>0) {
            Bukkit.getScheduler().runTask(RTP.getPlugin(), () -> {
                for (Object[] firework : fireworks) {
                    for (int i = 0; i < (int) firework[0]; i++) {
                        Firework f = (Firework) Objects.requireNonNull(player.getLocation().getWorld())
                                .spawnEntity(player.getLocation().clone().add(0, 1, 0), EntityType.FIREWORK);
                        FireworkMeta fwm = f.getFireworkMeta();

                        fwm.setPower(1);
                        FireworkEffect fireworkEffect = FireworkEffect.builder()
                                .withColor((Color) firework[1])
                                .withFade((Color) firework[2])
                                .flicker((boolean) firework[3])
                                .trail((boolean) firework[4])
                                .build();
                        fwm.addEffect(fireworkEffect);
                        f.setFireworkMeta(fwm);
                        if (player.isInvulnerable()) f.detonate();
                        else {
                            player.setInvulnerable(true);
                            f.detonate();
                            Bukkit.getScheduler().runTaskLater(RTP.getPlugin(), () -> player.setInvulnerable(false), 1);
                        }
                    }
                }
            });
        }
    }
}
