package io.github.dailystruggle.rtp.common.factory;

import java.util.function.Function;
import io.github.dailystruggle.rtp.common.RTP;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;

/**
 * this exists solely because java is so stubborn about constructors and generics. rather than
 * calling a constructor, the factory value will be copied from a value in the factory
 *
 * @param <E> enum of available parameters
 */
@SuppressWarnings("unchecked")
public abstract class FactoryValue<E extends Enum<E>> implements Cloneable {

  private static final Map<Class<? extends Number>, Function<String, Number>> numberParsers =
      new ConcurrentHashMap<>();

  static {
    numberParsers.put(Double.class, Double::parseDouble);
    numberParsers.put(Float.class, Float::parseFloat);
    numberParsers.put(Long.class, Long::parseLong);
    numberParsers.put(Integer.class, Integer::parseInt);
    numberParsers.put(Short.class, Short::parseShort);
    numberParsers.put(Byte.class, Byte::parseByte);
  }

  /** The enum class used as the key type for this factory value's data map. */
  public final Class<E> myClass;
  protected final EnumMap<E, String[]> desc;

  protected final Map<String, E> enumLookup;

  /** The name of this factory value (typically the config file name). */
  public String name;

  /** Maps canonical key names to their locale-translated equivalents. */
  public Map<String, Object> language_mapping = new ConcurrentHashMap<>();
  /** Maps locale-translated key names back to their canonical equivalents. */
  public Map<String, String> reverse_language_mapping = new ConcurrentHashMap<>();
  // Volatile so {@link #setData(EnumMap)} can publish a fresh map by reference
  // assignment and concurrent readers always observe either the old map intact
  // or the new map intact — never a clear()+putAll() torn state. Reads that
  // need a coherent snapshot (getData, toString, toYAML, getNumber cache-back)
  // synchronize on the current map instance, which is correct as long as no
  // mutator ever modifies the map after publishing it via setData.
  protected volatile EnumMap<E, Object> data;
  private Set<String> keys = null;

  /**
   * Constructs a factory value for the given enum class and name.
   *
   * @param myClass the enum class used as the key type
   * @param name    the name of this factory value
   */
  protected FactoryValue(Class<E> myClass, String name) {
    this.myClass = myClass;
    enumLookup = new ConcurrentHashMap<>();
    E[] enumConstants = myClass.getEnumConstants();
    for (E constant : enumConstants) {
      enumLookup.put(constant.name().toLowerCase(), constant);
    }
    data = new EnumMap<>(myClass);
    desc = new EnumMap<>(myClass);
    this.name = name;
  }

  /**
   * generic getter
   *
   * @return copy of data, to prevent editing
   *
   * <p>The clone is taken under {@code synchronized (data)} so concurrent
   * {@code put}s from {@link #getNumber} (cache-back on first parse) cannot
   * interleave with the snapshot. The returned {@code EnumMap} is owned by
   * the caller and may be iterated freely without CME risk.</p>
   */
  @NotNull
  public EnumMap<E, Object> getData() {
    synchronized (data) {
      return data.clone();
    }
  }

  /**
   * Set data using an EnumMap
   *
   * @param data - data to apply.
   * @throws IllegalArgumentException - if the data is invalid
   */
  public void setData(final EnumMap<? extends Enum<?>, ?> data) throws IllegalArgumentException {
    EnumMap<E, Object> rebuilt = new EnumMap<>(myClass);
    data.forEach(
        (key, value) -> {
          if (key == null) throw new IllegalArgumentException("null key");
          if (value == null) throw new IllegalArgumentException("null value");
          if (!myClass.isAssignableFrom(key.getClass())) {
            throw new IllegalArgumentException(
                "invalid assignment"
                    + "\nexpected:"
                    + myClass.getSimpleName()
                    + "\nreceived:"
                    + key.getClass().getSimpleName());
          }
          rebuilt.put((E) key, value);
        });
    // Atomic swap: publish the fully-built map by reference assignment so a
    // concurrent reader either sees the old map (intact) or the new map
    // (intact), never a clear()+putAll() in-between state. The {@code data}
    // field is volatile (in-VM via the synchronized read sites that wrap
    // every cache-back / iterate snapshot); subclasses populating
    // {@code data.put(...)} in constructors run before publication, so the
    // swap pattern is safe across the existing surface.
    EnumMap<E, Object> oldData = this.data;
    synchronized (oldData) {
      this.data = rebuilt;
    }
  }

