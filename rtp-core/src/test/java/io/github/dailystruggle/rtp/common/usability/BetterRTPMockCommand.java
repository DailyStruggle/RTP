package io.github.dailystruggle.rtp.common.usability;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Headless test model replicating BetterRTP's positional command syntax:
 * <pre>
 *   /rtp
 *   /rtp world &lt;world&gt;
 *   /rtp world &lt;world&gt; biome &lt;biome&gt;
 *   /rtp biome &lt;biome&gt;
 *   /rtp player &lt;player&gt;
 * </pre>
 *
 * <p>Unlike RTP's order-independent key-value parameters (e.g. {@code /rtp world=X biome=Y}),
 * BetterRTP enforces positional token order where subcommands must follow a strict layout.
 */
public class BetterRTPMockCommand extends BaseRTPCmdImpl {

    private final Set<String> knownWorlds;
    private final Set<String> knownBiomes;

    public BetterRTPMockCommand(Set<String> worlds, Set<String> biomes) {
        super(null);
        this.knownWorlds = new HashSet<>(worlds);
        this.knownBiomes = new HashSet<>(biomes);
    }

    @Override
    public String name() {
        return "rtp";
    }

    @Override
    public String permission() {
        return "betterrtp.use";
    }

    @Override
    public boolean onCommand(UUID senderId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        return true;
    }

    @Override
    public List<String> onTabComplete(UUID callerId,
                                      Predicate<String> permissionCheckMethod,
                                      String[] args) {
        if (!permissionCheckMethod.test(permission())) {
            return Collections.emptyList();
        }

        // Positional parsing:
        // Position 0 (first arg): ["world", "biome", "player", "info", "help"]
        if (args.length <= 1) {
            String current = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
            List<String> sub = List.of("world", "biome", "player", "info", "help");
            return sub.stream().filter(s -> s.startsWith(current)).collect(Collectors.toList());
        }

        String first = args[0].toLowerCase(Locale.ROOT);

        if (first.equals("world")) {
            // Pos 1: <world>
            if (args.length == 2) {
                String current = args[1].toLowerCase(Locale.ROOT);
                return knownWorlds.stream()
                        .filter(w -> w.toLowerCase(Locale.ROOT).startsWith(current))
                        .sorted()
                        .collect(Collectors.toList());
            }
            // Pos 2: optional "biome"
            if (args.length == 3) {
                String current = args[2].toLowerCase(Locale.ROOT);
                return List.of("biome").stream()
                        .filter(s -> s.startsWith(current))
                        .collect(Collectors.toList());
            }
            // Pos 3: <biome>
            if (args.length == 4 && args[2].equalsIgnoreCase("biome")) {
                String current = args[3].toLowerCase(Locale.ROOT);
                return knownBiomes.stream()
                        .filter(b -> b.toLowerCase(Locale.ROOT).startsWith(current))
                        .sorted()
                        .collect(Collectors.toList());
            }
        } else if (first.equals("biome")) {
            // Pos 1: <biome>
            if (args.length == 2) {
                String current = args[1].toLowerCase(Locale.ROOT);
                return knownBiomes.stream()
                        .filter(b -> b.toLowerCase(Locale.ROOT).startsWith(current))
                        .sorted()
                        .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }

    @Override
    public CompletableFuture<Boolean> onCommand(UUID callerId,
                                                 Predicate<String> permissionCheckMethod,
                                                 Consumer<String> messageMethod,
                                                 String[] args) {
        if (!permissionCheckMethod.test(permission())) {
            messageMethod.accept("No permission");
            return CompletableFuture.completedFuture(false);
        }

        if (args.length == 0) {
            messageMethod.accept("Teleporting...");
            return CompletableFuture.completedFuture(true);
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("world")) {
            if (args.length < 2 || !knownWorlds.contains(args[1])) {
                messageMethod.accept("Invalid world");
                return CompletableFuture.completedFuture(false);
            }
            if (args.length > 2) {
                if (args.length == 4 && args[2].equalsIgnoreCase("biome") && knownBiomes.contains(args[3])) {
                    messageMethod.accept("Teleporting to world and biome...");
                    return CompletableFuture.completedFuture(true);
                }
                messageMethod.accept("Invalid syntax: expected /rtp world <world> [biome <biome>]");
                return CompletableFuture.completedFuture(false);
            }
            messageMethod.accept("Teleporting to world...");
            return CompletableFuture.completedFuture(true);
        } else if (first.equals("biome")) {
            if (args.length < 2 || !knownBiomes.contains(args[1])) {
                messageMethod.accept("Invalid biome");
                return CompletableFuture.completedFuture(false);
            }
            messageMethod.accept("Teleporting to biome...");
            return CompletableFuture.completedFuture(true);
        }

        messageMethod.accept("Unknown subcommand: " + first);
        return CompletableFuture.completedFuture(false);
    }
}
