package io.github.dailystruggle.rtp.common.commands.admin;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.jetbrains.annotations.Nullable;

/**
 * Shared base for the player-targeted {@code /rtp clear} children
 * ({@code cooldown}, {@code queue}, {@code limit}, {@code invuln}).
 *
 * <p>All of these share the same targeting grammar and resolution rules; only
 * the per-player and global clearing actions differ. Subclasses implement
 * {@link #clearOne(UUID)} (clear a single player and report whether anything was
 * cleared) and {@link #clearAll()} (wipe the state for every player), plus a few
 * naming hooks.
 *
 * <p>Targeting via the optional {@code player} parameter:
 * <ul>
 *   <li>{@code /rtp clear <verb>} - clears the caller's own state when run by a
 *       player; clears <em>every</em> player's state when run from the console
 *       (the {@link RTPAPI#serverId} sentinel).</li>
 *   <li>{@code /rtp clear <verb> player=a,b,c} - clears just the named players,
 *       resolved by online name; unknown names are reported, not silently
 *       dropped (S-004).</li>
 * </ul>
 *
 * <p>Permission: {@code rtp.admin}.
 */
public abstract class PlayerTargetedClearCmd extends BaseRTPCmdImpl {

  /** Permission key shared by every player-targeted clear verb. */
  public static final String PERMISSION = "rtp.admin";

  /**
   * Constructs the verb and declares its optional {@code player} parameter so
   * {@code player=<name>[,<name>...]} routes through the command framework.
   * Tab-completion is intentionally free-form (no value list): the set of
   * resolvable players is platform-specific and supplied at runtime via
   * {@link RTP#serverAccessor}.
   *
   * @param parent the {@code /rtp clear} parent command, or {@code null}
   */
  protected PlayerTargetedClearCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addParameter(
        "player",
        new CommandParameter(
            PERMISSION, "target player(s); omit for self (or all from console)", (uuid, s) -> true) {
          @Override
          public Set<String> values() {
            return Collections.emptySet();
          }
        });
  }

  @Override
  public String permission() {
    return PERMISSION;
  }

  /**
   * Clear the targeted state for a single player.
   *
   * @param uuid target player uuid
   * @return {@code true} if any state existed for the player and was cleared
   */
  protected abstract boolean clearOne(UUID uuid);

  /**
   * Clear the targeted state for every player.
   *
   * @return the number of players whose state was cleared
   */
  protected abstract int clearAll();

  /**
   * Human-readable label for the cleared state, used in caller feedback and the
   * audit log line (e.g. {@code "teleport cooldown"}).
   *
   * @return the state label
   */
  protected abstract String label();

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, @Nullable CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

    List<String> names = expand(parameterValues == null ? null : parameterValues.get("player"));

    final List<UUID> targets = new ArrayList<>();
    final boolean global;
    if (names.isEmpty()) {
      // No explicit target: self for a player caller, all players for console.
      if (callerId != null && !callerId.equals(RTPAPI.serverId)) {
        targets.add(callerId);
        global = false;
      } else {
        global = true;
      }
    } else {
      global = false;
      for (String n : names) {
        RTPPlayer p = (RTP.serverAccessor != null) ? RTP.serverAccessor.getPlayer(n) : null;
        if (p == null) {
          String msg = "RTP: unknown player - " + n;
          RTP.log(Level.WARNING, "[RTP] clear " + name() + ": " + msg + " (by " + callerId + ")");
          sendToCaller(callerId, msg);
          continue;
        }
        targets.add(p.uuid());
      }
    }

    int cleared;
    if (global) {
      cleared = clearAll();
    } else {
      cleared = 0;
      for (UUID id : targets) {
        if (clearOne(id)) cleared++;
      }
    }

    String scope = global ? "all players" : (cleared + " player(s)");
    String msg = "cleared " + label() + " for " + scope;
    RTP.log(Level.INFO, "[RTP] clear " + name() + ": " + msg + " (by " + callerId + ")");
    sendToCaller(callerId, "RTP: " + msg);
    return true;
  }

  /**
   * Flatten the raw {@code player} parameter values into individual names,
   * splitting any comma-separated entry (so both {@code player=a,b,c} and
   * repeated {@code player=a player=b} forms are honoured) and dropping blanks.
   *
   * @param raw the raw parameter values, or {@code null}
   * @return the flattened, trimmed, non-blank player names
   */
  protected static List<String> expand(@Nullable List<String> raw) {
    List<String> out = new ArrayList<>();
    if (raw == null) return out;
    for (String entry : raw) {
      if (entry == null) continue;
      for (String part : entry.split(",")) {
        String trimmed = part.trim();
        if (!trimmed.isEmpty()) out.add(trimmed);
      }
    }
    return out;
  }

  /**
   * Best-effort feedback to the caller; never fatal on a headless / test scaffold.
   *
   * @param callerId the caller uuid, or {@code null}
   * @param message  the message to send
   */
  protected static void sendToCaller(@Nullable UUID callerId, String message) {
    if (callerId == null || RTP.serverAccessor == null) return;
    try {
      RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, message);
    } catch (RuntimeException ignored) {
      // serverAccessor failures (headless / test scaffolds) are not fatal.
    }
  }
}
