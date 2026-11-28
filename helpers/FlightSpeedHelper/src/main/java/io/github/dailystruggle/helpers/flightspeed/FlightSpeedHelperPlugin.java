package io.github.dailystruggle.helpers.flightspeed;

import io.papermc.paper.event.player.PlayerFailMoveEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Standalone Paper and Folia helper plugin providing unclamped flight speed multipliers
 * beyond CraftBukkit's {@code [-1.0f, 1.0f]} ({@code 10x}) cap by writing directly to
 * {@code Abilities#setFlyingSpeed(float)} and flushing {@code ServerPlayer#onUpdateAbilities()}.
 *
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004, REQ-RTP-S-005, REQ-RTP-S-007, REQ-FOLIA-ARCH-001
 */
public final class FlightSpeedHelperPlugin extends JavaPlugin
        implements CommandExecutor, TabCompleter, Listener {

    static final float VANILLA_ABILITIES_FLY_SPEED = 0.05f;
    static final float BUKKIT_MAX_MULTIPLIER = 10.0f;
    static final float MIN_MULTIPLIER = 0.1f;
    static final float DEFAULT_MAX_MULTIPLIER = 100.0f;

    private static final List<String> SPEED_SUGGESTIONS =
            List.of("reset", "1", "2", "5", "10", "15", "20", "30", "50");
    private static final List<String> FLY_SUGGESTIONS = List.of("toggle", "on", "off");

    private final Set<UUID> boostedPlayers = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        bindCommand("flyspeed");
        bindCommand("fly");
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("FlightSpeedHelper enabled (max-multiplier=" + resolveMaxMultiplier(getConfig()) + ").");
    }

    @Override
    public void onDisable() {
        boostedPlayers.clear();
    }

    private void bindCommand(String name) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().log(Level.WARNING, "Command '" + name + "' missing from plugin.yml");
            return;
        }
        cmd.setExecutor(this);
        cmd.setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        FileConfiguration cfg = getConfig();
        if (!sender.hasPermission("flightspeedhelper.use")) {
            sendConfigured(sender, cfg, "messages.no-permission", "&cNo permission.", Map.of());
            return true;
        }

        String cmdName = command.getName().toLowerCase(Locale.ROOT);
        if ("fly".equals(cmdName)) {
            return handleFlyCommand(sender, cfg, label, args);
        }
        return handleFlySpeedCommand(sender, cfg, label, args);
    }

    boolean handleFlySpeedCommand(
            CommandSender sender, FileConfiguration cfg, String label, String[] args) {
        if (args.length < 1 || args.length > 2) {
            String usage = "/" + label + " <multiplier|reset> [player]";
            getLogger().log(Level.WARNING, "Invalid /" + label + " invocation by " + sender.getName());
            sendConfigured(sender, cfg, "messages.invalid-command", "&cUsage: %usage%", Map.of("%usage%", usage));
            return true;
        }

        float maxMultiplier = resolveMaxMultiplier(cfg);
        String rawArg = args[0].trim();
        boolean isReset = "reset".equalsIgnoreCase(rawArg) || "default".equalsIgnoreCase(rawArg);
        float multiplier = 1.0f;
        if (!isReset) {
            Float parsed = parseMultiplier(rawArg, maxMultiplier);
            if (parsed == null) {
                getLogger().log(
                        Level.WARNING,
                        "Bad /" + label + " parameter '" + rawArg + "' from " + sender.getName());
                sendConfigured(
                        sender,
                        cfg,
                        "messages.bad-parameter",
                        "&cInvalid parameter '%arg%' (0.1..%max%).",
                        Map.of("%arg%", rawArg, "%max%", formatFloat(maxMultiplier)));
                return true;
            }
            multiplier = parsed;
        }

        Player target = resolveTarget(sender, cfg, args.length == 2 ? args[1] : null);
        if (target == null) {
            return true;
        }

        final float targetMultiplier = multiplier;
        final boolean resetMode = isReset;
        runOnEntity(
                target,
                () -> {
                    boolean ok =
                            applyFlightMultiplier(
                                    target, targetMultiplier, boostedPlayers, getLogger());
                    if (resetMode) {
                        sendConfigured(
                                sender,
                                cfg,
                                "messages.speed-reset",
                                "&aReset flight speed for %player%.",
                                Map.of("%player%", target.getName()));
                    } else if (ok) {
                        float rawAbilitiesSpeed = VANILLA_ABILITIES_FLY_SPEED * targetMultiplier;
                        sendConfigured(
                                sender,
                                cfg,
                                "messages.speed-set",
                                "&aSet flight speed for %player% to %multiplier%x.",
                                Map.of(
                                        "%player%", target.getName(),
                                        "%multiplier%", formatFloat(targetMultiplier),
                                        "%raw%", formatFloat(rawAbilitiesSpeed)));
                    } else {
                        sendConfigured(
                                sender,
                                cfg,
                                "messages.unclamped-unsupported",
                                "&cUnclamped flight unsupported; clamped to 10.0x.",
                                Map.of("%player%", target.getName()));
                    }
                });
        return true;
    }

    boolean handleFlyCommand(
            CommandSender sender, FileConfiguration cfg, String label, String[] args) {
        if (args.length > 2) {
            String usage = "/" + label + " [on|off|toggle] [player]";
            getLogger().log(Level.WARNING, "Invalid /" + label + " invocation by " + sender.getName());
            sendConfigured(sender, cfg, "messages.invalid-command", "&cUsage: %usage%", Map.of("%usage%", usage));
            return true;
        }

        Boolean explicitState = null;
        String targetArg = null;
        if (args.length >= 1) {
            String first = args[0].trim().toLowerCase(Locale.ROOT);
            switch (first) {
                case "on", "true", "enable" -> explicitState = Boolean.TRUE;
                case "off", "false", "disable" -> explicitState = Boolean.FALSE;
                case "toggle" -> explicitState = null;
                default -> {
                    if (args.length == 1) {
                        targetArg = args[0];
                    } else {
                        getLogger().log(
                                Level.WARNING,
                                "Bad /" + label + " state '" + args[0] + "' from " + sender.getName());
                        sendConfigured(
                                sender,
                                cfg,
                                "messages.invalid-command",
                                "&cUsage: %usage%",
                                Map.of("%usage%", "/" + label + " [on|off|toggle] [player]"));
                        return true;
                    }
                }
            }
        }
        if (args.length == 2) {
            targetArg = args[1];
        }

        Player target = resolveTarget(sender, cfg, targetArg);
        if (target == null) {
            return true;
        }

        final Boolean desired = explicitState;
        runOnEntity(
                target,
                () -> {
                    boolean next = desired != null ? desired : !target.getAllowFlight();
                    target.setAllowFlight(next);
                    if (!next && target.isFlying()) {
                        target.setFlying(false);
                    }
                    sendConfigured(
                            sender,
                            cfg,
                            next ? "messages.fly-enabled" : "messages.fly-disabled",
                            next ? "&aEnabled flight for %player%." : "&eDisabled flight for %player%.",
                            Map.of("%player%", target.getName()));
                });
        return true;
    }

    private Player resolveTarget(CommandSender sender, FileConfiguration cfg, String targetName) {
        if (targetName == null || targetName.isBlank()) {
            if (sender instanceof Player player) {
                return player;
            }
            sendConfigured(sender, cfg, "messages.player-only", "&cSpecify a player.", Map.of());
            return null;
        }
        if (!(sender instanceof Player self && self.getName().equalsIgnoreCase(targetName))
                && !sender.hasPermission("flightspeedhelper.others")) {
            sendConfigured(sender, cfg, "messages.no-permission", "&cNo permission.", Map.of());
            return null;
        }
        Player found = Bukkit.getPlayerExact(targetName);
        if (found == null) {
            sendConfigured(
                    sender,
                    cfg,
                    "messages.player-not-found",
                    "&cPlayer not found: %player%",
                    Map.of("%player%", targetName));
            return null;
        }
        return found;
    }

    /**
     * Executes work on the entity's owning thread (`Rule F-001` Folia-compatible).
     */
    private void runOnEntity(Player target, Runnable action) {
        try {
            if (Bukkit.isOwnedByCurrentRegion(target)) {
                action.run();
                return;
            }
        } catch (NoSuchMethodError ignored) {
            if (Bukkit.isPrimaryThread()) {
                action.run();
                return;
            }
        }
        Consumer<Object> taskConsumer = ignoredTask -> action.run();
        try {
            Object scheduler = Player.class.getMethod("getScheduler").invoke(target);
            Method runMethod =
                    scheduler.getClass()
                            .getMethod(
                                    "run",
                                    org.bukkit.plugin.Plugin.class,
                                    Consumer.class,
                                    Runnable.class);
            Object scheduled = runMethod.invoke(scheduler, this, taskConsumer, null);
            if (scheduled == null) {
                getLogger().log(
                        Level.WARNING,
                        "Failed to schedule flight speed update for retired player " + target.getName());
            }
        } catch (ReflectiveOperationException e) {
            getLogger().log(
                    Level.WARNING,
                    "EntityScheduler unavailable; falling back to main-thread scheduler for " + target.getName(),
                    e);
            Bukkit.getScheduler().runTask(this, action);
        }
    }

    static Float parseMultiplier(String rawArg, float maxMultiplier) {
        if (rawArg == null || rawArg.isBlank()) {
            return null;
        }
        String cleaned = rawArg.trim();
        if (cleaned.endsWith("x") || cleaned.endsWith("X")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        final float value;
        try {
            value = Float.parseFloat(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
        if (!Float.isFinite(value) || value < MIN_MULTIPLIER || value > maxMultiplier) {
            return null;
        }
        return value;
    }

    static float resolveMaxMultiplier(FileConfiguration cfg) {
        if (cfg == null) {
            return DEFAULT_MAX_MULTIPLIER;
        }
        double configured = cfg.getDouble("max-multiplier", DEFAULT_MAX_MULTIPLIER);
        if (!Double.isFinite(configured) || configured < 1.0d) {
            return DEFAULT_MAX_MULTIPLIER;
        }
        return (float) configured;
    }

    /**
     * Applies {@code multiplier} (where {@code 1.0f} equals vanilla creative {@code 0.05f}
     * {@code Abilities.flyingSpeed}). Multipliers up to {@code 10.0f} use standard Bukkit
     * {@link Player#setFlySpeed(float)}; higher multipliers write directly to the underlying
     * NMS {@code Abilities} instance and synchronize abilities packets to the client.
     *
     * @return {@code true} if the requested multiplier was applied, or {@code false} if an
     *     unclamped reflection write failed and fell back to {@code 10.0x}.
     */
    static boolean applyFlightMultiplier(
            Player player, float multiplier, Set<UUID> boostedSet, Logger logger) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(boostedSet, "boostedSet");
        Objects.requireNonNull(logger, "logger");

        if (!player.getAllowFlight()) {
            player.setAllowFlight(true);
        }

        if (multiplier <= BUKKIT_MAX_MULTIPLIER) {
            // CraftPlayer divides the Bukkit flySpeed [-1, 1] by 2 to obtain Abilities.flyingSpeed:
            // multiplier=1.0 -> bukkitSpeed=0.1 -> Abilities.flyingSpeed=0.05.
            float bukkitSpeed = Math.min(1.0f, Math.max(0.0f, multiplier * 0.1f));
            player.setFlySpeed(bukkitSpeed);
            if (multiplier > 1.0f) {
                boostedSet.add(player.getUniqueId());
            } else {
                boostedSet.remove(player.getUniqueId());
            }
            return true;
        }

        float abilitiesSpeed = VANILLA_ABILITIES_FLY_SPEED * multiplier;
        if (applyUnclampedNmsAbilitiesSpeed(player, abilitiesSpeed, logger)) {
            boostedSet.add(player.getUniqueId());
            return true;
        }

        player.setFlySpeed(1.0f);
        boostedSet.add(player.getUniqueId());
        return false;
    }

    static boolean applyUnclampedNmsAbilitiesSpeed(
            Object craftPlayerOrHandle, float abilitiesSpeed, Logger logger) {
        try {
            Object serverPlayer = resolveServerPlayerHandle(craftPlayerOrHandle);
            Object abilities = invokeNoArg(serverPlayer, "getAbilities");
            if (!writeFlyingSpeed(abilities, abilitiesSpeed)) {
                logger.log(
                        Level.WARNING,
                        "Could not locate setFlyingSpeed(float) or flyingSpeed field on "
                                + abilities.getClass().getName());
                return false;
            }
            Method updateMethod = findNoArgMethod(serverPlayer.getClass(), "onUpdateAbilities");
            if (updateMethod == null) {
                logger.log(
                        Level.WARNING,
                        "Could not locate onUpdateAbilities() on " + serverPlayer.getClass().getName());
                return false;
            }
            updateMethod.invoke(serverPlayer);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.log(Level.WARNING, "Failed to apply unclamped NMS flyingSpeed=" + abilitiesSpeed, e);
            return false;
        }
    }

    private static Object resolveServerPlayerHandle(Object candidate)
            throws ReflectiveOperationException {
        Method getHandle = findNoArgMethod(candidate.getClass(), "getHandle");
        if (getHandle != null) {
            return getHandle.invoke(candidate);
        }
        return candidate;
    }

    private static Object invokeNoArg(Object receiver, String methodName)
            throws ReflectiveOperationException {
        Method method = findNoArgMethod(receiver.getClass(), methodName);
        if (method == null) {
            throw new NoSuchMethodException(receiver.getClass().getName() + "#" + methodName + "()");
        }
        return method.invoke(receiver);
    }

    private static boolean writeFlyingSpeed(Object abilities, float abilitiesSpeed)
            throws ReflectiveOperationException {
        Method setter = findSingleFloatMethod(abilities.getClass(), "setFlyingSpeed");
        if (setter != null) {
            setter.invoke(abilities, abilitiesSpeed);
            return true;
        }
        Field field = findFloatField(abilities.getClass(), "flyingSpeed");
        if (field != null) {
            field.setAccessible(true);
            field.setFloat(abilities, abilitiesSpeed);
            return true;
        }
        return false;
    }

    private static Method findNoArgMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException ignored) {
            // Fall through to declared-method superclass walk for non-public members.
        }
        for (Class<?> cur = type; cur != null; cur = cur.getSuperclass()) {
            try {
                Method m = cur.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // Continue walking superclass hierarchy.
            }
        }
        return null;
    }

    private static Method findSingleFloatMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name, float.class);
        } catch (NoSuchMethodException ignored) {
            // Fall through to declared-method superclass walk for non-public members.
        }
        for (Class<?> cur = type; cur != null; cur = cur.getSuperclass()) {
            try {
                Method m = cur.getDeclaredMethod(name, float.class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // Continue walking superclass hierarchy.
            }
        }
        return null;
    }

    private static Field findFloatField(Class<?> type, String name) {
        for (Class<?> cur = type; cur != null; cur = cur.getSuperclass()) {
            try {
                Field f = cur.getDeclaredField(name);
                if (f.getType() == float.class) {
                    return f;
                }
            } catch (NoSuchFieldException ignored) {
                // Continue walking superclass hierarchy.
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerFailMove(PlayerFailMoveEvent event) {
        if (event.getFailReason() != PlayerFailMoveEvent.FailReason.MOVED_TOO_QUICKLY) {
            return;
        }
        if (!getConfig().getBoolean("suppress-moved-too-quickly", true)) {
            return;
        }
        Player player = event.getPlayer();
        if (boostedPlayers.contains(player.getUniqueId())
                && (player.isFlying() || player.getAllowFlight())) {
            event.setAllowed(true);
            event.setLogWarning(false);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        boostedPlayers.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("flightspeedhelper.use")) {
            return Collections.emptyList();
        }
        String cmdName = command.getName().toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            List<String> base = "fly".equals(cmdName) ? FLY_SUGGESTIONS : SPEED_SUGGESTIONS;
            return filterPrefix(base, args[0]);
        }
        if (args.length == 2 && sender.hasPermission("flightspeedhelper.others")) {
            List<String> names = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                names.add(online.getName());
            }
            return filterPrefix(names, args[1]);
        }
        return Collections.emptyList();
    }

    private static List<String> filterPrefix(List<String> candidates, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(candidate);
            }
        }
        return matches;
    }

    static String formatMessage(String template, Map<String, String> placeholders) {
        String resolved = template == null ? "" : template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            resolved = resolved.replace(entry.getKey(), entry.getValue());
        }
        return ChatColor.translateAlternateColorCodes('&', resolved);
    }

    private static void sendConfigured(
            CommandSender sender,
            FileConfiguration cfg,
            String path,
            String fallback,
            Map<String, String> placeholders) {
        String template = cfg != null ? cfg.getString(path, fallback) : fallback;
        String formatted = formatMessage(template, placeholders);
        if (!formatted.isEmpty()) {
            sender.sendMessage(formatted);
        }
    }

    private static String formatFloat(float value) {
        if (Math.abs(value - Math.round(value)) < 1e-4f) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    Set<UUID> boostedPlayersForTest() {
        return boostedPlayers;
    }
}
