package io.github.dailystruggle.rtp.common.commands.help;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.common.RTP;

public class SendMessage {
  public static void sendMessage(
      RTPCommandSender sender, String message, String hover, String click) {
    RTP.serverAccessor.sendMessage(sender.uuid(), message);
    // TODO: implement hover and click in RTPServerAccessor if needed
  }

  public static void sendMessage(Object sender, String message) {
    if (sender instanceof RTPCommandSender) {
      RTP.serverAccessor.sendMessage(((RTPCommandSender) sender).uuid(), message);
    }
  }

  public static void sendMessage(Object target1, Object target2, String message) {
    if (target1 instanceof RTPCommandSender && target2 instanceof RTPCommandSender) {
      RTP.serverAccessor.sendMessage(
          ((RTPCommandSender) target1).uuid(), ((RTPCommandSender) target2).uuid(), message);
    }
  }

  public static String formatNoColor(Object player, String text) {
    return text; // TODO: implement formatting
  }
}
