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
          configParser.check(configParser.version, configParser.pluginDirectory, null);
        }
        value = clone;
      } else return null;
    }
    return value.clone();
  }

  /**
   * Like {@link #construct(String)}, but clones the entry registered under
   * {@code fromName} as the template instead of always falling back to
   * {@code DEFAULT.YML}. Used to create a new file (e.g. a new region) seeded
   * from an arbitrary originating file rather than the bundled default.
   *
   * <p>When {@code fromName} resolves to an already-registered name the new
   * value reuses that entry as a template; when it does not exist the call
   * degrades to {@link #construct(String)} (default-seeded), so callers never
   * need to special-case a missing template.
   *
   * @param name     the name of the item to construct
   * @param fromName the originating template name to clone from
   * @return mutable copy of the item seeded from {@code fromName}, or
   *         {@code null} when the map is empty
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
      configParser.check(configParser.version, configParser.pluginDirectory, null);
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
