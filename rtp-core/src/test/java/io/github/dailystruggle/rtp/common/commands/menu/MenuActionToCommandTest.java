package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("MenuActionToCommand wire command encoding")
class MenuActionToCommandTest {

    @Test
    @DisplayName("toRunCommand returns null for null action or renderer-only actions")
    void toRunCommand_rendererOnlyAndNull() {
        assertNull(MenuActionToCommand.toRunCommand(null));
        assertNull(MenuActionToCommand.toRunCommand(new MenuAction.ChangePage(0)));
        assertNull(MenuActionToCommand.toRunCommand(new MenuAction.SuggestInput("foo")));
        assertNull(MenuActionToCommand.toRunCommand(new MenuAction.OpenExternalUrl(java.net.URI.create("https://example.com"))));
    }

    @Test
    @DisplayName("changePageCommand maps 0-indexed to 1-indexed command")
    void changePageCommand_mapping() {
        assertEquals("/rtp menu page n=1", MenuActionToCommand.changePageCommand(new MenuAction.ChangePage(0)));
        assertEquals("/rtp menu page n=5", MenuActionToCommand.changePageCommand(new MenuAction.ChangePage(4)));
    }

    @Test
    @DisplayName("buildRunCommand formats args with /rtp prefix")
    void buildRunCommand_formatting() {
        assertEquals("/rtp", MenuActionToCommand.buildRunCommand(new MenuAction.RunRtpCommand(new String[0])));
        assertEquals("/rtp reload all", MenuActionToCommand.buildRunCommand(new MenuAction.RunRtpCommand(new String[]{"reload", "all"})));
    }

    @Test
    @DisplayName("OpenMenu encodes pathArg correctly with empty and non-empty paths")
    void openMenu_formatting() {
        assertEquals("/rtp menu open", MenuActionToCommand.toRunCommand(new MenuAction.OpenMenu(new String[0])));
        assertEquals("/rtp menu open path=a.b.c", MenuActionToCommand.toRunCommand(new MenuAction.OpenMenu(new String[]{"a", "b", "c"})));
    }

    @Test
    @DisplayName("OpenParamPicker encodes parentPath and paramName")
    void openParamPicker_formatting() {
        assertEquals("/rtp menu picker param=radius",
                MenuActionToCommand.toRunCommand(new MenuAction.OpenParamPicker(new String[0], "radius")));
        assertEquals("/rtp menu picker path=regions.default param=radius",
                MenuActionToCommand.toRunCommand(new MenuAction.OpenParamPicker(new String[]{"regions", "default"}, "radius")));
    }

    @Test
    @DisplayName("PromptAnvilInput encodes prefill, path, mode, and param")
    void promptAnvilInput_formatting() {
        MenuAction.PromptAnvilInput p1 = new MenuAction.PromptAnvilInput(
                new String[0], "shape", "", MenuAction.Mode.RUN);
        assertEquals("/rtp menu anvil param=shape mode=run", MenuActionToCommand.toRunCommand(p1));

        MenuAction.PromptAnvilInput p2 = new MenuAction.PromptAnvilInput(
                new String[]{"custom"}, "radius", "1000", MenuAction.Mode.STAGE);
        assertEquals("/rtp menu anvil path=custom param=radius prefill=1000 mode=stage", MenuActionToCommand.toRunCommand(p2));
    }

    @Test
    @DisplayName("Config actions encode directory, file, key, subparam, and search")
    void configActions_formatting() {
        assertEquals("/rtp menu config", MenuActionToCommand.toRunCommand(new MenuAction.OpenConfigSelector("")));
        assertEquals("/rtp menu config dir=worlds", MenuActionToCommand.toRunCommand(new MenuAction.OpenConfigSelector("worlds")));

        assertEquals("/rtp menu config file=performance.yml",
                MenuActionToCommand.toRunCommand(new MenuAction.OpenConfigFile("performance.yml")));

        assertEquals("/rtp menu config file=performance.yml key=maxRadius",
                MenuActionToCommand.toRunCommand(new MenuAction.OpenConfigKey("performance.yml", "maxRadius")));

        assertEquals("/rtp menu config file=regions.yml key=shape type=circle",
                MenuActionToCommand.toRunCommand(new MenuAction.OpenConfigSubParamPage("regions.yml", "shape", "circle")));

        assertEquals("/rtp menu config search", MenuActionToCommand.toRunCommand(new MenuAction.OpenConfigSearchPrompt()));

        assertEquals("/rtp menu config search query=delay page=2",
                MenuActionToCommand.toRunCommand(new MenuAction.OpenConfigSearchResults("delay", 1)));
    }

