package io.github.dailystruggle.rtp.common.commands.config;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.SystemMessages;
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

  /**
   * Thin {@link BaseRTPCmdImpl} alias exposing the target {@link SubConfigCmd}'s parameter and
   * sub-command graph under an alternative name (e.g. bare "default" alongside "default.yml").
   */
  public static final class Alias extends BaseRTPCmdImpl {
    private final String aliasName;
    private final SubConfigCmd target;

    public Alias(@Nullable CommandsAPICommand parent, String aliasName, SubConfigCmd target) {
      super(parent);
      this.aliasName = aliasName.toLowerCase(java.util.Locale.ROOT);
      this.target = target;
    }

    @Override
    public String name() {
      return aliasName;
    }

    @Override
    public String permission() {
      return target.permission();
    }

    @Override
    public String description() {
      return target.description();
    }

    @Override
    public Map<String, CommandParameter> getParameterLookup() {
      return target.getParameterLookup();
    }

    @Override
    public Map<String, CommandsAPICommand> getCommandLookup() {
      return target.getCommandLookup();
    }

    @Override
    public boolean onCommand(
        UUID callerId,
        Map<String, List<String>> parameterValues,
        CommandsAPICommand nextCommand) {
      return target.onCommand(callerId, parameterValues, nextCommand);
    }
  }

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
    // If an intermediate node (nextCommand != null), do nothing and return true so
    // recursive walk runs child exactly once without duplicating execution.
    if (nextCommand != null) return true;

    String updateMsg = String.valueOf(RTP.configs.getConfigValue(SystemMessages.updating, ""));
    if (updateMsg != null) updateMsg = updateMsg.replace("[filename]", factoryValue.name);
    RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, updateMsg);

    RTP.scheduler.runTaskAsynchronously(() -> {
      if (factoryValue instanceof ConfigParser) {
        ConfigParser<?> configParser = (ConfigParser<?>) factoryValue;

      if (parameterValues.containsKey("world") && configParser.myClass.equals(RegionKeys.class)) {
        ConfigParser<RegionKeys> regionParser = (ConfigParser<RegionKeys>) configParser;
        RTPWorld rtpWorld = RTP.serverAccessor.getRTPWorld(parameterValues.get("world").get(0));
        if (rtpWorld != null) {
          io.github.dailystruggle.rtp.common.commands.menu.multiconfig.NetherEndConfigAmender
              .amend(parameterValues, regionParser, rtpWorld);
        }
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

        // A key that names one of the type-bearing factories we own
        // (shape/vert today; any future FactoryValue-backed factory
        // registered under RTP.factoryNames is picked up automatically) is
        // not a plain scalar - it is a type discriminator (`name`) plus a
        // block of sub-parameters. Expand the chosen type into its full
        // materialized block so the write path stores a complete nested
        // section rather than a bare scalar that would silently revert to
        // the factory default on the next reload.
        Factory<?> ownedFactory = ownedTypeFactoryForKey(key);
        if (ownedFactory != null) {
          FactoryValue<?> fv = ownedFactory.get(value.toString());
          if (fv == null) {
            msgBadParameter(callerId, key, value.toString(), "CFG");
            continue;
          }

          EnumMap<? extends Enum<?>, Object> data = fv.getData();

          Map<String, Object> subParams = new HashMap<>();
          subParams.put("name", fv.name);
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

          subParams.put("name", fv.name);
          for (Map.Entry<? extends Enum<?>, Object> entry : data.entrySet()) {
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
          if (RtpYamlConfig != null) {
            // Materialize parent block if scalar (@config token) to avoid clobbering sibling keys.
            materializeSectionParentIfScalar(configParser, RtpYamlConfig, key);
            // Restore canonical case of dotted leaf to overwrite existing key instead of creating orphan.
            String canonicalKey = canonicalizeDottedKey(configParser, RtpYamlConfig, key);
            RtpYamlConfig.set(canonicalKey, value);
          }
        } else if (ownedTypeFactoryForKey(key) != null && value instanceof Map) {
          // Top-level type override: write materialized block directly if slot is @config reference (ADR-073).
          RtpYamlConfig RtpYamlConfig = configParser.fileDatabase.cachedLookup.get().get(configParser.name);
          if (RtpYamlConfig != null
              && io.github.dailystruggle.rtp.common.configuration.ConfigDefaultResolver
                  .isReference(RtpYamlConfig.get(key))) {
            RtpYamlConfig.set(key, value);
          } else {
            configParser.set(key, value);
          }
        } else {
          configParser.set(key, value);
        }
      }

      try {
        configParser.save();
      } catch (IOException ex) {
        ex.printStackTrace();
      }

      String updatedMsg = String.valueOf(RTP.configs.getConfigValue(SystemMessages.updated, ""));
      if (updatedMsg != null) updatedMsg = updatedMsg.replace("[filename]", configParser.name);
      RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, updatedMsg);

      CommandsAPICommand reload =
              RTP.baseCommand.getCommandLookup().getOrDefault("reload", new ReloadCmd(RTP.baseCommand));
      reload.onCommand(callerId, new HashMap<>(), null);
    } else if (factoryValue instanceof MultiConfigParser) {
      MultiConfigParser<?> parser = (MultiConfigParser<?>) this.factoryValue;
      List<String> remove = parameterValues.getOrDefault("remove", new ArrayList<>());
      for (String target : remove) {
        // `default` is required by MultiConfigParser (re-extracted from jar
        // on construction); never remove it via the command.
        if (target != null && target.equalsIgnoreCase("default")) continue;
        String configName = target;
        if (!configName.endsWith(".yml")) configName = configName + ".yml";
        ConfigParser<?> configParser = (ConfigParser<?>) parser.configParserFactory.get(configName);
        if (configParser == null) continue;
        parser.configParserFactory.map.remove(configName.toUpperCase());
        // Drop both the parser-key form (`<entry>.yml`) and the bare
        // {@link Alias} form (`<entry>`) from the sub-command graph so the
        // entry is fully unreachable post-remove. `TreeCommand` exposes
        // no `removeSubCommand` API, so direct map removal is the only
        // option here - kept symmetric with the matching ADD-side calls
        // which go through `addSubCommand` (the supported registration
        // route). Keys are stored upper-cased per `TreeCommand` line 30.
        String bareRem = configName.replace(".yml", "").replace(".YML", "");
        commandLookup.remove(configName.toUpperCase(java.util.Locale.ROOT));
        commandLookup.remove(bareRem.toUpperCase(java.util.Locale.ROOT));
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
        // Parity with flat ConfigCmd.addCommands (lines 56-67) and the
        // addParameters() initial registration below: also register a
        // bare (suffix-stripped) {@link Alias} so `/rtp config <kind>
        // <entry>` resolves without the .yml suffix. Use `addSubCommand`
        // (the supported registration route) rather than touching
        // `commandLookup` directly.
        String bareAdd = configParser.name.replace(".yml", "").replace(".YML", "");
        if (!bareAdd.equalsIgnoreCase(configParser.name)) {
          addSubCommand(new Alias(this, bareAdd, subUpdateCmd));
        }
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
   * Return the type-bearing {@link Factory} matching {@code key} case-insensitively, or {@code null}.
   * Filters out non-type config-parser factories ({@code singleConfig}/{@code multiConfig}).
   */
  @Nullable
  static Factory<?> ownedTypeFactoryForKey(String key) {
    if (key == null) return null;
    for (RTP.factoryNames fn : RTP.factoryNames.values()) {
      if (fn == RTP.factoryNames.singleConfig || fn == RTP.factoryNames.multiConfig) continue;
      if (!fn.name().equalsIgnoreCase(key)) continue;
      Factory<?> factory = RTP.factoryMap.get(fn);
      if (factory != null) return factory;
    }
    return null;
  }

  /**
   * Materializes the parent block of a dotted leaf key when the on-disk parent is a scalar
   * (e.g. {@code @config} inheritance token), preventing single-leaf overwrites from dropping siblings.
   */
  static void materializeSectionParentIfScalar(
      ConfigParser<?> configParser, RtpYamlConfig yaml, String dottedKey) {
    int dot = dottedKey.indexOf('.');
    if (dot <= 0) return;
    String parentKey = dottedKey.substring(0, dot);

    Object existing = yaml.get(parentKey);
    if (existing instanceof RtpYamlSection) return; // already a nested block

    // Find the loaded, resolved value for the parent key by enum name.
    Object resolved = null;
    for (Map.Entry<? extends Enum<?>, Object> entry : configParser.getData().entrySet()) {
      if (entry.getKey().name().equalsIgnoreCase(parentKey)) {
        resolved = entry.getValue();
        break;
      }
    }
    if (!(resolved instanceof FactoryValue<?>)) return;

    FactoryValue<?> fv = (FactoryValue<?>) resolved;
    Map<String, Object> block = new LinkedHashMap<>();
    block.put("name", fv.name);
    for (Map.Entry<? extends Enum<?>, Object> d : fv.getData().entrySet()) {
      block.put(d.getKey().name(), d.getValue());
    }
    yaml.set(parentKey, block);
  }

  /**
   * Restores canonical case of a dotted leaf key from parent section or {@link FactoryValue} data,
   * preventing lower-cased parameter names from inserting orphan leaves.
   */
  static String canonicalizeDottedKey(
      ConfigParser<?> configParser, RtpYamlConfig yaml, String dottedKey) {
    int dot = dottedKey.indexOf('.');
    if (dot <= 0 || dot >= dottedKey.length() - 1) return dottedKey;
    String parentKey = dottedKey.substring(0, dot);
    String leaf = dottedKey.substring(dot + 1);

    // Prefer the on-disk parent section's existing key casing.
    Object parent = yaml.get(parentKey);
    if (parent instanceof RtpYamlSection) {
      for (String existing : ((RtpYamlSection) parent).getKeys(false)) {
        if (existing != null && existing.equalsIgnoreCase(leaf)) {
          return parentKey + "." + existing;
        }
      }
    }

    // Fall back to the loaded FactoryValue's data (enum-cased) key names.
    Object resolved = null;
    for (Map.Entry<? extends Enum<?>, Object> entry : configParser.getData().entrySet()) {
      if (entry.getKey().name().equalsIgnoreCase(parentKey)) {
        resolved = entry.getValue();
        break;
      }
    }
    if (resolved instanceof FactoryValue<?>) {
      if ("name".equalsIgnoreCase(leaf)) return parentKey + ".name";
      for (Map.Entry<? extends Enum<?>, Object> d : ((FactoryValue<?>) resolved).getData().entrySet()) {
        String name = d.getKey().name();
        if (name.equalsIgnoreCase(leaf)) return parentKey + "." + name;
      }
    }
    return dottedKey;
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

  /**
   * Registers typed dotted leaf parameters (e.g. {@code shape.radius}, {@code shape.name}) from a
   * {@link FactoryValue}'s data map for menu/CLI sub-knob staging (ADR-050).
   */
  private void addFactoryValueDottedParameters(String prefix, FactoryValue<?> fv) {
    // Discriminator (`shape.name`, `vert.name`) so the factory type can
    // be switched from the flat view.
    addParameter(
        prefix + ".name",
        new CommandParameter("rtp.update", "", (uuid, s1) -> true) {
          @Override
          public Set<String> values() {
            return new HashSet<>();
          }
        });
    EnumMap<?, Object> data = fv.getData();
    if (data == null) return;
    for (Map.Entry<?, Object> e : data.entrySet()) {
      Object keyObj = e.getKey();
      if (keyObj == null) continue;
      String leaf = String.valueOf(keyObj);
      String fullKey = prefix + "." + leaf;
      Object value = e.getValue();
      if (value instanceof Boolean) {
        addParameter(fullKey, new BooleanParameter("rtp.update", "", (uuid, s1) -> true));
      } else if (value instanceof Integer || value instanceof Long) {
        addParameter(fullKey, new IntegerParameter("rtp.update", "", (uuid, s1) -> true));
      } else if (value instanceof Double || value instanceof Float) {
        addParameter(fullKey, new FloatParameter("rtp.update", "", (uuid, s1) -> true));
      } else {
        addParameter(
            fullKey,
            new CommandParameter("rtp.update", "", (uuid, s1) -> true) {
              @Override
              public Set<String> values() {
                return new HashSet<>();
              }
            });
      }
    }
  }

  /**
   * Registers dotted sub-knob leaves for a {@code shape}/{@code vert} key whose stored value is an
   * inheritance reference (e.g. {@code @config}), registering the union across all factory variants.
   */
  private void addFactoryReferenceDottedParameters(String prefix, RTP.factoryNames factoryName) {
    addParameter(
        prefix + ".name",
        new CommandParameter("rtp.update", "", (uuid, s1) -> true) {
          @Override
          public Set<String> values() {
            return new HashSet<>();
          }
        });
    Factory<?> factory = RTP.factoryMap.get(factoryName);
    if (factory == null) return;
    for (Object v : factory.map.values()) {
      Map<String, CommandParameter> params = null;
      if (v instanceof Shape) {
        params = ((Shape<?>) v).getParameters();
      } else if (v instanceof VerticalAdjustor) {
        params = ((VerticalAdjustor<?>) v).getParameters();
      }
      if (params == null) continue;
      for (Map.Entry<String, CommandParameter> pe : params.entrySet()) {
        if (pe.getKey() == null || pe.getValue() == null) continue;
        addParameter(prefix + "." + pe.getKey(), pe.getValue());
      }
    }
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
      addSubCommand(new ViewRawSubConfigCmd(this, configParser));
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
        // name - the yaml is keyed by whichever the language_mapping points to.
        String desc = descriptionFromComment(parserYaml, s);
        if (desc.isEmpty() && !s.equals(name)) desc = descriptionFromComment(parserYaml, name);

        if (name.contains("world")) {
          addParameter(s, new WorldParameter("rtp.update", desc, (uuid, s1) -> true));
        } else if (name.contains("region")) {
          addParameter(s, new RegionParameter("rtp.update", desc, (uuid, s1) -> true));
        } else if (o instanceof String && name.equalsIgnoreCase("shape")) {
          // Referenced shape (@config or bare name): register ShapeParameter and factory dotted leaves.
          addParameter(s, new ShapeParameter("rtp.update", desc, (uuid, s1) -> true));
          addFactoryReferenceDottedParameters(s, RTP.factoryNames.shape);
        } else if (o instanceof String && name.equalsIgnoreCase("vert")) {
          // Referenced vert (@config or bare name): register VertParameter and factory dotted leaves.
          addParameter(s, new VertParameter("rtp.update", desc, (uuid, s1) -> true));
          addFactoryReferenceDottedParameters(s, RTP.factoryNames.vert);
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
          // ADR-050: register dotted scalar leaves (shape.radius, shape.centerX, ...) for menu/CLI staging.
          addFactoryValueDottedParameters(s, (FactoryValue<?>) o);
        } else if (o instanceof VerticalAdjustor) {
          addParameter(s, new VertParameter("rtp.update", desc, (uuid, s1) -> true));
          // Symmetrical dotted-leaf registration for vert.
          addFactoryValueDottedParameters(s, (FactoryValue<?>) o);
        } else if (o instanceof Region) {
          addParameter(s, new RegionParameter("rtp.update", desc, (uuid, s1) -> true));
        } else if (o instanceof RtpYamlSection) {
          if (s.equalsIgnoreCase("shape")) {
            addParameter(s, new ShapeParameter("rtp.update", desc, (uuid, s1) -> true));
            // Register dotted sub-parameters for raw RtpYamlSection before FactoryValue merge pass.
            addSectionParameters(s, (RtpYamlSection) o);
          } else if (s.equalsIgnoreCase("vert")) {
            VertParameter vertParameter = new VertParameter("rtp.update", desc, (uuid, s1) -> true);
            addParameter(s, vertParameter);
            addSectionParameters(s, (RtpYamlSection) o);
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
        if (entryValue instanceof FactoryValue) {
          String entryName = e.getKey().toString();
          SubConfigCmd childCmd =
              new SubConfigCmd(this, entryName, (FactoryValue<?>) entryValue);
          addSubCommand(childCmd);
          // Register bare (suffix-stripped) Alias so `/rtp config <kind> <entry>` resolves without .yml.
          String bare = entryName.replace(".yml", "").replace(".YML", "");
          if (!bare.equalsIgnoreCase(entryName)) {
            addSubCommand(new Alias(this, bare, childCmd));
          }
        }
      }
      addParameter(
          "add",
          new CommandParameter("rtp.config", "add a file", (uuid, s) -> true) {
            @Override
            public Set<String> values() {
              return new HashSet<>();
            }
          });
      // `remove`: never offer `default` as a removable entry - the
      // default.yml is required (MultiConfigParser re-extracts it from the
      // jar on construction). When the filtered set is empty (only default
      // exists), omit the `remove` parameter entirely so the menu doesn't
      // render a picker with zero valid answers.
      Set<String> removable = new HashSet<>();
      for (String n : parser.listParsers()) {
        if (n == null) continue;
        if (n.equalsIgnoreCase("default")) continue;
        removable.add(n);
      }
      if (!removable.isEmpty()) {
        addParameter(
            "remove",
            new CommandParameter("rtp.update", "remove a file", (uuid, s) -> true) {
              @Override
              public Set<String> values() {
                Set<String> res = new HashSet<>();
                for (String n : parser.listParsers()) {
                  if (n == null) continue;
                  if (n.equalsIgnoreCase("default")) continue;
                  res.add(n);
                }
                return res;
              }
            });
      }
    }
  }
}
