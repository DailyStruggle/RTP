package io.github.dailystruggle.rtp.common.commands.config;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /rtp config <file> view} &mdash; opens the interactive book menu for
 * the targeted configuration file (proposal v3.6.1, checklist step 6).
 *
 * <p>When a {@link BookViewOpener} has been wired (e.g. by the Bukkit family
 * via {@code RTPCmdBukkit}), invoking this command mints an
 * {@code OpenConfigFile(name)} action through the menu pipeline and delegates
 * rendering to the active {@code MenuRenderer}. On platforms where no opener
 * has been wired (or where the wiring deliberately returns {@code false}),
 * the command falls through to the legacy raw-dump path provided by
 * {@link ViewRawSubConfigCmd} so {@code /rtp config <file> view} never feels
 * broken on Spigot / Fabric (REQ-RTP-S-007 spirit).
 *
 * <p>Read-only by design: this command never mutates state.
 */
public class ViewSubConfigCmd extends BaseRTPCmdImpl {

  /**
   * Service hook for opening the book-form config view for a given file name
   * and caller. Implementations live in platform wiring (e.g. {@code rtp-plugin}'s
   * {@code RTPCmdBukkit}) where the {@code MenuRenderer},
   * {@code MenuConfigSubtreeBuilder} and {@code MenuTokenRegistry} are wired.
   *
   * <p>Implementations should return {@code true} when the book was opened
   * (or a configurable rejection was sent), and {@code false} when this
   * command should fall through to the legacy raw-dump path.
   */
  @FunctionalInterface
  public interface BookViewOpener {
    boolean open(UUID viewer, String fileName);
  }

  private static final AtomicReference<BookViewOpener> OPENER = new AtomicReference<>();

  /**
   * Installs the platform's book-view opener. Pass {@code null} to clear.
   * Idempotent; later wiring replaces earlier wiring.
   */
  public static void setBookViewOpener(@Nullable BookViewOpener opener) {
    OPENER.set(opener);
  }

  /** Test / introspection hook: current opener, or {@code null} if unwired. */
  public static @Nullable BookViewOpener getBookViewOpener() {
    return OPENER.get();
  }

  private final ConfigParser<?> configParser;

  public ViewSubConfigCmd(@Nullable CommandsAPICommand parent, @NotNull ConfigParser<?> configParser) {
    super(parent);
    this.configParser = configParser;
  }

  @Override
  public String name() {
    return "view";
  }

  @Override
  public String permission() {
    return "rtp.config";
  }

  @Override
  public String description() {
    return "open this configuration file as an interactive book";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

    BookViewOpener opener = OPENER.get();
    if (opener != null) {
      try {
        if (opener.open(callerId, configParser.name)) {
          return true;
        }
      } catch (RuntimeException e) {
        RTP.log(
            Level.WARNING,
            "[RTP config/view] book opener threw for "
                + configParser.name
                + "; falling through to raw dump: "
                + e.getMessage(),
            e);
      }
    }

    // Fallthrough: legacy raw-dump path. Reuses ViewRawSubConfigCmd so the
    // two code paths share the truncation / read / error semantics.
    String hint =
        "&7[RTP config/view] book menu unavailable on this platform; falling back to raw dump. "
            + "Use &f/rtp config "
            + configParser.name
            + " viewraw&7 for this view directly.";
    RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, hint);
    return new ViewRawSubConfigCmd(null, configParser).onCommand(callerId, parameterValues, null);
  }
}
