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
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;

import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;

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
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

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
          if (rtpWorld.name().endsWith("_nether") || rtpWorld.name().endsWith("_the_end")) {
            name = "LINEAR";
            parameterValues.putIfAbsent("direction", Collections.singletonList(String.valueOf(2)));
          }
          int maxY = 255;
          int minY = 0;

          Object o = regionParser.getConfigValue(RegionKeys.vert, null);
          if (o instanceof RtpYamlSection) {
            RtpYamlSection section = (RtpYamlSection) o;
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
          if (shape == null) {
            msgBadParameter(callerId, key, value.toString(), "CFG");
            continue;
          }

          EnumMap<?, Object> data = shape.getData();

          Map<String, Object> subParams = new HashMap<>();
          subParams.put("name", shape.name);
          for (Map.Entry<? extends Enum<?>, Object> entry : data.entrySet()) {
            subParams.put(entry.getKey().name(), entry.getValue());
          }

          RtpYamlConfig RtpYamlConfig = configParser.fileDatabase.cachedLookup.get().get(configParser.name);
          if (RtpYamlConfig != null) {
            Object o = RtpYamlConfig.get(key);
            if (o instanceof RtpYamlSection) {
              RtpYamlSection section = (RtpYamlSection) o;
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
          if (vert == null) {
            msgBadParameter(callerId, key, value.toString(), "CFG");
            continue;
          }

          EnumMap<? extends Enum<?>, Object> vertData = vert.getData();

          Map<String, Object> subParams = new HashMap<>();

          subParams.put("name", vert.name);
          for (Map.Entry<? extends Enum<?>, Object> entry : vertData.entrySet()) {
            subParams.put(entry.getKey().name(), entry.getValue());
          }

          RtpYamlConfig RtpYamlConfig = configParser.fileDatabase.cachedLookup.get().get(configParser.name);
          if (RtpYamlConfig != null) {
            Object o = RtpYamlConfig.get(key);
            if (o instanceof RtpYamlSection) {
              RtpYamlSection section = (RtpYamlSection) o;
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

        if (key.contains(".")) {
          RtpYamlConfig RtpYamlConfig = configParser.fileDatabase.cachedLookup.get().get(configParser.name);
          if (RtpYamlConfig != null) RtpYamlConfig.set(key, value);
        } else {
          configParser.set(key, value);
        }
      }

      try {
        configParser.save();
      } catch (IOException ex) {
        ex.printStackTrace();
      }

      String updatedMsg = String.valueOf(lang.getConfigValue(MessagesKeys.updated, ""));
      if (updatedMsg != null) updatedMsg = updatedMsg.replace("[filename]", configParser.name);
      RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, updatedMsg);

      CommandsAPICommand reload =
              RTP.baseCommand.getCommandLookup().getOrDefault("reload", new ReloadCmd(RTP.baseCommand));
      reload.onCommand(callerId, new HashMap<>(), null);
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
        RtpYamlConfig RtpYamlConfig = configParser.fileDatabase.cachedLookup.get().get(configName);
        if (RtpYamlConfig != null) RtpYamlConfig.getConfigurationFile().deleteOnExit();
      }

      List<String> add = parameterValues.getOrDefault("add", new ArrayList<>());
      for (String target : add) {
        parser.addParser(target);
        ConfigParser<?> configParser = parser.getParser(target);
        SubConfigCmd subUpdateCmd = new SubConfigCmd(this, configParser.name, configParser);
        subUpdateCmd.addParameters();
        addSubCommand(subUpdateCmd);
      }

      CommandsAPICommand reload =
              RTP.baseCommand.getCommandLookup().getOrDefault("reload", new ReloadCmd(RTP.baseCommand));
      reload.onCommand(callerId, new HashMap<>(), null);
    }
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

  /**
   * Resolve a one-line description for a YAML key by reading its block comment
   * via {@link RtpYamlSection#getComment(String)} and returning the first
   * non-blank, non-comment-marker line (leading {@code '#'} and surrounding
   * whitespace stripped). Returns {@code ""} when no usable comment exists.
   */
  private static String descriptionFromComment(RtpYamlSection section, String key) {
    if (section == null || key == null) return "";
    String comment;
    try {
      comment = section.getComment(key);
    } catch (RuntimeException ignored) {
      return "";
    }
    if (comment == null || comment.isEmpty()) return "";
    for (String line : comment.split("\n", -1)) {
      String trimmed = line.trim();
      while (trimmed.startsWith("#")) trimmed = trimmed.substring(1).trim();
      if (!trimmed.isEmpty()) return trimmed;
    }
    return "";
  }

  private void addSectionParameters(String prefix, RtpYamlSection section) {
    for (String key : section.getKeys(false)) {
      String fullKey = prefix + "." + key;
      Object value = section.get(key);
      String desc = descriptionFromComment(section, key);
      if (value instanceof RtpYamlSection) {
        addSectionParameters(fullKey, (RtpYamlSection) value);
      } else if (value instanceof Boolean) {
        addParameter(fullKey, new BooleanParameter("rtp.update", desc, (uuid, s1) -> true));
      } else if (value instanceof Integer || value instanceof Long) {
        addParameter(fullKey, new IntegerParameter("rtp.update", desc, (uuid, s1) -> true));
      } else if (value instanceof Double || value instanceof Float) {
        addParameter(fullKey, new FloatParameter("rtp.update", desc, (uuid, s1) -> true));
      } else {
        addParameter(
            fullKey,
            new CommandParameter("rtp.update", desc, (uuid, s1) -> true) {
              @Override
              public Set<String> values() {
                return new HashSet<>();
              }
            });
      }
    }
  }

  public void addParameters() {
    parameterLookup.clear();
    commandLookup.clear();
    if (factoryValue == null) return;

    if (factoryValue instanceof ConfigParser) {
      ConfigParser<?> configParser = (ConfigParser<?>) this.factoryValue;
      addSubCommand(new ViewSubConfigCmd(this, configParser));
      // Resolve the in-memory yaml backing this parser so that the per-key
      // block comment can drive the parameter description (first comment line).
      RtpYamlConfig parserYaml = null;
      try {
        parserYaml = configParser.fileDatabase.cachedLookup.get().get(configParser.name);
      } catch (RuntimeException ignored) {
        // Best-effort: a missing cached yaml just means descriptions stay empty.
      }
      EnumMap<?, ?> data = configParser.getData();
      for (Map.Entry<? extends Enum<?>, ?> e : data.entrySet()) {
        String name = e.getKey().name();
        if (name.equalsIgnoreCase("version")) continue;
        String s = name;
        Object nameObj = configParser.language_mapping.get(name);
        if (nameObj != null) s = nameObj.toString();
        Object o = e.getValue();
        // Try the in-file display name first (s), then fall back to the enum
        // name — the yaml is keyed by whichever the language_mapping points to.
        String desc = descriptionFromComment(parserYaml, s);
        if (desc.isEmpty() && !s.equals(name)) desc = descriptionFromComment(parserYaml, name);

        if (name.contains("world")) {
          addParameter(s, new WorldParameter("rtp.update", desc, (uuid, s1) -> true));
        } else if (name.contains("region")) {
          addParameter(s, new RegionParameter("rtp.update", desc, (uuid, s1) -> true));
        } else if (o instanceof String) {
          final String desc_ = desc;
          addParameter(
              s,
              new CommandParameter("rtp.update", desc_, (uuid, s1) -> true) {
                @Override
                public Set<String> values() {
                  return new HashSet<>();
                }
              });
        } else if (o instanceof Boolean) {
          addParameter(s, new BooleanParameter("rtp.update", desc, (uuid, s1) -> true));
        } else if (o instanceof Integer || o instanceof Long) {
          addParameter(s, new IntegerParameter("rtp.update", desc, (uuid, s1) -> true));
        } else if (o instanceof Double || o instanceof Float) {
          addParameter(s, new FloatParameter("rtp.update", desc, (uuid, s1) -> true));
        } else if (o instanceof Shape) {
          addParameter(s, new ShapeParameter("rtp.update", desc, (uuid, s1) -> true));
        } else if (o instanceof VerticalAdjustor) {
          addParameter(s, new VertParameter("rtp.update", desc, (uuid, s1) -> true));
        } else if (o instanceof Region) {
          addParameter(s, new RegionParameter("rtp.update", desc, (uuid, s1) -> true));
        } else if (o instanceof RtpYamlSection) {
          if (s.equalsIgnoreCase("shape")) {
            addParameter(s, new ShapeParameter("rtp.update", desc, (uuid, s1) -> true));
          } else if (s.equalsIgnoreCase("vert")) {
            VertParameter vertParameter = new VertParameter("rtp.update", desc, (uuid, s1) -> true);
            addParameter(s, vertParameter);
          } else {
            addSectionParameters(s, (RtpYamlSection) o);
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
          RtpYamlConfig RtpYamlConfig = configParser.fileDatabase.cachedLookup.get().get(configParser.name);
          if (RtpYamlConfig != null) addSubCommand(new ListCmd(name, this, values, RtpYamlConfig, s));
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
