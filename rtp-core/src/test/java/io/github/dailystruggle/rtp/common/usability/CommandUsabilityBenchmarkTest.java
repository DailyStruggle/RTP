package io.github.dailystruggle.rtp.common.usability;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.CoreCommandTreeBuilder;
import io.github.dailystruggle.rtp.common.commands.PlatformCommandParameters;
import io.github.dailystruggle.rtp.common.commands.RTPCmd;
import io.github.dailystruggle.rtp.common.commands.parameters.BiomeParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.WorldParameter;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.File;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comparative Usability & Token-Interaction Audit: RTP vs BetterRTP.
 *
 * <p>Measures:
 * <ul>
 *   <li><b>Total Input Tokens (TIT)</b>: Tokens typed, tab-selected, and retry penalties.</li>
 *   <li><b>Tab Steps to Discovery (TtD)</b>: Depth in candidate lists to reach target parameter.</li>
 *   <li><b>Order Tolerance</b>: Recovery/acceptance when user provides parameters out-of-order.</li>
 * </ul>
 */
public class CommandUsabilityBenchmarkTest {

    @TempDir
    File tempDir;

    private MockRTPServerAccessor accessor;
    private MockRTPPlayer player;
    private TestRTPCmd rtpCmd;
    private BetterRTPMockCommand betterRtpCmd;

    private static class TestRTPCmd extends BaseRTPCmdImpl implements RTPCmd {
        public TestRTPCmd() {
            super(null);
        }

        @Override
        public boolean onCommand(UUID senderId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            return onCommand(senderId, parameterValues, nextCommand, null);
        }

        @Override
        public boolean onCommand(UUID senderId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand, java.util.function.Consumer<String> messageMethod) {
            if (nextCommand != null) return true;
            return compute(senderId, parameterValues, nextCommand, messageMethod);
        }