  /**
   * Set data using a map of string keys and objects
   *
   * @param data - data to apply. key is case-sensitive
   * @throws IllegalArgumentException - if the data is invalid
   */
  public void setData(final Map<String, Object> data) throws IllegalArgumentException {
    // Build a fresh map off-side, then atomically swap. Mirrors the
    // {@link #setData(EnumMap)} pattern: concurrent readers always see
    // either the old map intact or the new map intact, never an
    // intermediate state. Pre-existing entries in {@code this.data} are
    // preserved by seeding {@code rebuilt} with the current snapshot
    // (this overload is "merge", not "replace" — matches the prior
    // {@code this.data.put(...)} semantics).
    EnumMap<E, Object> rebuilt = new EnumMap<>(getData());
    data.forEach(
        (keyStr, value) -> {
          if (keyStr == null) return;
          if (value == null) return;

          try {
            E key = Enum.valueOf(myClass, keyStr);
            rebuilt.put(key, value);
          } catch (IllegalArgumentException ignored) {

          }
        });
    EnumMap<E, Object> oldData = this.data;
    synchronized (oldData) {
      this.data = rebuilt;
    }
  }

  /**
   * Get data for a specific key
   *
   * @param key the key to get data for
   * @return the data object
   */
  public Object getData(E key) {
    return data.get(key);
  }

  /**
   * Set the description for a specific key
   *
   * @param key the key
   * @param desc the description lines
   * @throws IllegalArgumentException if parameters are null
   */
  public void setDesc(@NotNull E key, @NotNull String[] desc) throws IllegalArgumentException {
    if (key == null) throw new IllegalArgumentException("null key");
    if (desc == null) throw new IllegalArgumentException("null desc");
    this.desc.put(key, desc.clone());
  }

  /**
   * Set a value for a specific key
   *
   * @param key the key
   * @param value the value
   * @throws IllegalArgumentException if parameters are null
   */
  public void set(@NotNull E key, @NotNull Object value) throws IllegalArgumentException {
    if (key == null) throw new IllegalArgumentException("null key");
    if (value == null) throw new IllegalArgumentException("null value");
    synchronized (this.data) {
      this.data.put(key, value);
    }
  }

  @Override
  public FactoryValue<E> clone() {
    try {
      FactoryValue<E> clone = (FactoryValue<E>) super.clone();
      // Snapshot under the same lock as getNumber's cache-back put, so the
      // clone observes a coherent EnumMap rather than a partially-mutated one.
      synchronized (data) {
        clone.data = data.clone();
      }
      for (Map.Entry<E, Object> entry : clone.data.entrySet()) {
        Object value = entry.getValue();
        if (value instanceof FactoryValue<?>) {
          entry.setValue(((FactoryValue<?>) value).clone());
        } else if (value instanceof Map) {
          entry.setValue(new HashMap<>((Map<?, ?>) value));
        }
      }
      return clone;
    } catch (CloneNotSupportedException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    }
    return null;
  }

  /**
   * Get a number value for a specific key
   *
   * @param key the key
   * @param def the default value
   * @return the number value
   * @throws NumberFormatException if the value is not a number
   */
  public Number getNumber(E key, Number def) throws NumberFormatException {
    // Snapshot {@code data} once: a concurrent {@link #setData(EnumMap)} may
    // publish a new map between the read and the cache-back put, in which
    // case we want both to target the same instance. {@code data} is volatile,
    // so this load is the read-side of the publication.
    EnumMap<E, Object> snapshot = data;
    Object resObj;
    synchronized (snapshot) {
      resObj = snapshot.getOrDefault(key, def);
    }
    // Hot path: already a Number — return without writing back. Pre-fix this
    // method called {@code data.put(key, res)} unconditionally on every read,
    // which (a) wasted a write per call after the first parse and (b) raced
    // with concurrent EnumMap iterators (toString / toYAML / setData.forEach)
    // under the probe-first pipeline, producing CME during pregen. The
    // cache-back is now restricted to genuine String/Character → Number
    // transitions, which fire at most once per (instance, key) for the
    // lifetime of the process. See FactoryValueGetNumberConcurrencyTest.
    if (resObj instanceof Number n) return n;

    Number res;
    if (resObj instanceof Boolean b) {
      // Tolerant boolean -> int coercion: a YAML author who writes a legacy
      // {@code true}/{@code false} for a knob that is now numeric (e.g.
      // {@code uniquePlacements}) gets 1/0 rather than a thrown NaN.
      res = b ? 1 : 0;
    } else if (resObj instanceof String s) {
      String coerced = s.replaceAll(",", ".");
      try {
        res = Double.parseDouble(coerced);
      } catch (NumberFormatException e) {
        RTP.log(
            Level.SEVERE,
            "expected floating point value for " + key.name() + ", received - " + coerced, e);
        res = def;
      }
    } else if (resObj instanceof Character c) {
      try {
        res = Integer.parseInt(c.toString());
      } catch (NumberFormatException e) {
        RTP.log(Level.SEVERE, "expected integer for " + key.name() + ", received - " + resObj, e);
        res = def;
      }
    } else {
      throw new IllegalArgumentException("[RTP] " + key.name() + ":NaN");
    }
    // Idempotent transition cache: any racing thread parses the same String
    // to the same Number, so last-writer-wins is harmless. Cache back into
    // the same map instance we read from (the snapshot) — if a concurrent
    // {@link #setData(EnumMap)} has since swapped {@code data}, the put
    // lands harmlessly in the now-orphaned old map; the next reader will
    // load the new map via the volatile {@code data} field and parse again.
    synchronized (snapshot) {
      snapshot.put(key, res);
    }
    return res;
  }

