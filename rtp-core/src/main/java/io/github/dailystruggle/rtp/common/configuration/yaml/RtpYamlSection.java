package io.github.dailystruggle.rtp.common.configuration.yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-side / mutate-side handle on a YAML mapping (the root document or
 * a nested section). Mirrors the small surface of {@code simpleyaml}'s
 * {@code ConfigurationSection} that the RTP codebase actually uses, per
 * the ADR-025 §Context enumeration.
 *
 * <p>The handle holds a back-reference to the underlying {@link RtpYamlMapping}
 * — mutations performed through {@link #set(String, Object)} go directly
 * to the AST, so subsequent saves see them.</p>
 *
 * <p>Dotted-path keys (e.g. {@code "database.type"}) are supported and
 * match the deep-keys semantics established by the prior simpleyaml-backed
 * implementation (see {@code RtpYamlDeepKeysParityTest}).</p>
 */
public class RtpYamlSection {

    final RtpYamlMapping node;
    /** Own key name; empty string for the document root. */
    final String name;
    /** Dotted path from root; empty string for the document root. */
    final String path;

    RtpYamlSection(RtpYamlMapping node) {
        this(node, "", "");
    }

    RtpYamlSection(RtpYamlMapping node, String name, String path) {
        this.node = node;
        this.name = name == null ? "" : name;
        this.path = path == null ? "" : path;
    }

    /** The underlying AST mapping (package-private accessor for testing). */
    RtpYamlMapping mapping() { return node; }

    /** Own key name (mirrors {@code simpleyaml}'s {@code ConfigurationSection.getName()}). */
    public String getName() { return name; }

    /** Dotted path from the document root (mirrors {@code ConfigurationSection.getCurrentPath()}). */
    public String getCurrentPath() { return path; }

    /**
     * Returns the value at {@code key}, where {@code key} may be a
     * dotted path. Scalars are returned with primitive coercion
     * ({@link RtpYamlScalar#value()}); sections are returned as a
     * {@link RtpYamlSection}; sequences are returned as a {@code List}
     * of recursively coerced values.
     */
    public Object get(String key) {
        RtpYamlNode n = resolve(key, false);
        return coerce(n);
    }

    public Object get(String key, Object fallback) {
        Object v = get(key);
        return v == null ? fallback : v;
    }

    public RtpYamlSection getConfigurationSection(String key) {
        RtpYamlNode n = resolve(key, false);
        if (n instanceof RtpYamlMapping) {
            String[] parts = key.split("\\.");
            String leaf = parts[parts.length - 1];
            String childPath = path.isEmpty() ? key : path + "." + key;
            return new RtpYamlSection((RtpYamlMapping) n, leaf, childPath);
        }
        return null;
    }

    public boolean contains(String key) {
        return resolve(key, false) != null;
    }

    /** Mirrors {@code simpleyaml}'s {@code isSet} — true iff key is present. */
    public boolean isSet(String key) { return contains(key); }

    public boolean isConfigurationSection(String key) {
        return resolve(key, false) instanceof RtpYamlMapping;
    }

    public boolean isString(String key) {
        Object v = get(key);
        return v instanceof String;
    }

    public boolean isInt(String key) {
        Object v = get(key);
        return v instanceof Integer || v instanceof Long;
    }

    public boolean isBoolean(String key) {
        Object v = get(key);
        return v instanceof Boolean;
    }

    public boolean isList(String key) {
        return get(key) instanceof List;
    }

    /* ------------------------ typed getters ------------------------ */

    public String getString(String key) {
        Object v = get(key);
        return v == null ? null : String.valueOf(v);
    }

    public String getString(String key, String def) {
        String s = getString(key);
        return s == null ? def : s;
    }

    public int getInt(String key) { return getInt(key, 0); }
    public int getInt(String key, int def) {
        Object v = get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) try { return Integer.parseInt((String) v); } catch (NumberFormatException ignored) {}
        return def;
    }

    public long getLong(String key) { return getLong(key, 0L); }
    public long getLong(String key, long def) {
        Object v = get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) try { return Long.parseLong((String) v); } catch (NumberFormatException ignored) {}
        return def;
    }

    public double getDouble(String key) { return getDouble(key, 0.0); }
    public double getDouble(String key, double def) {
        Object v = get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) try { return Double.parseDouble((String) v); } catch (NumberFormatException ignored) {}
        return def;
    }

    public boolean getBoolean(String key) { return getBoolean(key, false); }
    public boolean getBoolean(String key, boolean def) {
        Object v = get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("yes") || s.equalsIgnoreCase("on")) return true;
            if (s.equalsIgnoreCase("false") || s.equalsIgnoreCase("no") || s.equalsIgnoreCase("off")) return false;
        }
        return def;
    }

    @SuppressWarnings("unchecked")
    public List<?> getList(String key) {
        Object v = get(key);
        return v instanceof List ? (List<Object>) v : null;
    }

    public List<String> getStringList(String key) {
        Object v = get(key);
        if (!(v instanceof List)) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) v) {
            if (o != null) out.add(String.valueOf(o));
        }
        return out;
    }

    /**
     * Mirrors {@code simpleyaml}'s {@code ConfigurationSection.getMapValues(deep)}.
     * Alias for {@link #getValues(boolean)} provided for parity with the prior
     * yaml backend so call sites can be migrated with a pure type swap.
     */
    public Map<String, Object> getMapValues(boolean deep) {
        return getValues(deep);
    }

    /**
     * Mirrors {@code simpleyaml}'s {@code ConfigurationSection.createSection(String)}:
     * creates (or replaces) an empty section at {@code key} and returns a
     * handle to it.
     */
    public RtpYamlSection createSection(String key) {
        return createSection(key, java.util.Collections.emptyMap());
    }

    /**
     * Mirrors {@code simpleyaml}'s {@code ConfigurationSection.createSection(String, Map)}:
     * creates (or replaces) a section at {@code key} populated with the entries
     * of {@code values} and returns a handle to it.
     */
    public RtpYamlSection createSection(String key, Map<?, ?> values) {
        RtpYamlMapping fresh = new RtpYamlMapping();
        for (var e : values.entrySet()) {
            fresh.put(String.valueOf(e.getKey()), toNode(e.getValue()));
        }
        set(key, fresh);
        return new RtpYamlSection(fresh);
    }

    /**
     * Mirrors {@code simpleyaml}'s {@code ConfigurationSection.remove(String)}.
     * Removes the key (which may be a dotted path) and its value/comment. No-op
     * if the key is absent.
     */
    public void remove(String key) {
        if (key == null || key.isEmpty()) return;
        String[] parts = key.split("\\.");
        RtpYamlMapping cur = node;
        for (int i = 0; i < parts.length - 1; i++) {
            RtpYamlNode child = cur.get(parts[i]);
            if (!(child instanceof RtpYamlMapping)) return;
            cur = (RtpYamlMapping) child;
        }
        cur.entries().remove(parts[parts.length - 1]);
    }

    /** Mirrors {@code simpleyaml}'s {@code ConfigurationSection.getValues(deep)}. */
    public Map<String, Object> getValues(boolean deep) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        // Snapshot the entries before iterating: async reload paths
        // (Configs.reloadRegions -> ConfigParser.setSection -> getMapValues
        // -> getValues) can race with concurrent setters mutating the same
        // backing LinkedHashMap, producing ConcurrentModificationException.
        // A shallow copy of the entry set is cheap and decouples iteration
        // from concurrent structural mutations; entry values (RtpYamlNode)
        // are still shared, which is fine because we only read them.
        var snapshot = new ArrayList<>(node.entries().entrySet());
        for (var e : snapshot) {
            String key = e.getKey();
            RtpYamlNode child = e.getValue();
            if (deep && child instanceof RtpYamlMapping) {
                Map<String, Object> sub = new RtpYamlSection((RtpYamlMapping) child).getValues(true);
                for (var sub_e : sub.entrySet()) {
                    out.put(key + "." + sub_e.getKey(), sub_e.getValue());
                }
            } else {
                out.put(key, coerce(child));
            }
        }
        return out;
    }

    /* --------------------- comment passthroughs --------------------- */

    /**
     * Returns the first line of the block comment attached to {@code key},
     * or {@code null} if no comment is present. Mirrors {@code simpleyaml}'s
     * {@code getComment(String)}; multi-line comments are joined with {@code \n}.
     */
    public String getComment(String key) {
        RtpYamlNode n = resolve(key, false);
        if (n == null) return null;
        List<String> lines = n.blockComments();
        if (lines == null || lines.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    /**
     * Sets the block comment above {@code key}. {@code null} or empty clears
     * the comment. Multi-line input is split on {@code \n}.
     */
    public void setComment(String key, String comment) {
        RtpYamlNode n = resolve(key, false);
        if (n == null) return;
        if (comment == null || comment.isEmpty()) {
            n.setBlockComments(new ArrayList<>());
            return;
        }
        List<String> lines = new ArrayList<>();
        for (String line : comment.split("\n", -1)) lines.add(line);
        n.setBlockComments(lines);
    }

    /**
     * Top-level (non-deep) or deep key set. Mirrors
     * {@code ConfigurationSection.getKeys(deep)}: when {@code deep} is
     * true the keys are dotted paths to every leaf in the subtree
     * (sections are included as well, exactly matching the prior
     * simpleyaml behaviour the test suite locked in).
     */
    public Set<String> getKeys(boolean deep) {
        if (!deep) return new LinkedHashSet<>(node.keys());
        LinkedHashSet<String> out = new LinkedHashSet<>();
        collectKeys(node, "", out);
        return out;
    }

    private static void collectKeys(RtpYamlMapping mapping, String prefix, Set<String> out) {
        for (var e : mapping.entries().entrySet()) {
            String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            out.add(path);
            if (e.getValue() instanceof RtpYamlMapping) {
                collectKeys((RtpYamlMapping) e.getValue(), path, out);
            }
        }
    }

    /**
     * Set the value at {@code key} (which may be a dotted path).
     * Intermediate sections are created on demand. Existing block
     * comments on the target node are preserved across re-typed writes
     * of the same key.
     */
    public void set(String key, Object value) {
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("key must not be empty");
        String[] parts = key.split("\\.");
        RtpYamlMapping cur = node;
        for (int i = 0; i < parts.length - 1; i++) {
            RtpYamlNode child = cur.get(parts[i]);
            if (child instanceof RtpYamlMapping) {
                cur = (RtpYamlMapping) child;
            } else {
                RtpYamlMapping fresh = new RtpYamlMapping();
                cur.put(parts[i], fresh);
                cur = fresh;
            }
        }
        String leaf = parts[parts.length - 1];
        RtpYamlNode existing = cur.get(leaf);
        List<String> preservedComments = existing != null ? new ArrayList<>(existing.blockComments()) : null;
        RtpYamlNode toStore = toNode(value);
        if (preservedComments != null) toStore.setBlockComments(preservedComments);
        cur.put(leaf, toStore);
    }

    /* ------------------------------------------------------------------ */

    private RtpYamlNode resolve(String key, boolean createParents) {
        if (key == null || key.isEmpty()) return node;
        String[] parts = key.split("\\.");
        RtpYamlNode cur = node;
        for (int i = 0; i < parts.length; i++) {
            if (!(cur instanceof RtpYamlMapping)) return null;
            cur = ((RtpYamlMapping) cur).get(parts[i]);
            if (cur == null) return null;
        }
        return cur;
    }

    private static Object coerce(RtpYamlNode n) {
        if (n == null) return null;
        if (n instanceof RtpYamlScalar) return ((RtpYamlScalar) n).value();
        if (n instanceof RtpYamlMapping) return new RtpYamlSection((RtpYamlMapping) n);
        if (n instanceof RtpYamlSequence) {
            List<Object> out = new ArrayList<>();
            for (RtpYamlNode item : ((RtpYamlSequence) n).items()) out.add(coerce(item));
            return out;
        }
        return null;
    }

    private static RtpYamlNode toNode(Object value) {
        if (value == null) return new RtpYamlScalar("", RtpYamlScalar.Style.PLAIN);
        if (value instanceof RtpYamlNode) return (RtpYamlNode) value;
        if (value instanceof RtpYamlSection) return ((RtpYamlSection) value).node;
        if (value instanceof List<?>) {
            RtpYamlSequence seq = new RtpYamlSequence();
            for (Object o : (List<?>) value) seq.add(toNode(o));
            return seq;
        }
        if (value instanceof java.util.Map<?, ?>) {
            RtpYamlMapping m = new RtpYamlMapping();
            for (var e : ((java.util.Map<?, ?>) value).entrySet()) {
                m.put(String.valueOf(e.getKey()), toNode(e.getValue()));
            }
            return m;
        }
        if (value instanceof Boolean || value instanceof Number) {
            return new RtpYamlScalar(String.valueOf(value), RtpYamlScalar.Style.PLAIN);
        }
        // Strings (and everything else) get double-quoted to be safe.
        return new RtpYamlScalar(String.valueOf(value), RtpYamlScalar.Style.DOUBLE);
    }
}