        @Override public String name() { return "rtp"; }
        @Override public String permission() { return "rtp.use"; }
        @Override public String description() { return "rtp"; }
        @Override public void successEvent(RTPCommandSender sender, RTPPlayer player) {}
        @Override public void failEvent(RTPCommandSender sender, String msg) {}
    }

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir);
        player = new MockRTPPlayer(UUID.randomUUID(), "TestPlayer", null);
        accessor.addPlayer(player);

        // Register common worlds in mock server
        accessor.addWorld(new MockRTPWorld("world_nether"));

        // Setup RTP Command tree
        rtpCmd = new TestRTPCmd();
        PlatformCommandParameters platformParams = new PlatformCommandParameters() {
            @Override
            public CommandParameter playerParameter() {
                return new WorldParameter("rtp.other", "target player", (uuid, s) -> true);
            }

            @Override
            public CommandParameter worldParameter() {
                return new WorldParameter("rtp.world", "target world", (uuid, s) -> true);
            }
        };
        CoreCommandTreeBuilder.attachCommonParameters(rtpCmd, platformParams);
        CoreCommandTreeBuilder.attachCommonSubcommands(rtpCmd);

        // Override biome parameter with populated suggestions for usability testing
        Set<String> testBiomes = Set.of("desert", "plains", "forest", "nether_wastes", "soul_sand_valley");
        BiomeParameter populatedBiomeParam = new BiomeParameter(
                "rtp.biome",
                "select a biome",
                (uuid, s) -> testBiomes.contains(s)
        ) {
            @Override
            public Set<String> values() {
                return testBiomes;
            }
        };
        rtpCmd.addParameter("biome", populatedBiomeParam);

        // Setup BetterRTP mock command
        betterRtpCmd = new BetterRTPMockCommand(
                Set.of("world", "world_nether"),
                testBiomes
        );
    }

    @Test
    @DisplayName("Scenario 1: Simple /rtp (bare teleport) - Baseline parity")
    void scenario1_simpleRtp() {
        UserJourneyAuditor rtpJourney = new UserJourneyAuditor(rtpCmd, player);
        rtpJourney.execute(true);
        UserJourneyAuditor.UsabilityScore rtpScore = rtpJourney.score();

        UserJourneyAuditor betterJourney = new UserJourneyAuditor(betterRtpCmd, player);
        betterJourney.execute(true);
        UserJourneyAuditor.UsabilityScore betterScore = betterJourney.score();

        System.out.printf("[SCENARIO 1 - BARE RTP] RTP: %s | BetterRTP: %s%n", rtpScore, betterScore);
        assertTrue(rtpScore.totalTokens() <= betterScore.totalTokens());
    }

    @Test
    @DisplayName("Scenario 2: World-specific RTP - Tab-discovery of target world")
    void scenario2_worldRtp() {
        // RTP: types "w=", completes "world=world_nether"
        UserJourneyAuditor rtpJourney = new UserJourneyAuditor(rtpCmd, player);
        rtpJourney.tabDiscover("w", "world=")
                  .tabDiscover("world=world_n", "world=world_nether")
                  .execute(true);
        UserJourneyAuditor.UsabilityScore rtpScore = rtpJourney.score();

        // BetterRTP: types "w", tabs "world", then "world_n", tabs "world_nether"
        UserJourneyAuditor betterJourney = new UserJourneyAuditor(betterRtpCmd, player);
        betterJourney.tabDiscover("w", "world")
                     .tabDiscover("world_n", "world_nether")
                     .execute(true);
        UserJourneyAuditor.UsabilityScore betterScore = betterJourney.score();

        System.out.printf("[SCENARIO 2 - WORLD RTP] RTP: %s | BetterRTP: %s%n", rtpScore, betterScore);
        // Both complete target world in 2 tokens
        assertTrue(rtpScore.totalTokens() == betterScore.totalTokens());
    }

    @Test
    @DisplayName("Scenario 3: World + Biome RTP - Compound target discovery")
    void scenario3_worldAndBiomeRtp() {
        // RTP: "b" -> "biome=", "biome=des" -> "biome=desert", "w" -> "world=", "world=w" -> "world=world_nether"
        UserJourneyAuditor rtpJourney = new UserJourneyAuditor(rtpCmd, player);
        rtpJourney.tabDiscover("b", "biome=")
                  .tabDiscover("biome=des", "biome=desert")
                  .tabDiscover("w", "world=")
                  .tabDiscover("world=world_n", "world=world_nether")
                  .execute(true);
        UserJourneyAuditor.UsabilityScore rtpScore = rtpJourney.score();

        // BetterRTP: "world" -> <world> -> "biome" -> <biome>
        UserJourneyAuditor betterJourney = new UserJourneyAuditor(betterRtpCmd, player);
        betterJourney.tabDiscover("w", "world")
                     .tabDiscover("world_n", "world_nether")
                     .tabDiscover("b", "biome")
                     .tabDiscover("des", "desert")
                     .execute(true);
        UserJourneyAuditor.UsabilityScore betterScore = betterJourney.score();

        System.out.printf("[SCENARIO 3 - WORLD + BIOME] RTP: %s | BetterRTP: %s%n", rtpScore, betterScore);
        assertTrue(rtpScore.totalTokens() <= betterScore.totalTokens());
    }

    @Test
    @DisplayName("Scenario 4: Misordered Parameter Entry - Out-of-order tolerance")
    void scenario4_outOfOrderTolerance() {
        // User intends to specify biome before world:
        // RTP: order-free key-value parsing succeeds on 1st attempt without errors
        UserJourneyAuditor rtpJourney = new UserJourneyAuditor(rtpCmd, player);
        rtpJourney.tabDiscover("b", "biome=")
                  .tabDiscover("biome=des", "biome=desert")
                  .tabDiscover("w", "world=")
                  .tabDiscover("world=world_n", "world=world_nether")
                  .execute(true);
        UserJourneyAuditor.UsabilityScore rtpScore = rtpJourney.score();

        // BetterRTP: User tries biome first ("biome desert world world_nether")
        // Positional parser rejects with syntax error, forcing a retry sequence
        UserJourneyAuditor betterJourney = new UserJourneyAuditor(betterRtpCmd, player);
        betterJourney.tabDiscover("b", "biome")
                     .tabDiscover("des", "desert")
                     .type("world")
                     .type("world_nether")
                     .execute(false) // Fails due to positional mismatch
                     .clearTokens()
                     // Retry in mandatory order
                     .tabDiscover("w", "world")
                     .tabDiscover("world_n", "world_nether")
                     .tabDiscover("b", "biome")
                     .tabDiscover("des", "desert")
                     .execute(true);
        UserJourneyAuditor.UsabilityScore betterScore = betterJourney.score();

        System.out.printf("[SCENARIO 4 - ORDER TOLERANCE] RTP: %s | BetterRTP: %s%n", rtpScore, betterScore);
        // RTP incurs 0 retries and achieves lower TIT score than BetterRTP
        assertTrue(rtpScore.errorsIncurred() == 0);
        assertTrue(betterScore.errorsIncurred() > 0);
        assertTrue(rtpScore.titScore() < betterScore.titScore(),
                "RTP must have significantly lower TIT score on misordered input due to zero retry penalty");
    }

    @Test
    @DisplayName("Scenario 5: Empty-Token Parameter Exploration")
    void scenario5_emptyTokenExploration() {
        // Player types nothing and hits TAB to explore available capabilities
        List<String> rtpSuggestions = rtpCmd.onTabComplete(player.uuid(), player::hasPermission, new String[]{""});
        List<String> betterSuggestions = betterRtpCmd.onTabComplete(player.uuid(), player::hasPermission, new String[]{""});

        System.out.printf("[SCENARIO 5 - DISCOVERY OPTIONS] RTP options: %d %s | BetterRTP options: %d %s%n",
                rtpSuggestions.size(), rtpSuggestions, betterSuggestions.size(), betterSuggestions);

        // Both suggest meaningful entry points
        assertTrue(rtpSuggestions.contains("world="));
        assertTrue(rtpSuggestions.contains("biome="));
        assertTrue(betterSuggestions.contains("world"));
        assertTrue(betterSuggestions.contains("biome"));
    }
}