  /**
   * Get all keys available for this factory value
   *
   * @return collection of key names
   */
  public Collection<String> keys() {
    if (keys == null)
      keys = Arrays.stream(myClass.getEnumConstants()).map(Enum::name).collect(Collectors.toSet());
    return keys;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    // Iterate a snapshot so a concurrent {@link #getNumber} cache-back
    // cannot fire CME on the underlying EnumMap iterator.
    getData().forEach((e, o) -> builder.append("\n").append(e).append(": ").append(o.toString()));
    return builder.toString();
  }

  /**
   * Convert the data to a YAML string
   *
   * @return YAML string representation
   */
  public String toYAML() {
    StringBuilder res = new StringBuilder();
    // Iterate a snapshot so a concurrent {@link #getNumber} cache-back
    // cannot fire CME on the underlying EnumMap iterator.
    for (Map.Entry<E, Object> e : getData().entrySet()) {
      String[] desc = this.desc.get(e.getKey());
      if (desc != null) {
        for (String d : desc) {
          res.append(d).append("\n");
        }
      }

      res.append(e.getKey().name()).append(": ");

      Object value = e.getValue();
      if (value instanceof FactoryValue<?>) {
        res.append("\n");
        String s = ((FactoryValue<?>) value).toYAML();
        s = s.replaceAll("\n", "  \n");
        res.append(s);
      } else if (value instanceof Map) {
        ((Map<?, ?>) value)
            .forEach(
                (o, o2) ->
                    res.append("\n").append(o.toString()).append(": ").append(o2.toString()));
      } else if (value instanceof List) {
        ((List<?>) value).forEach(o -> res.append("\n").append(o.toString()));
      } else {
        res.append(value.toString());
      }
    }
    return res.toString();
  }

  /**
   * Load language mapping from a file
   *
   * @param subDir the subdirectory in the lang folder
   * @throws IOException if an I/O error occurs
   */
  public void loadLangFile(String subDir) throws IOException {
    String name = this.name;
    if (!name.endsWith(".yml")) name = name + ".yml";
    File langFile;
    if (RTP.serverAccessor == null) return;
    // ADR-076: the shared catalog rename map is a co-located dotfile sibling under the
    // catalog directory itself (e.g. definitions/shape/.CIRCLE.lang.yml), not
    // lang/<subDir>/<name>.lang.yml.
    String subPart = subDir.replace('/', File.separatorChar);
    String langDirStr =
        RTP.serverAccessor.getPluginDirectory().getAbsolutePath()
            + File.separator
            + subPart;
    File langDir = new File(langDirStr);
    if (!langDir.exists()) {
      boolean mkdir = langDir.mkdirs();
      if (!mkdir && !langDir.exists()) {
        throw new IllegalStateException(
            "Failed to create lang directory: " + langDir.getAbsolutePath()
                + " (check filesystem permissions for the plugin process)");
      }
    }

    String mapFileName = langDir + File.separator + "." + name.replace(".yml", ".lang.yml");
    langFile = new File(mapFileName);

    RtpYamlConfig langYaml = new RtpYamlConfig(langFile);
    if (!langFile.exists()) {
      try {
        java.io.InputStream in = RTP.class.getClassLoader().getResourceAsStream(subDir + "/" + langFile.getName());
        if (in != null) {
          java.io.FileOutputStream out = new java.io.FileOutputStream(langFile);
          byte[] buf = new byte[1024];
          int len;
          while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
          }
          out.close();
          in.close();
        }
      } catch (Exception ignored) {}

      if (!langFile.exists()) {
        for (String key : keys()) { // default data, to guard exceptions
          langYaml.set(key, key);
        }
        langYaml.save(langFile);
      }
    }

    langYaml.loadWithComments();
    Map<String, Object> map = langYaml.getMapValues(true);
    language_mapping.clear();
    language_mapping.putAll(map);
    reverse_language_mapping.clear();
    for (Map.Entry<String, Object> e : language_mapping.entrySet()) {
      reverse_language_mapping.put(e.getValue().toString(), e.getKey());
    }
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof FactoryValue)) return false;
    if (!(this.getClass().isAssignableFrom(other.getClass()))) return false;
    if (!(((FactoryValue<?>) other).myClass.equals(myClass))) return false;
    EnumMap<E, Object> data = (EnumMap<E, Object>) ((FactoryValue<?>) other).getData();
    for (Map.Entry<? extends Enum<?>, Object> e : this.data.entrySet()) {
      Object mine = e.getValue();
      Object theirs = data.get(e.getKey());
      if (mine.getClass().equals(theirs.getClass())) {
        if (!mine.equals(theirs)) return false;
      } else if (!mine.toString().equalsIgnoreCase(theirs.toString())) return false;
    }
    return true;
  }
}