    @Test
    @DisplayName("Static panel actions encode admin, front, visualizations")
    void staticPanels_formatting() {
        assertEquals("/rtp menu admin", MenuActionToCommand.toRunCommand(new MenuAction.OpenAdminPanel()));
        assertEquals("/rtp menu front", MenuActionToCommand.toRunCommand(new MenuAction.OpenFrontPage()));
        assertEquals("/rtp menu visualizations", MenuActionToCommand.toRunCommand(new MenuAction.OpenVisualizations()));
    }

    @Test
    @DisplayName("Info actions encode global, world, region scopes and switch to text")
    void infoActions_formatting() {
        MenuAction.InfoScopeToken global = new MenuAction.InfoScopeToken(MenuAction.InfoScopeToken.Kind.GLOBAL, "");
        MenuAction.InfoScopeToken world = new MenuAction.InfoScopeToken(MenuAction.InfoScopeToken.Kind.WORLD, "overworld");
        MenuAction.InfoScopeToken region = new MenuAction.InfoScopeToken(MenuAction.InfoScopeToken.Kind.REGION, "plains");

        assertEquals("/rtp menu info scope=global", MenuActionToCommand.toRunCommand(new MenuAction.OpenInfo(global)));
        assertEquals("/rtp menu info scope=world:overworld", MenuActionToCommand.toRunCommand(new MenuAction.OpenInfo(world)));
        assertEquals("/rtp menu info scope=region:plains", MenuActionToCommand.toRunCommand(new MenuAction.OpenInfo(region)));

        assertEquals("/rtp menu info scope=global text=true", MenuActionToCommand.toRunCommand(new MenuAction.SwitchInfoToText(global)));
    }

    @Test
    @DisplayName("Staging actions encode stage, unstage, apply, discard")
    void stagingActions_formatting() {
        assertEquals("/rtp menu stage file=config.yml key=radius value=500",
                MenuActionToCommand.toRunCommand(new MenuAction.StageConfigValue("config.yml", "radius", "500")));
        assertEquals("/rtp menu unstage file=config.yml key=radius",
                MenuActionToCommand.toRunCommand(new MenuAction.UnstageConfigValue("config.yml", "radius")));
        assertEquals("/rtp menu apply file=config.yml",
                MenuActionToCommand.toRunCommand(new MenuAction.ApplyStagedConfig("config.yml")));
        assertEquals("/rtp menu discard file=config.yml",
                MenuActionToCommand.toRunCommand(new MenuAction.DiscardStagedConfig("config.yml")));
    }

    @Test
    @DisplayName("MultiConfig actions encode kind, entry, op")
    void multiConfigActions_formatting() {
        assertEquals("/rtp menu multi kind=region",
                MenuActionToCommand.toRunCommand(new MenuAction.OpenMultiConfigSelector("region")));
        assertEquals("/rtp menu multi kind=region entry=custom_nether",
                MenuActionToCommand.toRunCommand(new MenuAction.OpenMultiConfigEntry("region", "custom_nether")));
        assertEquals("/rtp menu multi kind=region entry=custom_nether op=remove",
                MenuActionToCommand.toRunCommand(new MenuAction.MultiConfigMutate("region", "custom_nether", MenuAction.MultiConfigMutate.Op.REMOVE)));
    }

    @Test
    @DisplayName("OpenMap encodes visualization command with kind and region")
    void openMap_allKinds() {
        for (ChartSpec.Kind kind : ChartSpec.Kind.values()) {
            String cmd = MenuActionToCommand.toRunCommand(new MenuAction.OpenMap(kind, "default"));
            assertEquals("/rtp visualization " + MenuActionToCommand.visualizationKindLiteral(kind) + " region=default", cmd);
        }
    }
}
