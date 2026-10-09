package io.github.dailystruggle.rtp.common.factory;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.search.FuzzySearchEngine;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

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
  /** Serializes writers only; readers never take it (copy-on-write, ADR-094). */
  private final Object dataLock = new Object();

  /** The name of this factory value (typically the config file name). */
  public String name;

  /** Maps canonical key names to their locale-translated equivalents. */
  public Map<String, Object> language_mapping = new ConcurrentHashMap<>();
  /** Maps locale-translated key names back to their canonical equivalents. */
  public Map<String, String> reverse_language_mapping = new ConcurrentHashMap<>();
  // Copy-on-write: a map published here is never mutated again. Writers clone,
  // modify and republish under dataLock; readers load the volatile reference and
  // read it lock-free. Selection reads knobs several times per chunk from many
  // async workers, so a read-side monitor serializes the whole pipeline.
  // Subclasses may write {@code data} directly only before the instance is shared
  // (constructors); afterwards they must go through set / setData / replaceData.
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
   * Returns a mutable copy of the current (immutable, published) data map.
   *
   * @return cloned copy of data
   */
  @NotNull
  public EnumMap<E, Object> getData() {
    return data.clone();
  }

  /**
   * Publish {@code rebuilt} as the new data map. The caller must not mutate it afterwards.
   *
   * @param rebuilt fully-populated replacement map
   */
  protected final void replaceData(EnumMap<E, Object> rebuilt) {
    synchronized (dataLock) {
      this.data = rebuilt;
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
    replaceData(rebuilt);
  }

  /**
   * Set data using a map of string keys and objects. Property keys are resolved
   * case-insensitively and typo-tolerantly with warning diagnostics.
   *
   * @param data - data to apply
   * @throws IllegalArgumentException - if the data is invalid
   */
  public void setData(final Map<String, Object> data) throws IllegalArgumentException {
    // Merge, not replace: existing entries survive. Seeded under the writer lock so
    // a concurrent set() is not lost between the snapshot and the publish.
    synchronized (dataLock) {
      EnumMap<E, Object> rebuilt = this.data.clone();

      // Build candidate map from myClass enum constants and the factory value "name" property
      Map<String, Object> candidateMap = new LinkedHashMap<>();
      boolean hasNameConstant = false;
      for (E constant : myClass.getEnumConstants()) {
        candidateMap.put(constant.name(), constant);
        if (constant.name().equalsIgnoreCase("name")) {
          hasNameConstant = true;
        }
      }
      if (!hasNameConstant) {
        candidateMap.put("name", "name");
      }

      data.forEach(
          (keyStr, value) -> {
            if (keyStr == null || value == null) return;

            FuzzySearchEngine.FuzzyLookupResult<Object> lookup =
                FuzzySearchEngine.resolveCandidate(keyStr, candidateMap);

            if (lookup.isExact()) {
              if (lookup.match() instanceof Enum<?> e && myClass.isInstance(e)) {
                rebuilt.put(myClass.cast(e), value);
              }
              if ("name".equalsIgnoreCase(lookup.matchedKey())) {
                if (this.name == null || !this.name.equalsIgnoreCase(String.valueOf(value))) {
                  this.name = String.valueOf(value);
                }
              }
            } else if (lookup.isPerceptible()) {
              RTP.log(
                  Level.WARNING,
                  "[RTP] Property '"
                      + keyStr
                      + "' in "
                      + myClass.getSimpleName()
                      + " was not recognized, but closely matches '"
                      + lookup.matchedKey()
                      + "'. Autocorrecting to '"
                      + lookup.matchedKey()
                      + "'.");
              if (lookup.match() instanceof Enum<?> e && myClass.isInstance(e)) {
                rebuilt.put(myClass.cast(e), value);
              }
              if ("name".equalsIgnoreCase(lookup.matchedKey())) {
                if (this.name == null || !this.name.equalsIgnoreCase(String.valueOf(value))) {
                  this.name = String.valueOf(value);
                }
              }
            } else {
              RTP.log(
                  Level.WARNING,
                  "[RTP] Unrecognized property '"
                      + keyStr
                      + "' for "
                      + myClass.getSimpleName()
                      + " (valid properties: "
                      + String.join(", ", lookup.availableCandidates())
                      + "). Ignoring.");
            }
          });
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
  public void setDesc(E key, String[] desc) throws IllegalArgumentException {
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
  public void set(E key, Object value) throws IllegalArgumentException {
    if (key == null) throw new IllegalArgumentException("null key");
    if (value == null) throw new IllegalArgumentException("null value");
    synchronized (dataLock) {
      EnumMap<E, Object> rebuilt = this.data.clone();
      rebuilt.put(key, value);
      this.data = rebuilt;
    }
  }

  @NotNull
  @Override
  public FactoryValue<E> clone() {
    try {
      FactoryValue<E> clone = (FactoryValue<E>) super.clone();
      // Published maps are immutable, so a plain clone is a coherent snapshot. The
      // copy is private to the new instance until clone() returns.
      clone.data = data.clone();
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
      throw new IllegalStateException("Failed to clone FactoryValue", e);
    }
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
    // Lock-free: one volatile load of an immutable map. Taking a monitor here
    // serialized every selection worker on a single object (stress-test stall).
    EnumMap<E, Object> snapshot = data;
    Object resObj = snapshot.getOrDefault(key, def);
    // Hot path: already a Number - return without writing back. Pre-fix this
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
      String coerced = s.replace(",", ".").trim();
      io.github.dailystruggle.rtp.common.selection.region.util.DistanceParser.ParsedDistance parsedDist =
          io.github.dailystruggle.rtp.common.selection.region.util.DistanceParser.parse(coerced, null);
      if (parsedDist != null && parsedDist.explicitUnit()) {
        res = parsedDist.toChunks();
      } else if (coerced.equalsIgnoreCase("auto")) {
        res = def;
      } else {
        try {
          res = Double.parseDouble(coerced);
        } catch (NumberFormatException e) {
          RTP.log(
              Level.SEVERE,
              "expected floating point value for " + key.name() + ", received - " + coerced, e);
          res = def;
        }
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
    // One-time String -> Number transition, published copy-on-write. Skipped if a
    // writer replaced the map since our read, so a stale parse never overwrites it.
    synchronized (dataLock) {
      if (this.data == snapshot) {
        EnumMap<E, Object> rebuilt = snapshot.clone();
        rebuilt.put(key, res);
        this.data = rebuilt;
      }
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
        s = s.replace("\n", "  \n");
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
    // catalog directory itself (e.g. definitions/regions/.shape/.CIRCLE.lang.yml), not
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
          try (in; java.io.FileOutputStream out = new java.io.FileOutputStream(langFile)) {
            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf)) > 0) {
              out.write(buf, 0, len);
            }
          }
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

  @Override
  public int hashCode() {
    int result = Objects.hash(myClass, name);
    for (Map.Entry<? extends Enum<?>, Object> e : this.data.entrySet()) {
      Object mine = e.getValue();
      if (mine != null) {
        result = 31 * result + mine.toString().toLowerCase(Locale.ROOT).hashCode();
      }
    }
    return result;
  }
}
