package io.github.dailystruggle.rtp.common.permission;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.editor.EditorLoopbackJson;
import io.github.dailystruggle.rtp.common.importer.ForeignConfigImporter;
import io.github.dailystruggle.rtp.common.importer.ForeignConfigImporterRegistry;
import io.github.dailystruggle.rtp.common.importer.UniversalConfigImporter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives permission-migration sources from the server's plugin folders when no {@code source=} is named.
 *
 * <p>A folder counts as a foreign rtp plugin when its YAML keys hit at least {@link #MIN_CONCEPTS} distinct
 * rtp concepts (radius, center, cooldown, delay, worlds). Keys match a concept through the editor search
 * thesaurus ({@code synonyms} in the packaged {@code /editor/editor-data.json}) plus Levenshtein distance.
 * A node prefix then best-matches a derived source by normalized equality, containment or edit distance,
 * so an unlisted {@code FooRTP} works while {@code worldedit.*} (a {@code config.yml} with only a brush
 * radius) does not.
 */
public final class PermissionSourceResolver {

    /** Distinct rtp concepts a folder's YAML keys must hit to count as a source. */
    static final int MIN_CONCEPTS = 2;
    /** Maximum edit distance for a fuzzy key or prefix match. */
    static final int MAX_EDIT_DISTANCE = 2;
    /** Terms shorter than this only match exactly (containment / edit distance are too noisy). */
    static final int MIN_FUZZY_LENGTH = 5;
    /** Shortest name allowed to match by containment ({@code rtp} itself never does). */
    static final int MIN_CONTAINMENT_LENGTH = 4;

    static final String THESAURUS_RESOURCE = "/editor/editor-data.json";

    private static final int MAX_FILES_PER_FOLDER = 32;
    private static final long MAX_FILE_BYTES = 512L * 1024L;
    private static final Pattern YAML_KEY = Pattern.compile("^\\s*(?:-\\s+)?[\"']?([A-Za-z0-9_.\\-]+)[\"']?\\s*:");

    /** Concept -> normalized LeafRTP (or common foreign) key spellings. */
    private static final Map<String, Set<String>> CANONICAL = Map.of(
            "radius", Set.of("radius", "maxradius", "minradius", "centerradius", "range", "maxrange", "minrange"),
            "center", Set.of("center", "centerx", "centerz"),
            "cooldown", Set.of("cooldown", "teleportcooldown"),
            "delay", Set.of("delay", "teleportdelay", "warmup"),
            "worlds", Set.of("worlds", "customworlds", "enabledworlds"));

    private static volatile Map<String, Set<String>> conceptTerms;

    private PermissionSourceResolver() {}

    /** Server {@code plugins/} directory (parent of RTP's data folder), or {@code null} when unknown. */
    @Nullable
    public static Path resolvePluginsDir() {
        File pluginDir = null;
        if (RTP.configs != null && RTP.configs.pluginDirectory != null) {
            pluginDir = RTP.configs.pluginDirectory;
        } else if (RTP.serverAccessor != null) {
            pluginDir = RTP.serverAccessor.getPluginDirectory();
        }
        if (pluginDir == null) return null;
        File parent = pluginDir.getAbsoluteFile().getParentFile();
        return parent != null ? parent.toPath() : null;
    }

    /**
     * Normalized source names (folder names plus aliases of matching importers) for every folder under
     * {@code pluginsDir} that looks like a foreign rtp plugin. Empty when the directory is unknown.
     */
    @NotNull
    public static Set<String> deriveSources(@Nullable Path pluginsDir) {
        Set<String> sources = new LinkedHashSet<>();
        if (pluginsDir == null || !Files.isDirectory(pluginsDir)) return sources;
        for (Map.Entry<String, Path> e : ForeignConfigImporterRegistry.detectAvailableSources(pluginsDir).entrySet()) {
            if (!isRtpLikeFolder(e.getValue())) continue;
            String folder = normalize(e.getKey());
            if (folder.isEmpty()) continue;
            sources.add(folder);
            for (ForeignConfigImporter importer : ForeignConfigImporterRegistry.getAllImporters()) {
                if (importer instanceof UniversalConfigImporter) continue;
                if (!nameMatches(folder, normalize(importer.sourceName()))) continue;
                sources.add(normalize(importer.sourceName()));
                for (String alias : importer.directoryAliases()) {
                    String a = normalize(alias);
                    if (!a.isEmpty()) sources.add(a);
                }
            }
        }
        return sources;
    }

    /** True when the permission node's plugin prefix best-matches one of {@code sources}. */
    public static boolean matchesSource(@Nullable String permissionNode, @Nullable Collection<String> sources) {
        if (permissionNode == null || sources == null || sources.isEmpty()) return false;
        String lower = permissionNode.trim().toLowerCase(Locale.ROOT);
        int dot = lower.indexOf('.');
        String prefix = normalize(dot > 0 ? lower.substring(0, dot) : lower);
        if (prefix.isEmpty() || "rtp".equals(prefix)) return false;
        for (String s : sources) {
            if (nameMatches(prefix, normalize(s))) return true;
        }
        return false;
    }

    /** True when the folder's YAML keys hit at least {@link #MIN_CONCEPTS} distinct rtp concepts. */
    static boolean isRtpLikeFolder(@Nullable Path dir) {
        return conceptCount(dir) >= MIN_CONCEPTS;
    }

    static int conceptCount(@Nullable Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return 0;
        Set<String> keys = collectYamlKeys(dir);
        Map<String, Set<String>> terms = conceptTerms();
        Set<String> hit = new HashSet<>();
        for (String key : keys) {
            for (Map.Entry<String, Set<String>> c : terms.entrySet()) {
                if (hit.contains(c.getKey())) continue;
                for (String term : c.getValue()) {
                    if (keyMatches(key, term)) {
                        hit.add(c.getKey());
                        break;
                    }
                }
            }
            if (hit.size() == terms.size()) break;
        }
        return hit.size();
    }

    static boolean keyMatches(String key, String term) {
        if (key.isEmpty() || term.isEmpty()) return false;
        if (key.equals(term)) return true;
        if (term.length() < MIN_FUZZY_LENGTH) return false;
        if (key.contains(term)) return true;
        return key.length() >= MIN_FUZZY_LENGTH && levenshtein(key, term) <= MAX_EDIT_DISTANCE;
    }

    static boolean nameMatches(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        if (Math.min(a.length(), b.length()) >= MIN_CONTAINMENT_LENGTH && (a.contains(b) || b.contains(a))) {
            return true;
        }
        return a.length() >= MIN_FUZZY_LENGTH && b.length() >= MIN_FUZZY_LENGTH
                && levenshtein(a, b) <= MAX_EDIT_DISTANCE;
    }

    /** Lower-case ASCII letters and digits only. */
    static String normalize(@Nullable String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int k = 0; k < s.length(); k++) {
            char c = Character.toLowerCase(s.charAt(k));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
        }
        return sb.toString();
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /** Normalized YAML keys (whole and per dotted segment) from the folder root and one subdirectory level. */
    private static Set<String> collectYamlKeys(Path dir) {
        Set<String> keys = new HashSet<>();
        List<Path> files = new ArrayList<>();
        listYaml(dir, files);
        try (DirectoryStream<Path> subs = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path sub : subs) {
                if (files.size() >= MAX_FILES_PER_FOLDER) break;
                listYaml(sub, files);
            }
        } catch (IOException | RuntimeException ignored) {
            // Unreadable subdirectories just contribute no keys.
        }
        for (Path f : files) {
            try {
                if (Files.size(f) > MAX_FILE_BYTES) continue;
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    Matcher m = YAML_KEY.matcher(line);
                    if (!m.find()) continue;
                    String raw = m.group(1);
                    keys.add(normalize(raw));
                    for (String seg : raw.split("\\.")) keys.add(normalize(seg));
                }
            } catch (IOException | RuntimeException ignored) {
                // Non-UTF-8 or unreadable files contribute no keys.
            }
        }
        keys.remove("");
        return keys;
    }

    private static void listYaml(Path dir, List<Path> out) {
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "*.{yml,yaml}")) {
            for (Path p : s) {
                if (out.size() >= MAX_FILES_PER_FOLDER) return;
                if (Files.isRegularFile(p)) out.add(p);
            }
        } catch (IOException | RuntimeException ignored) {
            // Unreadable directory: no candidates.
        }
    }

    /** Canonical spellings plus every thesaurus word whose key list names one of them. */
    static Map<String, Set<String>> conceptTerms() {
        Map<String, Set<String>> cached = conceptTerms;
        if (cached != null) return cached;
        Map<String, Set<String>> terms = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : CANONICAL.entrySet()) {
            terms.put(e.getKey(), new HashSet<>(e.getValue()));
        }
        for (Map.Entry<String, List<String>> syn : loadThesaurus().entrySet()) {
            String word = normalize(syn.getKey());
            if (word.isEmpty()) continue;
            for (Map.Entry<String, Set<String>> c : CANONICAL.entrySet()) {
                for (String key : syn.getValue()) {
                    if (c.getValue().contains(normalize(key))) {
                        terms.get(c.getKey()).add(word);
                        break;
                    }
                }
            }
        }
        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        terms.forEach((k, v) -> frozen.put(k, Set.copyOf(v)));
        conceptTerms = Collections.unmodifiableMap(frozen);
        return conceptTerms;
    }

    private static Map<String, List<String>> loadThesaurus() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        try (InputStream in = PermissionSourceResolver.class.getResourceAsStream(THESAURUS_RESOURCE)) {
            if (in == null) {
                RTP.log(Level.FINE, "[RTP] permission source thesaurus missing: " + THESAURUS_RESOURCE);
                return out;
            }
            Object root = EditorLoopbackJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (!(root instanceof Map<?, ?> m) || !(m.get("synonyms") instanceof Map<?, ?> syn)) return out;
            for (Map.Entry<?, ?> e : syn.entrySet()) {
                if (!(e.getKey() instanceof String word) || !(e.getValue() instanceof List<?> list)) continue;
                List<String> keys = new ArrayList<>();
                for (Object o : list) if (o instanceof String s) keys.add(s);
                out.put(word, keys);
            }
        } catch (IOException | RuntimeException e) {
            RTP.log(Level.FINE, "[RTP] permission source thesaurus unreadable; using canonical keys only: " + e);
        }
        return out;
    }
}
