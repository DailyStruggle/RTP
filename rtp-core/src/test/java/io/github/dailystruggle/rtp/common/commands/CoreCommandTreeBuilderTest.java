package io.github.dailystruggle.rtp.common.commands;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.commands.parameters.WorldParameter;
import io.github.dailystruggle.rtp.common.commands.version.VersionCmd;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Focused coverage for the platform-agnostic command-tree registrar. Asserts
 * that {@link CoreCommandTreeBuilder} registers the expected common verb set
 * (with the {@link VersionCmd#ALIAS} lookup alias) and the common parameter
 * set, sourcing only {@code player}/{@code world} from the platform seam.
 */
public class CoreCommandTreeBuilderTest {

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir.toFile());
  }

  @Test
  void attachCommonSubcommands_registersExpectedVerbSet() {
    StubRoot root = new StubRoot();
    CoreCommandTreeBuilder.attachCommonSubcommands(root);

    Map<String, CommandsAPICommand> lookup = root.getCommandLookup();
    // Subcommands are keyed by name().toUpperCase() (TreeCommand#addSubCommand).
    assertTrue(lookup.containsKey("RELOAD"), "reload verb missing");
    assertTrue(lookup.containsKey("GUI"), "gui verb missing");
    assertTrue(lookup.containsKey("CONFIG"), "config verb missing");
    assertTrue(lookup.containsKey("SCAN"), "scan verb missing");
    assertTrue(lookup.containsKey("INFO"), "info verb missing");
    assertTrue(lookup.containsKey("VERSION"), "version verb missing");
    assertTrue(lookup.containsKey("CLEARCACHE"), "clearcache verb missing");

    // VersionCmd is additionally reachable under its ALIAS, pointing at the
    // same instance as the VERSION verb.
    assertTrue(
        lookup.containsKey(VersionCmd.ALIAS.toUpperCase()),
        "version alias '" + VersionCmd.ALIAS + "' missing");
    assertSame(
        lookup.get("VERSION"),
        lookup.get(VersionCmd.ALIAS.toUpperCase()),
        "version alias must resolve to the same VersionCmd instance");

    // No `help` subcommand: commands-api auto-emits the built-in help listing.
    assertTrue(!lookup.containsKey("HELP"), "help must not be registered as a subcommand");
  }

  @Test
  void attachCommonParameters_registersCommonAndPlatformParameters() {
    StubRoot root = new StubRoot();
    StubPlatformParameters platform = new StubPlatformParameters();
    CoreCommandTreeBuilder.attachCommonParameters(root, platform);

    Map<String, CommandParameter> params = root.getParameterLookup();
    // Parameters are keyed lowercased (TreeCommand#addParameter).
    assertTrue(params.containsKey("region"), "region parameter missing");
    assertTrue(params.containsKey("biome"), "biome parameter missing");
    assertTrue(params.containsKey("toggletargetperms"), "toggletargetperms parameter missing");

    // player / world come from the platform seam.
    assertSame(platform.player, params.get("player"), "player must be platform-supplied");
    assertSame(platform.world, params.get("world"), "world must be platform-supplied");
  }

  /** Minimal concrete {@code /rtp} root for exercising the builder. */
  private static final class StubRoot extends BaseRTPCmdImpl implements RTPCmd {
    StubRoot() {
      super(null);
    }

    @Override
    public String name() {
      return "rtp";
    }

    @Override
    public boolean onCommand(
        UUID senderId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
      return true;
    }

    @Override
    public void successEvent(RTPCommandSender sender, RTPPlayer player) {}

    @Override
    public void failEvent(RTPCommandSender sender, String msg) {}
  }

  /** Stub platform parameter source returning trivial, identifiable instances. */
  private static final class StubPlatformParameters implements PlatformCommandParameters {
    final CommandParameter player =
        new WorldParameter("rtp.other", "stub player", (uuid, s) -> true);
    final CommandParameter world =
        new WorldParameter("rtp.world", "stub world", (uuid, s) -> true);

    @Override
    public CommandParameter playerParameter() {
      return player;
    }

    @Override
    public CommandParameter worldParameter() {
      return world;
    }
  }
}
