package io.github.dailystruggle.rtp.common.factory;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import java.util.Enumeration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * On request, find a stored object with the correct name, clone it, and return it
 *
 * @param <T> type of values this factory will hold
 */
public class Factory<T extends FactoryValue<?>> {

  /** Constructs an empty factory. */
  public Factory() {}

  /** The backing map of upper-cased {@code .YML}-suffixed names to values. */
  public final ConcurrentHashMap<String, T> map = new ConcurrentHashMap<>();

  /**
   * Adds a value under the given name (upper-cased, {@code .YML}-suffixed).
   *
   * @param name  the name to register under
   * @param value the value to store
   */
  public void add(String name, T value) {
    name = name.toUpperCase();
    if (!name.endsWith(".YML")) name = name + ".YML";
    map.put(name, value);
  }

  /**
   * Removes the value registered under the given name.
   *
   * @param name the name to remove
   */
  public void remove(String name) {
    name = name.toUpperCase();
    if (!name.endsWith(".YML")) name = name + ".YML";
    map.remove(name);
  }

  /**
   * Returns an enumeration of all registered names.
   *
   * @return enumeration of registered names
   */
  public Enumeration<String> list() {
    return map.keys();
  }

  /**
   * Returns {@code true} if a value is registered under the given name.
   *
   * @param name the name to check
   * @return {@code true} if the name is registered
   */
  public boolean contains(String name) {
    name = name.toUpperCase();
    if (!name.endsWith(".YML")) name = name + ".YML";
    return map.containsKey(name);
  }

  /**
   * @param name name of item
   * @return mutable copy of an item
   */
  @Nullable
  public FactoryValue<?> construct(String name) {
    String comparableName = name.toUpperCase();
    if (!comparableName.endsWith(".YML")) comparableName = comparableName + ".YML";
    // guard constructor
    T value = map.get(comparableName);
    if (value == null) {
      if (map.containsKey("DEFAULT.YML") || !map.isEmpty()) {
        value = map.getOrDefault("DEFAULT.YML", map.values().stream().findAny().get());
        T clone = (T) value.clone();
        clone.name = (name.endsWith(".yml")) ? name : name + ".yml";

        if (clone instanceof ConfigParser) {
          ConfigParser<?> configParser = (ConfigParser<?>) clone;
          // Reuse the template's rename map (e.g. a MultiConfigParser's shared
          // folder-similar `.worlds.lang.yml`) rather than passing null, which
          // would re-auto-resolve to a stray per-file map inside the folder.
          configParser.check(configParser.version, configParser.pluginDirectory, configParser.langFile);
        }
        value = clone;
      } else return null;
    }
    return value.clone();
  }

  /**
   * Constructs an item cloned from template {@code fromName}, falling back to default.
   *
   * @param name     item name to construct
   * @param fromName originating template name to clone from
   * @return mutable cloned copy, or {@code null} if factory is empty
   */
  @Nullable
  public FactoryValue<?> construct(String name, String fromName) {
    if (fromName == null) return construct(name);
    String fromKey = fromName.toUpperCase();
    if (!fromKey.endsWith(".YML")) fromKey = fromKey + ".YML";
    T template = map.get(fromKey);
    if (template == null) {
      // Unknown originating file: fall back to the default-seeded behaviour
      // rather than failing, so callers don't have to pre-check existence.
      return construct(name);
    }
    T clone = (T) template.clone();
    clone.name = (name.endsWith(".yml")) ? name : name + ".yml";
    if (clone instanceof ConfigParser) {
      ConfigParser<?> configParser = (ConfigParser<?>) clone;
      // Reuse the template's rename map (see construct(String)) instead of null.
      configParser.check(configParser.version, configParser.pluginDirectory, configParser.langFile);
    }
    return clone.clone();
  }

  /**
   * Returns a clone of the value registered under the given name, or {@code null} if absent.
   *
   * @param name the name to look up
   * @return a clone of the registered value, or {@code null}
   */
  @Nullable
  public FactoryValue<?> get(String name) {
    name = name.toUpperCase();
    if (!name.endsWith(".YML")) name = name + ".YML";
    T t = map.get(name);
    if (t == null) return null;
    return t.clone();
  }

  /**
   * Returns a clone of the value registered under the given name, falling back to the
   * {@code DEFAULT.YML} entry or any available entry when the name is absent.
   *
   * @param name the name to look up
   * @return a clone of the best-matching value; never {@code null} when the map is non-empty
   */
  @NotNull
  public FactoryValue<?> getOrDefault(String name) {
    name = name.toUpperCase();
    if (!name.endsWith(".YML")) name = name + ".YML";
    // guard constructor
    T value = map.get(name);
    if (value == null) {
      if (map.containsKey("DEFAULT.YML")) {
        value = (T) construct(name);
        map.put(name, value);
      } else {
        Optional<T> any = map.values().stream().findAny();
        if (any.isPresent()) return any.get().clone();
        else {
          RTP.log(Level.WARNING, "no values in map", new IllegalStateException());
        }
      }
    }
    return value.clone();
  }
}
