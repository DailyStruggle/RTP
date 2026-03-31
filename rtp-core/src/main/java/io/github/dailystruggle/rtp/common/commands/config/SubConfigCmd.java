package io.github.dailystruggle.rtp.common.commands.config;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.parameters.*;
import io.github.dailystruggle.rtp.common.commands.reload.ReloadCmd;
import io.github.dailystruggle.rtp.common.commands.config.list.ListCmd;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simpleyaml.configuration.ConfigurationSection;
import org.simpleyaml.configuration.MemorySection;
import org.simpleyaml.configuration.file.YamlFile;

public class SubConfigCmd extends BaseRTPCmdImpl {

  private final String name;
  private final FactoryValue<?> factoryValue;

  public SubConfigCmd(
      @Nullable CommandsAPICommand parent, String name, FactoryValue<?> factoryValue) {
    super(parent);
    this.name = name.toLowerCase();
    this.factoryValue = factoryValue;
    addParameters();
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String permission() {
    return "rtp.config";
  }

  @Override
  public String description() {
    return "update sections of this configuration";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    ConfigParser<MessagesKeys> lang =
            (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
    String updateMsg = String.valueOf(lang.getConfigValue(MessagesKeys.updating, ""));
    if (updateMsg != null) updateMsg = updateMsg.replace("[filename]", factoryValue.name);
    RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, updateMsg);

    RTP.scheduler.runTaskAsynchronously(() -> {
      if (factoryValue instanceof ConfigParser) {
        ConfigParser<?> configParser = (ConfigParser<?>) factoryValue;

      if (parameterValues.containsKey("world") && configParser.myClass.equals(RegionKeys.class))
        vertFixBlock:
        {
          ConfigParser<RegionKeys> regionParser = (ConfigParser<RegionKeys>) configParser;

          RTPWorld rtpWorld = RTP.serverAccessor.getRTPWorld(parameterValues.get("world").get(0));
          if (rtpWorld == null) break vertFixBlock;

          String name = "JUMP";
          int maxY = 255;
          int minY = 0;

          Object o = regionParser.getConfigValue(RegionKeys.vert, null);
          if (o instanceof ConfigurationSection) {
            ConfigurationSection section = (ConfigurationSection) o;
            name =
                parameterValues.containsKey("vert")
                    ? parameterValues.get("vert").get(0)
                    : section.getString("name");
            if (name == null) break vertFixBlock;

            String maxYStr =
                parameterValues.containsKey("maxy")
                    ? parameterValues.get("maxy").get(0)
                    : section.getString("maxY").replace(",", ".");

            String minYStr =
                parameterValues.containsKey("miny")
                    ? parameterValues.get("miny").get(0)
                    : section.getString("minY").replace(",", ".");

            try {
              maxY = ((Number) Double.parseDouble(maxYStr)).intValue();
              minY = ((Number) Double.parseDouble(minYStr)).intValue();
            } catch (IllegalArgumentException ignored) {

            }
          } else if (o instanceof VerticalAdjustor<?>) {
            VerticalAdjustor<?> vert = (VerticalAdjustor<?>) o;
            name = vert.name;
            maxY = vert.maxY();
            minY = vert.minY();
          }

          parameterValues.putIfAbsent("vert", Collections.singletonList(name));
          if (rtpWorld.name().endsWith("_nether")) {
            maxY = Math.min(maxY, 128);
            parameterValues.putIfAbsent(
                "requireskylight", Collections.singletonList(String.valueOf(false)));
          } else if (rtpWorld.name().endsWith("_the_end")) {
            parameterValues.putIfAbsent(
                "requireskylight", Collections.singletonList(String.valueOf(false)));
          }
          maxY = Math.min(maxY, rtpWorld.getMaxHeight());

          if (maxY < minY) {
            minY = rtpWorld.getMinHeight();
          } else {
            minY = Math.max(minY, rtpWorld.getMinHeight());
          }

          parameterValues.putIfAbsent("miny", Collections.singletonList(String.valueOf(minY)));
          parameterValues.putIfAbsent("maxy", Collections.singletonList(String.valueOf(maxY)));
        }

      for (Map.Entry<String, List<String>> e : parameterValues.entrySet()) {
        String key = e.getKey();
        Object value = e.getValue().get(0);

        if (key == null || value == null) continue;
        if (!getParameterLookup().containsKey(key.toLowerCase())) continue;

        if (configParser.myClass.equals(io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys.class) && key.equalsIgnoreCase("database.type")) {
          Object oldValue = ((ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys>)configParser).getConfigValue(io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys.database, "yaml");
          String oldStr = String.valueOf(oldValue);
          String newStr = value.toString();
          RTP.handleMigration(oldStr, newStr);
        }

        // todo: shape and vert updates
        if (key.equalsIgnoreCase("shape")) {
          Factory<Shape<?>> factory =
              (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
          if (factory == null) continue;
          Shape<?> shape = (Shape<?>) factory.get(value.toString());
          if (shape == null) msgBadParameter(callerId, key, value.toString());

          EnumMap<?, Object> data = shape.getData();

          Map<String, Object> subParams = new HashMap<>();
          subParams.put("name", shape.name);
          for (Map.Entry<? extends Enum<?>, Object> entry : data.entrySet()) {
            subParams.put(entry.getKey().name(), entry.getValue());
          }

          YamlFile yamlFile = configParser.fileDatabase.cachedLookup.get().get(configParser.name);
          if (yamlFile != null) {
            Object o = yamlFile.get(key);
            if (o instanceof ConfigurationSection) {
              ConfigurationSection section = (ConfigurationSection) o;
              Map<String, Object> mapValues = section.getMapValues(false);
              for (Map.Entry<String, Object> entry : mapValues.entrySet()) {
                if (subParams.containsKey(entry.getKey()))
                  subParams.put(entry.getKey(), entry.getValue());
              }
            } else if (o instanceof Map) {
              Map<String, Object> mapValues = (Map<String, Object>) o;
              for (Map.Entry<String, Object> entry : mapValues.entrySet()) {
                if (subParams.containsKey(entry.getKey()))
                  subParams.put(entry.getKey(), entry.getValue());
              }
            }
          }

          subParams.put("name", shape.name);
          for (Map.Entry<? extends Enum<?>, Object> entry : data.entrySet()) {
            String name = entry.getKey().name();
            List<String> strings = parameterValues.get(name.toLowerCase());
            if (strings != null && !strings.isEmpty()) {
              subParams.put(name, strings.get(0));
            }
          }
          value = subParams;
        } else if (key.equalsIgnoreCase("vert")) {
          Factory<VerticalAdjustor<?>> factory =
              (Factory<VerticalAdjustor<?>>) RTP.factoryMap.get(RTP.factoryNames.vert);
          if (factory == null) continue;
          VerticalAdjustor<?> vert = (VerticalAdjustor<?>) factory.get(value.toString());
          if (vert == null) msgBadParameter(callerId, key, value.toString());

          EnumMap<? extends Enum<?>, Object> vertData = vert.getData();

          Map<String, Object> subParams = new HashMap<>();
          subParams.put("name", vert.name);
          for (Map.Entry<? extends Enum<?>, Object> entry : vertData.entrySet()) {
            subParams.put(entry.getKey().name(), entry.getValue());
          }

          YamlFile yamlFile = configParser.fileDatabase.cachedLookup.get().get(configParser.name);
          if (yamlFile != null) {
            Object o = yamlFile.get(key);
            if (o instanceof ConfigurationSection) {
              ConfigurationSection section = (ConfigurationSection) o;
              Map<String, Object> mapValues = section.getMapValues(false);
              for (Map.Entry<String, Object> entry : mapValues.entrySet()) {
                if (subParams.containsKey(entry.getKey()))
                  subParams.put(entry.getKey(), entry.getValue());
              }
            } else if (o instanceof Map) {
              Map<String, Object> mapValues = (Map<String, Object>) o;
              for (Map.Entry<String, Object> entry : mapValues.entrySet()) {
                if (subParams.containsKey(entry.getKey()))
                  subParams.put(entry.getKey(), entry.getValue());
              }
            }
          }

          subParams.put("name", vert.name);
          for (Map.Entry<? extends Enum<?>, Object> entry : vertData.entrySet()) {
            String name = entry.getKey().name();
            List<String> strings = parameterValues.get(name.toLowerCase());
            if (strings != null && !strings.isEmpty()) {
              subParams.put(name, strings.get(0));
            }
          }
          value = subParams;
        }

        configParser.set(key, value);
      }

      try {
        configParser.save();
      } catch (IOException ex) {
        ex.printStackTrace();
      }

      String updatedMsg = String.valueOf(lang.getConfigValue(MessagesKeys.updated, ""));
      if (updatedMsg != null) updatedMsg = updatedMsg.replace("[filename]", configParser.name);
      RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, updatedMsg);
    } else if (factoryValue instanceof MultiConfigParser) {
      MultiConfigParser<?> parser = (MultiConfigParser<?>) this.factoryValue;
      List<String> remove = parameterValues.getOrDefault("remove", new ArrayList<>());
      for (String target : remove) {
        String configName = target;
        if (!configName.endsWith(".yml")) configName = configName + ".yml";
        ConfigParser<?> configParser = (ConfigParser<?>) parser.configParserFactory.get(configName);
        if (configParser == null) continue;
        parser.configParserFactory.map.remove(configName.toUpperCase());
        commandLookup.remove(target);
        YamlFile yamlFile = configParser.fileDatabase.cachedLookup.get().get(configName);
        if (yamlFile != null) yamlFile.getConfigurationFile().deleteOnExit();
      }

      List<String> add = parameterValues.getOrDefault("add", new ArrayList<>());
      for (String target : add) {
        parser.addParser(target);
        ConfigParser<?> configParser = parser.getParser(target);
        SubConfigCmd subUpdateCmd = new SubConfigCmd(this, configParser.name, configParser);
        subUpdateCmd.addParameters();
        addSubCommand(subUpdateCmd);
      }
    }

      CommandsAPICommand reload =
              RTP.baseCommand.getCommandLookup().getOrDefault("reload", new ReloadCmd(RTP.baseCommand));
      reload.onCommand(callerId, new HashMap<>(), null);
    });
    return true;
  }

  @Override
  public @NotNull List<String> onTabComplete(
      @NotNull UUID callerId,
      @NotNull Predicate<String> permissionCheckMethod,
      @NotNull String[] args) {
    addParameters();
    return super.onTabComplete(callerId, permissionCheckMethod, args);
  }

  public void addParameters() {
    parameterLookup.clear();
    commandLookup.clear();
    if (factoryValue == null) return;

    if (factoryValue instanceof ConfigParser) {
      ConfigParser<?> configParser = (ConfigParser<?>) this.factoryValue;
      EnumMap<?, ?> data = configParser.getData();
      for (Map.Entry<? extends Enum<?>, ?> e : data.entrySet()) {
        String name = e.getKey().name();
        if (name.equalsIgnoreCase("version")) continue;
        String s = name;
        Object nameObj = configParser.language_mapping.get(name);
        if (nameObj != null) s = nameObj.toString();
        Object o = e.getValue();

        if (name.contains("world")) {
          addParameter(s, new WorldParameter("rtp.update", "", (uuid, s1) -> true));
        } else if (name.contains("region")) {
          addParameter(s, new RegionParameter("rtp.update", "", (uuid, s1) -> true));
        } else if (o instanceof String) {
          addParameter(
              s,
              new CommandParameter("rtp.update", "", (uuid, s1) -> true) {
                @Override
                public Set<String> values() {
                  return new HashSet<>();
                }
              });
        } else if (o instanceof Boolean) {
          addParameter(s, new BooleanParameter("rtp.update", "", (uuid, s1) -> true));
        } else if (o instanceof Integer || o instanceof Long) {
          addParameter(s, new IntegerParameter("rtp.update", "", (uuid, s1) -> true));
        } else if (o instanceof Double || o instanceof Float) {
          addParameter(s, new FloatParameter("rtp.update", "", (uuid, s1) -> true));
        } else if (o instanceof Shape) {
          addParameter(s, new ShapeParameter("rtp.update", "", (uuid, s1) -> true));
        } else if (o instanceof VerticalAdjustor) {
          addParameter(s, new VertParameter("rtp.update", "", (uuid, s1) -> true));
        } else if (o instanceof Region) {
          addParameter(s, new RegionParameter("rtp.update", "", (uuid, s1) -> true));
        } else if (o instanceof MemorySection) {
          if (s.equalsIgnoreCase("shape")) {
            addParameter(s, new ShapeParameter("rtp.update", "", (uuid, s1) -> true));
          } else if (s.equalsIgnoreCase("vert")) {
            VertParameter vertParameter = new VertParameter("rtp.update", "", (uuid, s1) -> true);
            addParameter(s, vertParameter);
          }
        } else if (o instanceof List) {
          Supplier<Set<String>> values = HashSet::new;
          if (name.contains("block")) {
            values = () -> RTP.serverAccessor.materials();
          } else if (name.contains("biome")) {
            values =
                () -> {
                  Set<String> res = new HashSet<>();
                  List<RTPWorld<?>> rtpWorlds = RTP.serverAccessor.getRTPWorlds();
                  for (RTPWorld<?> rtpWorld : rtpWorlds) {
                    res.addAll(RTP.serverAccessor.getBiomes(rtpWorld));
                  }
                  return res;
                };
          }
          YamlFile yamlFile = configParser.fileDatabase.cachedLookup.get().get(configParser.name);
          if (yamlFile != null) addSubCommand(new ListCmd(name, this, values, yamlFile, s));
        }
      }
    } else if (factoryValue instanceof MultiConfigParser<?> parser) {
      for (Map.Entry<?, ?> e : parser.configParserFactory.map.entrySet()) {
        Object entryValue = e.getValue();
        if (entryValue instanceof FactoryValue)
          addSubCommand(
              new SubConfigCmd(this, e.getKey().toString(), (FactoryValue<?>) entryValue));
      }
      addParameter(
          "add",
          new CommandParameter("rtp.config", "add a file", (uuid, s) -> true) {
            @Override
            public Set<String> values() {
              return new HashSet<>();
            }
          });
      addParameter(
          "remove",
          new CommandParameter("rtp.update", "remove a file", (uuid, s) -> true) {
            @Override
            public Set<String> values() {
              return parser.listParsers();
            }
          });
    }
  }
}
