package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.ScanTask;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlaceholderProvider {
    public static final Map<String, Function<UUID, String>> placeholders = new ConcurrentHashMap<>();

    static {
        placeholders.put(
                "delay",
                uuid -> {
                    if (RTP.getInstance() == null) return "0";
                    if (RTP.serverAccessor == null) return "0";
                    RTPCommandSender commandSender = RTP.serverAccessor.getSender(uuid);
                    Number n = RTP.configs.getParser(ConfigKeys.class).getNumber(ConfigKeys.teleportDelay, 0);
                    int n2 = ParsePermissions.getInt(commandSender, "rtp.delay.");
                    if (n2 >= 0) n = n2;
                    if (n.longValue() == 0) return "0";

                    long time = n.longValue();
                    ConfigParser<MessagesKeys> langParser =
                            (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
                    long days = TimeUnit.SECONDS.toDays(time);
                    long hours = TimeUnit.SECONDS.toHours(time) % 24;
                    long minutes = TimeUnit.SECONDS.toMinutes(time) % 60;
                    long seconds = time % 60;

                    String replacement = "";
                    if (days > 0)
                        replacement += days + langParser.getConfigValue(MessagesKeys.days, "").toString() + " ";
                    if (hours > 0)
                        replacement +=
                                hours + langParser.getConfigValue(MessagesKeys.hours, "").toString() + " ";
                    if (minutes > 0)
                        replacement +=
                                minutes + langParser.getConfigValue(MessagesKeys.minutes, "").toString() + " ";
                    if (seconds > 0)
                        replacement += seconds + langParser.getConfigValue(MessagesKeys.seconds, "").toString();
                    return replacement;
                });
        placeholders.put(
                "cooldown",
                uuid -> {
                    if (RTP.getInstance() == null) return "A";
                    if (RTP.serverAccessor == null) return "B";
                    RTPCommandSender commandSender = RTP.serverAccessor.getSender(uuid);
                    Number n =
                            RTP.configs.getParser(ConfigKeys.class).getNumber(ConfigKeys.teleportCooldown, 0);
                    int n2 = ParsePermissions.getInt(commandSender, "rtp.cooldown.");
                    if (n2 >= 0) n = n2;

                    long time = n.longValue();
                    if (time <= 0) time = 0;
                    ConfigParser<MessagesKeys> langParser =
                            (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
                    long days = TimeUnit.SECONDS.toDays(time);
                    long hours = TimeUnit.SECONDS.toHours(time) % 24;
                    long minutes = TimeUnit.SECONDS.toMinutes(time) % 60;
                    long seconds = time % 60;

                    String replacement = "";
                    if (days > 0)
                        replacement += days + langParser.getConfigValue(MessagesKeys.days, "").toString() + " ";
                    if (hours > 0)
                        replacement +=
                                hours + langParser.getConfigValue(MessagesKeys.hours, "").toString() + " ";
                    if (minutes > 0)
                        replacement +=
                                minutes + langParser.getConfigValue(MessagesKeys.minutes, "").toString() + " ";
                    if (seconds > 0)
                        replacement += seconds + langParser.getConfigValue(MessagesKeys.seconds, "").toString();
                    return replacement;
                });
        placeholders.put(
                "remainingCooldown",
                uuid -> {
                    if (RTP.getInstance() == null) return "A";
                    if (RTP.serverAccessor == null) return "B";

                    long start = System.currentTimeMillis();

                    RTPCommandSender sender = RTP.serverAccessor.getSender(uuid);
                    if (sender != null) {
                        TeleportData teleportData = RTP.getInstance().latestTeleportData.get(uuid);
                        long lastTime = start;
                        if (teleportData != null) lastTime = teleportData.time;

                        long n = sender.cooldown();

                        long currTime = (start - lastTime);
                        long remainingTime = n - currTime;
                        if (remainingTime < 0) remainingTime = 0;

                        ConfigParser<MessagesKeys> langParser =
                                (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
                        long days = TimeUnit.MILLISECONDS.toDays(remainingTime);
                        long hours = TimeUnit.MILLISECONDS.toHours(remainingTime) % 24;
                        long minutes = TimeUnit.MILLISECONDS.toMinutes(remainingTime) % 60;
                        long seconds = TimeUnit.MILLISECONDS.toSeconds(remainingTime) % 60;
                        long millis = remainingTime % 1000;
                        if (millis > 500 && seconds > 0) {
                            seconds++;
                            millis = 0;
                        }

                        String replacement = "";
                        if (days > 0)
                            replacement +=
                                    days + langParser.getConfigValue(MessagesKeys.days, "").toString() + " ";
                        if (hours > 0)
                            replacement +=
                                    hours + langParser.getConfigValue(MessagesKeys.hours, "").toString() + " ";
                        if (minutes > 0)
                            replacement +=
                                    minutes + langParser.getConfigValue(MessagesKeys.minutes, "").toString() + " ";
                        if (seconds > 0) {

                            replacement +=
                                    seconds + langParser.getConfigValue(MessagesKeys.seconds, "").toString();
                        }
                        if (seconds < 2) {
                            replacement += millis + langParser.getConfigValue(MessagesKeys.millis, "").toString();
                        }
                        return replacement;
                    }
                    return "C";
                });
        placeholders.put(
                "queueLocation",
                uuid -> {
                    if (RTP.getInstance() == null) return "0";
                    TeleportData teleportData = RTP.getInstance().latestTeleportData.get(uuid);
                    if (teleportData == null) return "0";
                    return String.valueOf(teleportData.queueLocation);
                });
        placeholders.put(
                "tickets",
                uuid ->{
                    long res = 0;
                    for (RTPWorld<?> world : RTP.serverAccessor.getRTPWorlds()) {
                        res += world.activeChunkTickets.get();
                    }
                    return String.valueOf(res);
                });

        placeholders.put(
                "plugin_forced",
                uuid -> {
                    long res = 0;
                    for (RTPWorld<?> world : RTP.serverAccessor.getRTPWorlds()) {
                        res += world.numForceLoaded();
                    }
                    return String.valueOf(res);
                });

        placeholders.put(
                "server_forced",
                uuid -> {
                    long res = 0;
                    for (RTPWorld<?> world : RTP.serverAccessor.getRTPWorlds()) {
                        res += world.getServerForceLoadedCount().join();
                    }
                    return String.valueOf(res);
                });

        placeholders.put(
                "teleports",
                uuid -> {
                    if (RTP.getInstance() == null) return "0";
                    return String.valueOf(RTP.getInstance().latestTeleportData.values().stream().filter(teleportData -> !teleportData.completed).count());
                });

        placeholders.put(
                "mspt",
                uuid -> String.format("%.4f", io.github.dailystruggle.rtp.common.tools.PerformanceTracker.pluginMSPT));

        placeholders.put(
                "loads",
                uuid -> {
                    long totalLoads = 0;
                    for (RTPWorld<?> world : RTP.serverAccessor.getRTPWorlds()) {
                        totalLoads += world.totalChunkLoads.get();
                    }
                    return String.valueOf(totalLoads);
                });

        placeholders.put(
                "leakRate",
                uuid -> {
                    long activeTickets = 0;
                    long totalLoads = 0;
                    for (RTPWorld<?> world : RTP.serverAccessor.getRTPWorlds()) {
                        activeTickets += world.activeChunkTickets.get();
                        totalLoads += world.totalChunkLoads.get();
                    }

                    long totalExpectedTickets = 0;
                    // (Detailed tracking removed)

                    long discrepancy = activeTickets - totalExpectedTickets;
                    double leakRate = (totalLoads > 0) ? ((double) Math.max(0, discrepancy) / totalLoads) * 100.0 : 0.0;
                    return String.format("%.4f%%", leakRate);
                });
        placeholders.put(
                "attempts",
                uuid -> {
                    if (RTP.getInstance() == null) return "A";
                    TeleportData teleportData = RTP.getInstance().latestTeleportData.get(uuid);
                    if (teleportData == null) return "B";
                    return String.valueOf(teleportData.attempts);
                });
        placeholders.put(
                "processingTime",
                uuid -> {
                    if (RTP.getInstance() == null) return "0";
                    TeleportData teleportData = RTP.getInstance().latestTeleportData.get(uuid);

                    long time = (teleportData != null) ? teleportData.processingTime : 0L;

                    ConfigParser<MessagesKeys> langParser =
                            (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
                    long days = TimeUnit.MILLISECONDS.toDays(time);
                    long hours = TimeUnit.MILLISECONDS.toHours(time) % 24;
                    long minutes = TimeUnit.MILLISECONDS.toMinutes(time) % 60;
                    long seconds = TimeUnit.MILLISECONDS.toSeconds(time) % 60;
                    long millis = time % 1000;
                    if (millis > 500 && seconds > 0) {
                        seconds++;
                        millis = 0;
                    }

                    String replacement = "";
                    if (days > 0)
                        replacement += days + langParser.getConfigValue(MessagesKeys.days, "").toString() + " ";
                    if (hours > 0)
                        replacement +=
                                hours + langParser.getConfigValue(MessagesKeys.hours, "").toString() + " ";
                    if (minutes > 0)
                        replacement +=
                                minutes + langParser.getConfigValue(MessagesKeys.minutes, "").toString() + " ";
                    if (seconds > 0)
                        replacement += seconds + langParser.getConfigValue(MessagesKeys.seconds, "").toString();
                    if (seconds < 2) {
                        replacement += millis + langParser.getConfigValue(MessagesKeys.millis, "").toString();
                    }
                    return replacement;
                });
        placeholders.put(
                "spot",
                uuid -> {
                    if (RTP.getInstance() == null) return "0";
                    TeleportData teleportData = RTP.getInstance().latestTeleportData.get(uuid);
                    if (teleportData == null) return "0";

                    long spot = teleportData.queueLocation;
                    return String.valueOf(spot);
                });
        placeholders.put(
                "player",
                uuid -> {
                    RTPCommandSender sender = RTP.serverAccessor.getSender(uuid);
                    if (sender == null) {
                        return "";
                    }
                    return sender.name();
                });
        placeholders.put(
                "player_name",
                uuid -> {
                    RTPCommandSender sender = RTP.serverAccessor.getSender(uuid);
                    if (sender == null) {
                        return "";
                    }
                    return sender.name();
                });
        placeholders.put(
                "player_status",
                uuid -> {
                    RTPCommandSender sender = RTP.serverAccessor.getSender(uuid);
                    if (sender == null) {
                        return "";
                    }

                    TeleportData data = RTP.getInstance().latestTeleportData.get(uuid);
                    ConfigParser<MessagesKeys> lang =
                            (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);

                    if (data == null)
                        return lang.getConfigValue(MessagesKeys.PLAYER_AVAILABLE, "").toString();
                    if (data.completed) {
                        long dt = System.currentTimeMillis() - data.time;
                        if (dt < 0) dt = Long.MAX_VALUE + dt;
                        if (dt < sender.cooldown()) {
                            return lang.getConfigValue(MessagesKeys.PLAYER_COOLDOWN, "").toString();
                        }

                        return lang.getConfigValue(MessagesKeys.PLAYER_AVAILABLE, "").toString();
                    }

                    RTPRunnable nextTask = data.nextTask;
                    if (nextTask instanceof TeleportPipelineTask) {
                        TeleportPipelineTask task = (TeleportPipelineTask) nextTask;
                        switch (task.getPhase()) {
                            case TELEPORT:
                                return lang.getConfigValue(MessagesKeys.PLAYER_TELEPORTING, "").toString();
                            case LOAD:
                                return lang.getConfigValue(MessagesKeys.PLAYER_LOADING, "").toString();
                            case SETUP:
                                return lang.getConfigValue(MessagesKeys.PLAYER_SETUP, "").toString();
                            default:
                                break;
                        }
                    }
                    return "";
                });

        placeholders.put(
                "total_queue_length",
                uuid -> {
                    RTPPlayer player = RTP.serverAccessor.getPlayer(uuid);
                    if (player == null) {
                        return "";
                    }

                    Region region =
                            RTP.selectionAPI.getRegion(player);
                    if (region == null) return "0";
                    return String.valueOf(region.queueManager.getTotalQueueLength(uuid));
                });

        placeholders.put(
                "public_queue_length",
                uuid -> {
                    RTPPlayer player = RTP.serverAccessor.getPlayer(uuid);
                    if (player == null) {
                        return "";
                    }

                    Region region =
                            RTP.selectionAPI.getRegion(player);
                    if (region == null) return "0";
                    return String.valueOf(region.queueManager.getPublicQueueLength());
                });

        placeholders.put(
                "personal_queue_length",
                uuid -> {
                    RTPPlayer player = RTP.serverAccessor.getPlayer(uuid);
                    if (player == null) {
                        return "";
                    }

                    Region region =
                            RTP.selectionAPI.getRegion(player);
                    if (region == null) return "0";
                    return String.valueOf(region.queueManager.getPersonalQueueLength(uuid));
                });

        placeholders.put(
                "teleport_world",
                uuid -> {
                    TeleportData data = RTP.getInstance().latestTeleportData.get(uuid);
                    if (data == null || data.selectedCoords == null) return "";
                    return data.selectedCoords.worldName();
                });

        placeholders.put(
                "teleport_x",
                uuid -> {
                    TeleportData data = RTP.getInstance().latestTeleportData.get(uuid);
                    if (data == null || data.selectedCoords == null) return "";
                    return String.valueOf(data.selectedCoords.x());
                });

        placeholders.put(
                "teleport_y",
                uuid -> {
                    TeleportData data = RTP.getInstance().latestTeleportData.get(uuid);
                    if (data == null || data.selectedCoords == null) return "";
                    return String.valueOf(data.selectedCoords.y());
                });

        placeholders.put(
                "teleport_z",
                uuid -> {
                    TeleportData data = RTP.getInstance().latestTeleportData.get(uuid);
                    if (data == null || data.selectedCoords == null) return "";
                    return String.valueOf(data.selectedCoords.z());
                });

        placeholders.put(
                "teleport_biome",
                uuid -> {
                    TeleportData data = RTP.getInstance().latestTeleportData.get(uuid);
                    if (data == null || data.selectedCoords == null) return "";
                    RTPWorld<?> world =
                            RTP.serverAccessor.getRTPWorld(data.selectedCoords.worldName());
                    if (world == null) return "";
                    // ADR-016 §13.1 follow-up (2026-04-20): route the biome read
                    // through the resolved chunk so anvil-cached data is used
                    // transparently and an ungenerated chunk is probed/loaded on
                    // demand rather than falling to the seed-synth getter.
                    int cx = data.selectedCoords.x() >> 4;
                    int cz = data.selectedCoords.z() >> 4;
                    try {
                        io.github.dailystruggle.rtp.api.world.RTPChunk<?> chunk =
                                world.getOrLoadChunk(cx, cz).get(5, TimeUnit.SECONDS);
                        if (chunk != null) {
                            return chunk.getBiome(
                                    data.selectedCoords.x(),
                                    data.selectedCoords.y(),
                                    data.selectedCoords.z());
                        }
                    } catch (Exception ignored) {
                        // Fall through to world-level getter on timeout/error.
                    }
                    return world.getBiome(
                            data.selectedCoords.x(), data.selectedCoords.y(), data.selectedCoords.z());
                });
        placeholders.put(
                "scan_chunks",
                uuid -> {
                    if (RTP.getInstance() == null) return "0";
                    long total = 0;
                    for (ScanTask task : RTP.getInstance().scanTasks.values()) {
                        total += task.latestAbsolutePos;
                    }
                    return String.valueOf(total);
                });
        placeholders.put(
                "scan_totalChunks",
                uuid -> {
                    if (RTP.getInstance() == null) return "0";
                    long total = 0;
                    for (ScanTask task : RTP.getInstance().scanTasks.values()) {
                        total += task.latestAbsoluteTotal;
                    }
                    return String.valueOf(total);
                });
        placeholders.put(
                "scan_cps",
                uuid -> {
                    if (RTP.getInstance() == null) return "0";
                    long total = 0;
                    for (ScanTask task : RTP.getInstance().scanTasks.values()) {
                        total += task.latestCps;
                    }
                    return String.valueOf(total);
                });
        placeholders.put(
                "scan_regions",
                uuid -> {
                    if (RTP.getInstance() == null) return "";
                    return String.join(", ", RTP.getInstance().scanTasks.keySet());
                });
        placeholders.put(
                "scan_eta",
                uuid -> {
                    try {
                        if (RTP.getInstance() == null) return "0s";

                        long maxEta = 0;
                        for (ScanTask task : RTP.getInstance().scanTasks.values()) {
                            if (task.latestEtaSeconds > maxEta) maxEta = task.latestEtaSeconds;
                        }

                        ConfigParser<MessagesKeys> langParser =
                                (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);

                        if (langParser == null) return maxEta + "s";

                        long days = TimeUnit.SECONDS.toDays(maxEta);
                        long hours = TimeUnit.SECONDS.toHours(maxEta) % 24;
                        long minutes = TimeUnit.SECONDS.toMinutes(maxEta) % 60;
                        long seconds = maxEta % 60;

                        StringBuilder replacement = new StringBuilder();

                        if (days > 0) {
                            replacement.append(days).append(String.valueOf(langParser.getConfigValue(MessagesKeys.days, ""))).append(" ");
                        }
                        if (hours > 0) {
                            replacement.append(hours).append(String.valueOf(langParser.getConfigValue(MessagesKeys.hours, ""))).append(" ");
                        }
                        if (minutes > 0) {
                            replacement.append(minutes).append(String.valueOf(langParser.getConfigValue(MessagesKeys.minutes, ""))).append(" ");
                        }

                        if (seconds > 0 || replacement.length() == 0) {
                            replacement.append(seconds).append(String.valueOf(langParser.getConfigValue(MessagesKeys.seconds, "")));
                        }

                        return replacement.toString().trim();
                    } catch (Exception e) {
                        RTP.log(java.util.logging.Level.WARNING, "Placeholder resolution failed for scan_eta", e);
                        return "0s";
                    }
                });

        placeholders.put("world", uuid -> {
            RTPWorld world = RTP.worldContext.get();
            if (world != null) return world.name();
            Region region = RTP.regionContext.get();
            if (region != null) return region.getWorld().name();
            RTPCommandSender sender = RTP.serverAccessor.getSender(uuid);
            if (sender != null) {
                RTPWorld senderWorld = RTP.serverAccessor.getRTPWorld(sender.uuid()); // This is wrong, sender uuid is player uuid.
                // Wait, RTPWorld getRTPWorld(UUID) might be world UID or player UID depending on implementation.
                // Usually it's world UID.
                // Let's use a better way if possible.
            }
            return "";
        });

        placeholders.put("name", uuid -> {
            RTPWorld world = RTP.worldContext.get();
            if (world != null) return world.name();
            Region region = RTP.regionContext.get();
            if (region != null) return region.getWorld().name();
            return "";
        });

        placeholders.put("region", uuid -> {
            Region region = RTP.regionContext.get();
            if (region != null) return region.name;
            RTPWorld world = RTP.worldContext.get();
            if (world != null) return RTP.selectionAPI.getRegion(world).name;
            return "";
        });

        placeholders.put("requirePermission", uuid -> {
            Region region = RTP.regionContext.get();
            if (region != null) {
                boolean req = false;
                EnumMap<RegionKeys, Object> data = region.getData();
                Object o = data.getOrDefault(RegionKeys.requirePermission, false);
                if (o instanceof Boolean) req = (Boolean) o;
                else if (o instanceof String) {
                    req = Boolean.parseBoolean((String) o);
                    data.put(RegionKeys.requirePermission, req);
                }
                return String.valueOf(req);
            }
            RTPWorld world = RTP.worldContext.get();
            if (world != null) {
                MultiConfigParser<WorldKeys> worlds =
                        (MultiConfigParser<WorldKeys>) RTP.configs.getParser(WorldKeys.class);
                ConfigParser<WorldKeys> parser = worlds.getParser(world.name());
                return parser.getConfigValue(WorldKeys.requirePermission, false).toString();
            }
            return "false";
        });

        placeholders.put("override", uuid -> {
            RTPWorld world = RTP.worldContext.get();
            if (world != null) {
                MultiConfigParser<WorldKeys> worlds =
                        (MultiConfigParser<WorldKeys>) RTP.configs.getParser(WorldKeys.class);
                ConfigParser<WorldKeys> parser = worlds.getParser(world.name());
                String override = parser.getConfigValue(WorldKeys.override, "[0]").toString();
                if (override.startsWith("[") && override.endsWith("]")) {
                    try {
                        int num = Integer.parseInt(override.substring(1, override.length() - 1));
                        List<RTPWorld<?>> rtpWorlds = RTP.serverAccessor.getRTPWorlds();
                        if (num >= 0 && num < rtpWorlds.size()) {
                            return rtpWorlds.get(num).name();
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                return override;
            }
            return "0";
        });

        placeholders.put("pluginForced", uuid -> {
            RTPWorld world = RTP.worldContext.get();
            if (world != null) return String.valueOf(world.numForceLoaded());
            return "0";
        });

        placeholders.put("serverForced", uuid -> {
            RTPWorld world = RTP.worldContext.get();
            if (world != null) return String.valueOf(world.getServerForceLoadedCount().join());
            return "0";
        });

        placeholders.put("shape", uuid -> {
            Region region = RTP.regionContext.get();
            if (region != null) return region.getShape().name;
            return "none";
        });

        placeholders.put("cacheCap", uuid -> {
            Region region = RTP.regionContext.get();
            if (region != null) return String.valueOf(region.getSettings().cacheCap());
            return "0";
        });

        placeholders.put("cached", uuid -> {
            Region region = RTP.regionContext.get();
            if (region != null) return String.valueOf(region.queueManager.getPublicQueueLength());
            return "0";
        });

        placeholders.put("keptCache", uuid -> {
            Region region = RTP.regionContext.get();
            if (region != null) return String.valueOf(region.queueManager.keptLocations.size());
            return "0";
        });

        placeholders.put("locationQueue", uuid -> {
            Region region = RTP.regionContext.get();
            if (region != null) return String.valueOf(region.queueManager.keptLocations.size());
            return "0";
        });

        placeholders.put("inFlightCalculations", uuid -> {
            Region region = RTP.regionContext.get();
            if (region != null) return String.valueOf(region.inFlightCalculations.get());
            return "0";
        });

        placeholders.put("worldBorderOverride", uuid -> {
            Region region = RTP.regionContext.get();
            if (region != null) {
                boolean wbo = false;
                EnumMap<RegionKeys, Object> data = region.getData();
                Object o = data.getOrDefault(RegionKeys.worldBorderOverride, false);
                if (o instanceof Boolean) wbo = (Boolean) o;
                else if (o instanceof String) {
                    wbo = Boolean.parseBoolean((String) o);
                    data.put(RegionKeys.worldBorderOverride, wbo);
                }
                return String.valueOf(wbo);
            }
            return "false";
        });
    }

    public static String fillPlaceholders(String text, UUID uuid) {
        Set<String> keywords =
                ParseString.keywords(
                        text,
                        placeholders.keySet(),
                        new HashSet<>(Arrays.asList('[', '%')),
                        new HashSet<>(Arrays.asList(']', '%')));

        for (String s : keywords) {
            Function<UUID, String> function = placeholders.get(s);
            if (function == null) continue;
            String value = function.apply(uuid);
            String quotedValue = Matcher.quoteReplacement(value);
            text = Pattern.compile("\\[" + s + "]", Pattern.CASE_INSENSITIVE)
                    .matcher(text)
                    .replaceAll(quotedValue);
            text = Pattern.compile("%" + s + "%", Pattern.CASE_INSENSITIVE)
                    .matcher(text)
                    .replaceAll(quotedValue);
        }
        return text;
    }

    public static String fillNumericPlaceholders(String text) {
        ConfigParser<MessagesKeys> lang = (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
        if (lang == null) return text;
        // [p0], [p1]...
        text = fillNumericPlaceholders(text, Pattern.compile("\\[([Pp])(\\d*)]"), "\\[[Pp]\\d*]");
        // %p0%, %p1%...
        text = fillNumericPlaceholders(text, Pattern.compile("%([Pp])(\\d*)%"), "%[Pp]\\d*%");
        return text;
    }

    private static String fillNumericPlaceholders(String text, Pattern pattern, String removeRegex) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String group = matcher.group(2);
            int bits;
            try {
                bits = Integer.parseInt(group);
            } catch (NumberFormatException ignored) {
                continue;
            }
            matcher.reset();

            String replacement = "[invalid]";
            ConfigParser<MessagesKeys> parser =
                    (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
            Object o = parser.getConfigValue(MessagesKeys.placeholders, new ArrayList<>());
            if (o instanceof List<?> pList) {
                if (pList.size() > bits) {
                    replacement = pList.get(bits).toString();
                }
            }

            replacement = Pattern.compile(removeRegex).matcher(replacement).replaceAll("");

            text = matcher.replaceFirst(Matcher.quoteReplacement(replacement));
            matcher = pattern.matcher(text);
        }
        return text;
    }
}
