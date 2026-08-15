package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code CommandTreeMenuBuilder.buildOptionsPicker} — the generic
 * finite-value picker driven by a key's {@code @options}/{@code @source}
 * directive (ADR-064 amendment). Each option row must stage
 * {@code paramName = value} via {@link MenuAction.StageConfigValue}.
 */
@DisplayName("config menu @options/@source finite-value picker")
class MenuOptionsPickerTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setup() {
        RTPTestSetup.install(tempDir.toFile());
    }

    @Test
    @DisplayName("layout: Back(OpenConfigFile) + header + one StageConfigValue row per option")
    void layout() {
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder();
        List<String> options = List.of("yaml", "sqlite", "mysql", "postgresql");

        MenuModel model = builder.buildOptionsPicker(
                UUID.randomUUID(), "database", "database.type", "sqlite", options);

        List<MenuLine> lines = model.pages().get(0).lines();
        assertEquals(2 + options.size(), lines.size());

        MenuFragment back = lines.get(0).fragments().get(0);
        assertInstanceOf(MenuAction.OpenConfigFile.class, back.action());
        assertEquals("database", ((MenuAction.OpenConfigFile) back.action()).fileName());

        assertNull(lines.get(1).fragments().get(0).action(), "header row is non-clickable");

        for (int i = 0; i < options.size(); i++) {
            MenuFragment row = lines.get(2 + i).fragments().get(0);
            assertInstanceOf(MenuAction.StageConfigValue.class, row.action());
            MenuAction.StageConfigValue stage = (MenuAction.StageConfigValue) row.action();
            assertEquals("database", stage.fileName());
            assertEquals("database.type", stage.paramName());
            assertEquals(options.get(i), stage.value());
        }
    }

    @Test
    @DisplayName("current value is starred in its row label")
    void currentStarred() {
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder();
        MenuModel model = builder.buildOptionsPicker(
                UUID.randomUUID(), "database", "database.type", "MYSQL",
                List.of("yaml", "mysql"));
        List<MenuLine> lines = model.pages().get(0).lines();
        // mysql row (index 3) carries the current marker (case-insensitive match).
        assertTrue(lines.get(3).fragments().get(0).text().contains("*"),
                "current option must be marked");
    }

    @Test
    @DisplayName("empty options list is rejected")
    void emptyRejected() {
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder();
        assertThrows(IllegalArgumentException.class, () -> builder.buildOptionsPicker(
                UUID.randomUUID(), "database", "database.type", null, List.of()));
    }

}
