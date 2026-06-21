package io.github.dailystruggle.commandsapi.common;

import java.util.UUID;

/**
 * Platform-supplied delivery seam for command reply messages.
 *
 * <p>Installed once per plugin via {@link CommandsAPI#setMessageSink(MessageSink)}
 * and consulted by the dispatch layer (and command {@code msgInvalidCommand} /
 * {@code msgBadParameter} defaults) to obtain the consumer that delivers
 * a raw message template to a given caller (see {@code CommandsAPI.messageMethodFor}).
 * This removes the need for each
 * command to carry a platform-specific {@code messageMethodFactory} or to
 * subclass a platform command type purely to format / audit reply messages.</p>
 *
 * <p>Implementations are responsible for any platform-specific formatting
 * (placeholders, colour codes) and auditing (e.g. REQ-RTP-S-004 interception)
 * required for the message to reach the caller and the console exactly once.</p>
 */
public interface MessageSink {

  /**
   * Delivers a single raw message template to {@code callerId}. The
   * implementation performs any formatting / auditing required by the
   * platform.
   *
   * @param callerId    the command sender's UUID ({@link CommandsAPI#serverId} for console)
   * @param rawTemplate the unformatted message template
   */
  void send(UUID callerId, String rawTemplate);
}
