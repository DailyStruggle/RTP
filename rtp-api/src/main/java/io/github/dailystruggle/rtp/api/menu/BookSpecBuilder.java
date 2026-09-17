package io.github.dailystruggle.rtp.api.menu;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Pure conversion utility from {@link MenuModel} to {@link BookSpec}.
 */
public final class BookSpecBuilder {

  private BookSpecBuilder() {
    // Utility class.
  }

  /**
   * Converts a {@link MenuModel} into a {@link BookSpec} using the active server accessor
   * or a custom formatter, plus an action mapper.
   *
   * @param playerId     viewer UUID
   * @param model        menu model to translate; must not be null
   * @param formatter    formatting function (uuid, rawText) -> formattedText; if null, uses accessor
   * @param actionMapper maps MenuAction to a runnable command string
   * @return fully-constructed BookSpec
   */
  public static BookSpec buildSpec(
      UUID playerId,
      MenuModel model,
      BiFunction<UUID, String, String> formatter,
      Function<MenuAction, String> actionMapper) {
    Objects.requireNonNull(model, "model");
    List<BookSpec.Page> pages = new ArrayList<>(model.pages().size());
    for (MenuPage page : model.pages()) {
      List<BookSpec.Line> lines = new ArrayList<>(page.lines().size());
      for (MenuLine line : page.lines()) {
        List<BookSpec.Fragment> frags = new ArrayList<>(line.fragments().size());
        for (MenuFragment fragment : line.fragments()) {
          frags.add(toFragment(playerId, fragment, formatter, actionMapper));
        }
        lines.add(new BookSpec.Line(frags));
      }
      pages.add(new BookSpec.Page(lines));
    }
    String formattedTitle = format(playerId, model.title(), formatter);
    return new BookSpec(formattedTitle, pages);
  }

  private static BookSpec.Fragment toFragment(
      UUID playerId,
      MenuFragment fragment,
      BiFunction<UUID, String, String> formatter,
      Function<MenuAction, String> actionMapper) {
    String text = format(playerId, fragment.text(), formatter);
    String hover = format(playerId, fragment.hover(), formatter);
    String runCommand = (actionMapper != null) ? actionMapper.apply(fragment.action()) : null;
    return new BookSpec.Fragment(text, hover, runCommand);
  }

  private static String format(UUID playerId, String raw, BiFunction<UUID, String, String> formatter) {
    if (raw == null || raw.isEmpty()) return raw;
    if (formatter != null) {
      return formatter.apply(playerId, raw);
    }
    try {
      RTPServerAccessor accessor = RTPAPI.serverAccessor;
      if (accessor != null) {
        UUID uuid = (playerId == null) ? RTPAPI.serverId : playerId;
        return accessor.format(uuid, raw);
      }
    } catch (Throwable ignored) {
    }
    return raw;
  }
}
