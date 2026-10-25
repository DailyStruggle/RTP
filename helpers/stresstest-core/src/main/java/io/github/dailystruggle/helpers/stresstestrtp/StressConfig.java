package io.github.dailystruggle.helpers.stresstestrtp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Platform-neutral hierarchical configuration store for {@code stresstest-core}
 * (ADR-122 Decision 1 & 2).
 *
 * <p>Provides dot-separated path lookups ({@code ramp.pass.max-mspt-p95}),
 * section views ({@link #getConfigurationSection(String)}), programmatic
 * mutation ({@link #set(String, Object)}) for unit tests, and a self-contained
 * YAML loader covering the {@code config.yml} schema so carriers without
 * Bukkit's {@code YamlConfiguration} (such as {@code stresstest-fabric}) read
 * the same {@code config.yml} file directly.
 */
public final class StressConfig {

    private final Map<String, Object> root;

    public StressConfig() {
        this(new LinkedHashMap<>());
    }

    public StressConfig(Map<String, Object> backing) {
        this.root = new LinkedHashMap<>();
        if (backing != null) {
            for (Map.Entry<String, Object> e : backing.entrySet()) {
                set(e.getKey(), e.getValue());
            }
        }
    }

    public static StressConfig load(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return new StressConfig();
        }
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static StressConfig fromResource(Class<?> anchor, String resourcePath) {
        try (InputStream in = anchor.getResourceAsStream(resourcePath)) {
            if (in == null) return new StressConfig();
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            return new StressConfig();
        }
    }

    @SuppressWarnings("unchecked")
    public void set(String path, Object value) {
        if (path == null || path.isEmpty()) return;
        String[] parts = path.split("\\.");
        Map<String, Object> cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            Object next = cur.get(part);
            if (!(next instanceof Map<?, ?>)) {
                Map<String, Object> created = new LinkedHashMap<>();
                cur.put(part, created);
                cur = created;
            } else {
                cur = (Map<String, Object>) next;
            }
        }
        String leaf = parts[parts.length - 1];
        if (value == null) {
            cur.remove(leaf);
        } else if (value instanceof StressConfig sc) {
            cur.put(leaf, new LinkedHashMap<>(sc.root));
        } else {
            cur.put(leaf, value);
        }
    }

    public Object get(String path) {
        if (path == null || path.isEmpty()) return null;
        String[] parts = path.split("\\.");
        Object cur = root;
        for (String part : parts) {
            if (!(cur instanceof Map<?, ?> map)) return null;
            cur = map.get(part);
            if (cur == null) return null;
        }
        return cur;
    }

    public boolean getBoolean(String path, boolean def) {
        Object v = get(path);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) {
            if ("true".equalsIgnoreCase(s.trim())) return true;
            if ("false".equalsIgnoreCase(s.trim())) return false;
        }
        return def;
    }

    public int getInt(String path, int def) {
        Object v = get(path);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public long getLong(String path, long def) {
        Object v = get(path);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public double getDouble(String path, double def) {
        Object v = get(path);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public String getString(String path, String def) {
        Object v = get(path);
        if (v == null || v instanceof Map<?, ?> || v instanceof List<?>) return def;
        return String.valueOf(v);
    }

    public String getString(String path) {
        return getString(path, null);
    }

    public List<?> getList(String path) {
        Object v = get(path);
        if (v instanceof List<?> list) return list;
        return null;
    }

    public List<String> getStringList(String path) {
        Object v = get(path);
        if (!(v instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null && !(item instanceof Map<?, ?>)) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    public List<Double> getDoubleList(String path) {
        Object v = get(path);
        if (!(v instanceof List<?> list)) return List.of();
        List<Double> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Number n) {
                out.add(n.doubleValue());
            } else if (item != null) {
                try {
                    out.add(Double.parseDouble(String.valueOf(item).trim()));
                } catch (NumberFormatException ignored) {
                    // skip non-numeric
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public StressConfig getConfigurationSection(String path) {
        Object v = get(path);
        if (v instanceof Map<?, ?> map) {
            return new StressConfig((Map<String, Object>) map);
        }
        return null;
    }

    public Set<String> getKeys(boolean deep) {
        if (!deep) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(root.keySet()));
        }
        Set<String> keys = new LinkedHashSet<>();
        collectDeepKeys("", root, keys);
        return Collections.unmodifiableSet(keys);
    }

    @SuppressWarnings("unchecked")
    private static void collectDeepKeys(String prefix, Map<String, Object> map, Set<String> out) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String k = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            out.add(k);
            if (e.getValue() instanceof Map<?, ?> child) {
                collectDeepKeys(k, (Map<String, Object>) child, out);
            }
        }
    }

    /**
     * Parses a subset of YAML sufficient for {@code StressTestRTP}'s {@code config.yml}
     * (nested maps, inline arrays {@code [...]}, inline maps {@code {}}, block lists of
     * scalars or maps {@code - label: ...}).
     */
    @SuppressWarnings("unchecked")
    public static StressConfig parse(String yaml) {
        StressConfig cfg = new StressConfig();
        if (yaml == null || yaml.isEmpty()) return cfg;
        String[] lines = yaml.split("\r?\n");
        record Frame(int indent, Object container, String pendingKey) {}
        List<Frame> stack = new ArrayList<>();
        stack.add(new Frame(-1, cfg.root, null));

        for (String rawLine : lines) {
            String strippedComment = stripComment(rawLine);
            if (strippedComment.isBlank()) continue;
            int indent = countIndent(strippedComment);
            String trimmed = strippedComment.trim();

            while (stack.size() > 1 && indent <= stack.get(stack.size() - 1).indent()) {
                stack.remove(stack.size() - 1);
            }
            Frame top = stack.get(stack.size() - 1);

            if (trimmed.startsWith("- ") || trimmed.equals("-")) {
                List<Object> list;
                if (top.container() instanceof Map<?, ?> parentMap && top.pendingKey() != null) {
                    Map<String, Object> pm = (Map<String, Object>) parentMap;
                    Object existing = pm.get(top.pendingKey());
                    if (existing instanceof List<?>) {
                        list = (List<Object>) existing;
                    } else {
                        list = new ArrayList<>();
                        pm.put(top.pendingKey(), list);
                    }
                } else if (top.container() instanceof List<?> l) {
                    list = (List<Object>) l;
                } else {
                    continue;
                }
                String afterDash = trimmed.length() > 1 ? trimmed.substring(1).trim() : "";
                int colon = findUnquotedColon(afterDash);
                if (colon > 0) {
                    Map<String, Object> itemMap = new LinkedHashMap<>();
                    list.add(itemMap);
                    String k = unquote(afterDash.substring(0, colon).trim());
                    String vRaw = afterDash.substring(colon + 1).trim();
                    if (!vRaw.isEmpty()) {
                        itemMap.put(k, parseScalarOrInline(vRaw));
                    }
                    stack.add(new Frame(indent, itemMap, vRaw.isEmpty() ? k : null));
                } else if (!afterDash.isEmpty()) {
                    list.add(parseScalarOrInline(afterDash));
                }
                continue;
            }

            int colon = findUnquotedColon(trimmed);
            if (colon <= 0) continue;
            String key = unquote(trimmed.substring(0, colon).trim());
            String valRaw = trimmed.substring(colon + 1).trim();

            Map<String, Object> targetMap;
            if (top.container() instanceof Map<?, ?> m) {
                if (top.pendingKey() != null && indent > top.indent()) {
                    Map<String, Object> parent = (Map<String, Object>) m;
                    Object existing = parent.get(top.pendingKey());
                    if (existing instanceof Map<?, ?> child) {
                        targetMap = (Map<String, Object>) child;
                    } else {
                        targetMap = new LinkedHashMap<>();
                        parent.put(top.pendingKey(), targetMap);
                    }
                } else {
                    targetMap = (Map<String, Object>) m;
                }
            } else {
                continue;
            }

            if (valRaw.isEmpty()) {
                Map<String, Object> childMap = new LinkedHashMap<>();
                targetMap.put(key, childMap);
                stack.add(new Frame(indent, targetMap, key));
            } else {
                targetMap.put(key, parseScalarOrInline(valRaw));
            }
        }
        return cfg;
    }

    private static int countIndent(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        return i;
    }

    private static String stripComment(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle && (i == 0 || line.charAt(i - 1) != '\\')) inDouble = !inDouble;
            else if (c == '#' && !inSingle && !inDouble && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static int findUnquotedColon(String s) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle && (i == 0 || s.charAt(i - 1) != '\\')) inDouble = !inDouble;
            else if (c == ':' && !inSingle && !inDouble
                    && (i + 1 == s.length() || Character.isWhitespace(s.charAt(i + 1)))) {
                return i;
            }
        }
        return -1;
    }

    private static Object parseScalarOrInline(String raw) {
        String s = raw.trim();
        if (s.equals("[]")) return new ArrayList<>();
        if (s.equals("{}")) return new LinkedHashMap<String, Object>();
        if (s.startsWith("[") && s.endsWith("]")) {
            String inner = s.substring(1, s.length() - 1).trim();
            if (inner.isEmpty()) return new ArrayList<>();
            List<Object> out = new ArrayList<>();
            for (String part : splitTopLevelComma(inner)) {
                String p = part.trim();
                if (!p.isEmpty()) out.add(parseScalarOrInline(p));
            }
            return out;
        }
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return unquote(s);
        }
        if ("true".equalsIgnoreCase(s)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
        try {
            if (s.contains(".")) return Double.parseDouble(s);
            return Long.parseLong(s);
        } catch (NumberFormatException ignored) {
            return s;
        }
    }

    private static List<String> splitTopLevelComma(String s) {
        List<String> out = new ArrayList<>();
        boolean inSingle = false;
        boolean inDouble = false;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle && (i == 0 || s.charAt(i - 1) != '\\')) inDouble = !inDouble;
            else if (c == ',' && !inSingle && !inDouble) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            if (s.startsWith("\"") && s.endsWith("\"")) {
                return s.substring(1, s.length() - 1).replace("\\\"", "\"");
            }
            if (s.startsWith("'") && s.endsWith("'")) {
                return s.substring(1, s.length() - 1).replace("''", "'");
            }
        }
        return s;
    }
}
