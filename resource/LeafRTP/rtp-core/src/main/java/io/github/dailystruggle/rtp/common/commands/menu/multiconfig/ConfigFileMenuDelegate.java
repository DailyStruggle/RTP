package io.github.dailystruggle.rtp.common.commands.menu.multiconfig;

import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Functional interface decoupling {@link MultiConfigMenuBuilder} from
 * the concrete command tree menu builder implementation.
 */
@FunctionalInterface
public interface ConfigFileMenuDelegate {
    /**
     * Builds a menu model for a specific config file/entry.
     *
     * @param viewer            UUID of the calling viewer
     * @param syntheticFileName synthetic file name/path
     * @param entryParser       parser holding the entry's configuration
     * @param cartSnapshot      staged cart modifications
     * @return populated {@link MenuModel}, or {@code null} on failure
     */
    @Nullable MenuModel buildConfigFile(
            UUID viewer,
            String syntheticFileName,
            ConfigParser<?> entryParser,
            LinkedHashMap<String, String> cartSnapshot);
}
