package io.github.dailystruggle.rtp.common.action;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ActionKeys;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActionConfigLoaderCoverageTest {

  @AfterEach
  void tearDown() {
    RTPTestSetup.cleanUp();
  }

  /** Returns a real actions parser detached from its YAML document so the key-based path is taken. */
  private static ConfigParser<ActionKeys> keyedParser(Path tempDir) throws Exception {
    RTPTestSetup.install(tempDir.toFile());
    MultiConfigParser<ActionKeys> actions =
        new MultiConfigParser<>(ActionKeys.class, "actions", "1.0", tempDir.toFile(), "definitions/actions", "en");
    ConfigParser<ActionKeys> parser = actions.getParser("default");
    assertNotNull(parser);
    java.lang.reflect.Field f = ConfigParser.class.getDeclaredField("cachedLookup");
    f.setAccessible(true);
    f.set(parser, null);
    parser.pluginDirectory = null;
    assertNull(parser.getYamlRoot());
    return parser;
  }

  private static Map<String, Object> map(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }

  private static ActionDefinition parse(String yaml) {
    return ActionConfigLoader.parseDefinition("cov", RtpYamlConfig.parse(yaml));
  }

  @Test
  @DisplayName("Null YAML root yields default definition")
  void nullRootDefaults() {
    ActionDefinition def = ActionConfigLoader.parseDefinition("cov", (io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection) null);
    assertEquals("cov", def.alias());
    assertEquals("rtp.action.cov", def.permission());
    assertSame(ActionDefinition.PlacementSpec.DEFAULT, def.placement());
    assertSame(ActionDefinition.CommandSpec.EMPTY, def.command());
  }

  @Test
  @DisplayName("Null ConfigParser delegates to default definition")
  void nullConfigParserDefaults() {
    ActionDefinition def = ActionConfigLoader.parseDefinition("cov",
        (io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.ActionKeys>) null);
    assertEquals("cov", def.id());
    assertTrue(def.triggers().isEmpty());
  }

  @Test
  @DisplayName("Gate list of sections and plural gates section are both collected")
  void gateListAndPluralSection() {
    ActionDefinition list = parse("""
        gate:
          - type: PERMISSION
            value: a
          - type: COST
            value: 5
          - scalar
        """);
    assertEquals(2, list.gates().size());

    ActionDefinition plural = parse("""
        gates:
          type: PERMISSION
          value: b
        """);
    assertEquals(1, plural.gates().size());
    assertEquals("b", String.valueOf(plural.gates().get(0).get("value")));
  }

  @Test
  @DisplayName("Nested trigger section assigns ids from keys and parses coordinate variants")
  void nestedTriggerSection() {
    ActionDefinition def = parse("""
        triggers:
          alpha:
            type: step_in
            world: arena
            pos1: "1,2,3"
            pos2: "world,4,5,6"
            cooldown: 10
            batchInterval: 2
          beta:
            id: custom
            type: bogus
            worldName: nether
            min:
              x: 7
              y: 8
              z: 9
            max:
              x: 10
              y: 11
              z: 12
            cooldownSeconds: 3
            batch_interval: 4
          gamma:
            x1: "13"
            y1: "14"
            z1: "15"
            x2: 16
            y2: 17
            z2: 18
            interval: 5
        """);
    List<PhysicalTriggerSpec> triggers = def.triggers();
    assertEquals(2, triggers.size());
    PhysicalTriggerSpec alpha = triggers.get(0);
    assertEquals("alpha", alpha.id());
    assertEquals("arena", alpha.worldName());
    assertEquals(PhysicalTriggerSpec.TriggerType.STEP_IN, alpha.type());
    PhysicalTriggerSpec gamma = triggers.get(1);
    assertEquals("gamma", gamma.id());
    assertEquals("world", gamma.worldName());
    assertEquals(PhysicalTriggerSpec.TriggerType.STEP_IN, gamma.type());
  }

  @Test
  @DisplayName("Flat trigger section and trigger list of sections")
  void flatAndListTriggers() {
    ActionDefinition flat = parse("""
        trigger:
          minX: 1
          minY: 2
          minZ: 3
          maxX: bad
        """);
    assertEquals(1, flat.triggers().size());
    assertEquals("cov_trigger_1", flat.triggers().get(0).id());

    ActionDefinition list = parse("""
        trigger:
          - pos1: "a,b,c"
            pos2: "1,2"
          - worldName: w2
          - plain
        """);
    assertEquals(2, list.triggers().size());
    assertEquals("cov_trigger_2", list.triggers().get(1).id());
  }

  @Test
  @DisplayName("Command block parses alias fallback, alias list, parameter section and subcommand sections")
  void commandBlockSections() {
    ActionDefinition def = parse("""
        command:
          name: ""
          alias: main
          aliases:
            - " a "
            - b
          parameters:
            first:
              name: target
              type: STRING
            second:
              name: other
              type: qqqqzzzzxxxx
            third: scalar
          subcommands:
            join:
              aliases:
                - j
              actions:
                - CONSOLE: "say hi"
                - PLAYER: "spawn"
                - scalar
            leave:
              aliases: lv
              run:
                MESSAGE: bye
            bad: scalar
        """);
    ActionDefinition.CommandSpec cmd = def.command();
    assertEquals("main", cmd.name());
    assertEquals(List.of("a", "b"), cmd.aliases());
    assertEquals(2, cmd.parameters().size());
    assertEquals(io.github.dailystruggle.rtp.api.action.ParameterType.STRING, cmd.parameters().get(1).type());
    assertEquals(2, cmd.subcommands().size());
    ActionDefinition.SubcommandSpec join = cmd.subcommands().get("join");
    assertEquals(List.of("j"), join.aliases());
    assertEquals(2, join.actions().size());
    ActionDefinition.SubcommandSpec leave = cmd.subcommands().get("leave");
    assertEquals(List.of("lv"), leave.aliases());
    assertEquals(1, leave.actions().size());
    assertEquals(ActionDefinition.ActionType.MESSAGE, leave.actions().get(0).type());
  }

  @Test
  @DisplayName("Placement scalar shape, parameter section overrides and invalid retries fall back")
  void placementFallbacks() {
    ActionDefinition def = parse("""
        placement:
          shape: circle
          retries: 0
          cacheSize: -1
          enabled: "false"
          anchor: "  "
          parameters:
            minSeparation: 5
            elevationTolerance: 3
        """);
    ActionDefinition.PlacementSpec p = def.placement();
    assertEquals("CIRCLE", p.shapeName());
    assertFalse(p.enabled());
    assertEquals(5, p.minSeparation());
    assertEquals(3, p.elevationTolerance());
    assertEquals(0, p.cacheSize());
    assertFalse(p.parameters().containsKey("anchor"));

    ActionDefinition boolTrue = parse("placement: true\n");
    assertSame(ActionDefinition.PlacementSpec.DEFAULT, boolTrue.placement());

    ActionDefinition shapeSec = parse("""
        placement:
          retries: 7
          cacheSize: 4
          shape:
            radius: "notanumber"
        """);
    assertEquals(7, shapeSec.placement().retries());
    assertEquals(4, shapeSec.placement().cacheSize());
    assertEquals("SQUARE", shapeSec.placement().shapeName());
  }

  @Test
  @DisplayName("Confinement shape section with shape key, string damage and outside action aliases")
  void confinementAliases() {
    ActionDefinition first = parse("""
        confinement:
          shape:
            shape: circle
            radius: 10
            centerRadius: 2
          radius: 50
          cancellable: "false"
          damage: "2.5"
          damageBuffer: "x"
          damageInterval: 5s
          maxOutsideDistance: 4
          outsideAction: STOP
        """);
    ActionDefinition.ConfinementSpec c = first.confinement();
    assertEquals(io.github.dailystruggle.rtp.api.action.ConfinementBoundary.SHAPE, c.boundary());
    assertEquals("CIRCLE", c.shapeName());
    assertEquals(10, c.radius());
    assertEquals(2, c.centerRadius());
    assertFalse(c.cancellable());
    assertEquals(2.5, c.damageAmount());
    assertEquals(0.0, c.damageBuffer());
    assertEquals(4.0, c.maxDistanceOutside());
    assertEquals(1, c.outsideActions().size());
    assertEquals("STOP", c.outsideActions().get(0).payload());

    ActionDefinition second = parse("""
        confinement:
          radius: 30
          centerRadius: 5
          damageAmount: 1.5
          damageIntervalSeconds: 3
          outsideLimit: "7"
          onOutsideLimit:
            - CONSOLE: a
            - TELL: b
        """);
    ActionDefinition.ConfinementSpec c2 = second.confinement();
    assertEquals(io.github.dailystruggle.rtp.api.action.ConfinementBoundary.SHAPE, c2.boundary());
    assertEquals(30, c2.radius());
    assertEquals(5, c2.centerRadius());
    assertEquals(1.5, c2.damageAmount());
    assertEquals(3L, c2.damageIntervalSeconds());
    assertEquals(7.0, c2.maxDistanceOutside());
    assertEquals(2, c2.outsideActions().size());

    ActionDefinition third = parse("""
        confinement:
          damageAmount: "bad"
          maxDistanceOutside: "bad"
          outsideActions:
            - MSG: hi
            - FOR_EACH:
                CONSOLE: c
        """);
    ActionDefinition.ConfinementSpec c3 = third.confinement();
    assertEquals(io.github.dailystruggle.rtp.api.action.ConfinementBoundary.SUBSPACE, c3.boundary());
    assertEquals(0.0, c3.damageAmount());
    assertEquals(0.0, c3.maxDistanceOutside());
    assertEquals(2, c3.outsideActions().size());
    assertEquals(ActionDefinition.ActionType.FOR_EACH, c3.outsideActions().get(1).type());
  }

  @Test
  @DisplayName("Lifecycle gated steps with section, scalar and inline runs plus onWait alias")
  void lifecycleGatedSteps() {
    ActionDefinition def = parse("""
        lifecycle:
          onStart:
            - gate:
                type: X
              run:
                CONSOLE: a
            - gate:
                type: Y
              run: scalar
            - gate:
                type: Z
              delay: 5s
              MSG: hi
            - delaySeconds: 4
              PLAYER: p
            - scalar
          onWait:
            - MSG_TARGET: t
          onCancel:
            - gate:
                type: W
              run:
                - ACTION: a
                - FOR_EACH:
                    - CONSOLE: x
                - scalar
        """);
    ActionDefinition.LifecycleSpec life = def.lifecycle();
    assertEquals(4, life.onStart().size());
    assertEquals(1, life.onStart().get(0).actions().size());
    assertEquals("X", String.valueOf(life.onStart().get(0).gateConfig().get("type")));
    assertTrue(life.onStart().get(1).actions().isEmpty());
    assertEquals(5L, life.onStart().get(2).delaySeconds());
    assertEquals(ActionDefinition.ActionType.MESSAGE, life.onStart().get(2).actions().get(0).type());
    assertEquals(4L, life.onStart().get(3).delaySeconds());
    assertEquals(1, life.onEnqueue().size());
    assertEquals(ActionDefinition.ActionType.MESSAGE_TARGET, life.onEnqueue().get(0).actions().get(0).type());
    assertEquals(1, life.onCancel().get(0).actions().size());
    assertEquals(1, life.onCancel().get(0).actions().get(0).subActions().size());
  }

  @Test
  @DisplayName("Key-based parser path maps placement, confinement and command blocks given as plain maps")
  void keyedPlacementConfinementCommand(@TempDir Path tempDir) throws Exception {
    ConfigParser<ActionKeys> parser = keyedParser(tempDir);
    parser.set(ActionKeys.alias, "keyed");
    parser.set(ActionKeys.icon, "COMPASS");
    parser.set(ActionKeys.title, "Keyed");
    parser.set(ActionKeys.placement, map(
        "region", "r1",
        "shape", map("name", "circle", "radius", 100, "centerRadius", "2", "weight", 1.5),
        "parameters", map("minSeparation", 9, "elevationTolerance", 2, "extra", "v"),
        "anchor", "PLAYER",
        "enabled", true));
    parser.set(ActionKeys.confinement, map(
        "boundary", "SHAPE",
        "shape", map("radius", 20),
        "leashRadius", 10.0,
        "outsideActions", map("CONSOLE", "warn")));
    Map<String, Object> joinCfg = map(
        "aliases", Arrays.asList("j", null),
        "run", List.of(map("CONSOLE", "c"), map("FOR_EACH", map("PLAYER", "p"))));
    Map<String, Object> leaveCfg = map("run", map("FOR_EACH", List.of(map("ACTION", "x"))));
    parser.set(ActionKeys.command, map(
        "name", "kc",
        "aliases", Arrays.asList("k", null),
        "parameters", map("p", map("name", "who")),
        "subcommands", map("join", joinCfg, "leave", leaveCfg, "", map(), "skip", "scalar")));

    ActionDefinition def = ActionConfigLoader.parseDefinition("keyed", parser);
    assertEquals("keyed", def.alias());
    assertEquals("COMPASS", def.icon());
    assertEquals("Keyed", def.title());
    assertEquals("r1", def.placement().region());
    assertEquals("CIRCLE", def.placement().shapeName());
    assertEquals(100, def.placement().radius());
    assertEquals(2, def.placement().centerRadius());
    assertEquals(9, def.placement().minSeparation());
    assertEquals("v", def.placement().parameters().get("extra"));
    assertEquals(io.github.dailystruggle.rtp.api.action.ConfinementBoundary.SHAPE, def.confinement().boundary());
    assertEquals(20, def.confinement().radius());
    assertEquals(10.0, def.confinement().leashRadius());
    assertEquals(1, def.confinement().outsideActions().size());
    ActionDefinition.CommandSpec cmd = def.command();
    assertEquals("kc", cmd.name());
    assertEquals(List.of("k"), cmd.aliases());
    assertEquals(io.github.dailystruggle.rtp.api.action.ParameterType.STRING, cmd.parameters().get(0).type());
    assertEquals(2, cmd.subcommands().size());
    assertEquals(List.of("j"), cmd.subcommands().get("join").aliases());
    assertEquals(2, cmd.subcommands().get("join").actions().size());
    assertEquals(ActionDefinition.ActionType.FOR_EACH, cmd.subcommands().get("leave").actions().get(0).type());
  }

  @Test
  @DisplayName("Key-based parser path maps lifecycle, gate list and nested trigger maps")
  void keyedLifecycleGatesTriggers(@TempDir Path tempDir) throws Exception {
    ConfigParser<ActionKeys> parser = keyedParser(tempDir);
    parser.set(ActionKeys.lifecycle, map(
        "onStart", List.of(
            map("gate", map("type", "A"), "run", map("CONSOLE", "x")),
            map("gate", "notamap", "run", List.of(map("PLAYER", "y"))),
            map("FOR_EACH", List.of(map("TELL_TARGET", "t")))),
        "onDeath", "notalist"));
    parser.set(ActionKeys.gate, List.of(map("type", "PERMISSION"), "scalar"));
    parser.set(ActionKeys.trigger, map(
        "t1", map("pos1", map("x", 1, "y", 2, "z", 3), "pos2", map("x", 4, "y", 5, "z", 6)),
        "t2", map("id", "explicit", "type", "PORTAL"),
        "note", "scalar"));

    ActionDefinition def = ActionConfigLoader.parseDefinition("keyed", parser);
    List<ActionDefinition.LifecycleStep> onStart = def.lifecycle().onStart();
    assertEquals(3, onStart.size());
    assertEquals("A", onStart.get(0).gateConfig().get("type"));
    assertTrue(onStart.get(1).gateConfig().isEmpty());
    assertEquals(1, onStart.get(1).actions().size());
    assertEquals(ActionDefinition.ActionType.FOR_EACH, onStart.get(2).actions().get(0).type());
    assertTrue(def.lifecycle().onDeath().isEmpty());
    assertEquals(1, def.gates().size());
    assertEquals(2, def.triggers().size());
    PhysicalTriggerSpec t1 = def.triggers().get(0);
    assertEquals("t1", t1.id());
    assertEquals(1, t1.minX());
    assertEquals(6, t1.maxZ());
    assertEquals("explicit", def.triggers().get(1).id());
    assertEquals(PhysicalTriggerSpec.TriggerType.PORTAL, def.triggers().get(1).type());
  }

  @Test
  @DisplayName("Key-based parser path accepts single gate map plus flat and listed trigger maps")
  void keyedSingleGateAndFlatTriggers(@TempDir Path tempDir) throws Exception {
    ConfigParser<ActionKeys> parser = keyedParser(tempDir);
    parser.set(ActionKeys.gate, map("type", "COST"));
    parser.set(ActionKeys.trigger, map("minX", "5", "maxX", "bad", "world", "w"));
    ActionDefinition flat = ActionConfigLoader.parseDefinition("keyed", parser);
    assertEquals(1, flat.gates().size());
    assertEquals(1, flat.triggers().size());
    assertEquals(5, flat.triggers().get(0).minX());
    assertEquals("w", flat.triggers().get(0).worldName());

    parser.set(ActionKeys.trigger, List.of(map("min", map("x", 1), "max", 42), "scalar"));
    ActionDefinition listed = ActionConfigLoader.parseDefinition("keyed", parser);
    assertEquals(1, listed.triggers().size());
    assertEquals("keyed_trigger_1", listed.triggers().get(0).id());
  }

  @Test
  @DisplayName("Key-based confinement maps outside action list, map and string aliases")
  void keyedConfinementOutsideAliases(@TempDir Path tempDir) throws Exception {
    ConfigParser<ActionKeys> parser = keyedParser(tempDir);
    parser.set(ActionKeys.confinement, map("outsideAction", map("MSG", "m")));
    assertEquals(1, ActionConfigLoader.parseDefinition("k", parser).confinement().outsideActions().size());
    parser.set(ActionKeys.confinement, map("outsideAction", List.of(map("CONSOLE", "a"))));
    assertEquals(1, ActionConfigLoader.parseDefinition("k", parser).confinement().outsideActions().size());
    parser.set(ActionKeys.confinement, map("onOutsideLimit", "STOP"));
    assertEquals("STOP", ActionConfigLoader.parseDefinition("k", parser).confinement().outsideActions().get(0).payload());
    parser.set(ActionKeys.confinement, map("onOutsideLimit", map("PLAYER", "p")));
    assertEquals(1, ActionConfigLoader.parseDefinition("k", parser).confinement().outsideActions().size());
    parser.set(ActionKeys.confinement, map("outsideActions", "STOP", "boundary", "   "));
    ActionDefinition.ConfinementSpec c = ActionConfigLoader.parseDefinition("k", parser).confinement();
    assertEquals("STOP", c.outsideActions().get(0).payload());
    assertEquals(io.github.dailystruggle.rtp.api.action.ConfinementBoundary.SUBSPACE, c.boundary());
  }

  @Test
  @DisplayName("Detached ConfigParser falls back to reading its YAML file from its own or the server directory")
  void keyedParserFileFallback(@TempDir Path tempDir) throws Exception {
    ConfigParser<ActionKeys> parser = keyedParser(tempDir);
    Path own = tempDir.resolve("own");
    Files.createDirectories(own);
    Files.writeString(own.resolve(parser.name), "alias: fromfile\n");
    parser.pluginDirectory = own.toFile();
    assertEquals("fromfile", ActionConfigLoader.parseDefinition("keyed", parser).alias());

    Path empty = tempDir.resolve("empty");
    Files.createDirectories(empty);
    parser.pluginDirectory = empty.toFile();
    assertNotNull(ActionConfigLoader.parseDefinition("keyed", parser));

    Path dirAsFile = tempDir.resolve("dirAsFile");
    Files.createDirectories(dirAsFile.resolve(parser.name));
    parser.pluginDirectory = dirAsFile.toFile();
    parser.set(ActionKeys.alias, "keyedalias");
    // Unreadable file (a directory) is swallowed and the key-based mapping is used.
    assertEquals("keyedalias", ActionConfigLoader.parseDefinition("keyed", parser).alias());
  }

  @Test
  @DisplayName("Misspelled confinement boundary is autocorrected to the closest candidate")
  void confinementBoundaryAutocorrect() {
    ActionDefinition def = parse("""
        confinement:
          boundary: LEASHH
          leashRadius: -5
          initialSize: 100
          shrinkTo: 10
          shrinkOver: 1m
        """);
    ActionDefinition.ConfinementSpec c = def.confinement();
    assertEquals(io.github.dailystruggle.rtp.api.action.ConfinementBoundary.LEASH, c.boundary());
    assertEquals(64.0, c.leashRadius());
    assertEquals(100.0, c.initialSize());
    assertEquals(10.0, c.shrinkTo());
    assertEquals(60L, c.shrinkOverSeconds());
  }

  @Test
  @DisplayName("Legacy filesystem loader registers valid files and logs broken ones")
  void legacyLoaderParsesFiles(@TempDir Path tempDir) throws Exception {
    Path dir = tempDir.resolve("definitions/actions");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("good.yml"), "alias: goodalias\n");
    Files.writeString(dir.resolve("broken.yml"), "command:\n  parameters:\n    - name: \"\"\n");
    Files.writeString(dir.resolve(".hidden.yml"), "alias: hidden\n");
    Files.writeString(dir.resolve("note.txt"), "alias: txt\n");
    ActionManager manager = new ActionManager();
    ActionConfigLoader.loadActions(tempDir.toFile(), manager);
    assertTrue(manager.getAction("good").isPresent());
    assertTrue(manager.getAction("broken").isEmpty());
    assertTrue(manager.getAction(".hidden").isEmpty());
  }

  @Test
  @DisplayName("Legacy filesystem loader tolerates null and missing directories")
  void legacyLoaderGuards(@TempDir Path tempDir) throws Exception {
    ActionManager manager = new ActionManager();
    ActionConfigLoader.loadActions((File) null, manager);
    ActionConfigLoader.loadActions(tempDir.toFile(), null);
    ActionConfigLoader.loadActions(tempDir.toFile(), manager);
    Path actions = tempDir.resolve("definitions");
    Files.createDirectories(actions);
    Files.writeString(actions.resolve("actions"), "not a dir");
    ActionConfigLoader.loadActions(tempDir.toFile(), manager);
    assertTrue(manager.getAction("anything").isEmpty());
  }
}
