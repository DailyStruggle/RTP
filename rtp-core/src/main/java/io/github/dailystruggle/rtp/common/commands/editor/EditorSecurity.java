package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.common.RTP;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Editor hardening shared by the snapshot, apply and transport paths (ADR-104, ADR-106):
 * secret redaction of uploaded YAML and its round-trip restore, the apply path allow-list,
 * the https-unless-loopback URL rule, token log prefixes and scheduler-backed async work.
 */
final class EditorSecurity {

    /** Replaces secret values in uploaded / exported YAML; an applied sentinel keeps the on-disk value. */
    static final String REDACTED = "<redacted>";

    /** Normalised (lowercase, no {@code -}/{@code _}) key suffixes that mark a secret. */
    private static final String[] SECRET_SUFFIXES = {"password", "passwd", "secret", "token", "apikey", "credential", "credentials"};
    /** {@code scheme://user:pass@} userinfo or a {@code password=} style query parameter. */
    private static final Pattern URL_CREDENTIALS = Pattern.compile(
            "(?i)(://[^/\\s@:]*:[^/\\s@]*@)|([?&;]\\s*(password|passwd|pwd)=)");
    private static final Pattern KEY = Pattern.compile("^((['\"]?)([A-Za-z0-9_.\\-]+)\\2\\s*:)(\\s.*|)$");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");
    /** A mapping key inside flow text ({@code {a: 1, "b": 2}}). */
    private static final Pattern FLOW_KEY = Pattern.compile(
            "(?:^|[{,\\[\\s])(['\"]?)([A-Za-z0-9_.\\-]+)\\1\\s*:");
    private static final String REDACTED_QUOTED = "\"" + REDACTED + "\"";

    private EditorSecurity() {
    }

    // ---- redaction ----

    /** {@code configs} with every secret value replaced by {@link #REDACTED}; non-YAML entries unchanged. */
    static Map<String, String> redactConfigs(Map<String, String> configs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : configs.entrySet()) {
            String v = e.getValue();
            out.put(e.getKey(), v != null && isYamlName(e.getKey()) ? redactYaml(v) : v);
        }
        return out;
    }

    /**
     * Line-level redaction: scalar values of secret-named keys (or under one), and any value carrying
     * URL credentials, become {@code "<redacted>"}; a secret block scalar loses its body lines. A flow
     * collection ({@code {..}} / {@code [..]}, single- or multi-line) holding a secret key or URL
     * credentials is redacted as a whole value.
     */
    static String redactYaml(String text) {
        if (text == null || text.isEmpty()) return text;
        List<Line> lines = walk(text);
        Set<Integer> dropped = new HashSet<>();
        StringBuilder sb = new StringBuilder(text.length());
        for (int k = 0; k < lines.size(); k++) {
            Line l = lines.get(k);
            if (l.owner >= 0 && dropped.contains(l.owner)) continue;
            String out = l.raw;
            if (l.path != null) {
                boolean secret = isSecretPath(l.path);
                if (l.block) {
                    if (secret || (l.flow && flowHasSecret(ownedText(lines, k)))) {
                        out = l.head() + (l.bare ? "" : " ") + REDACTED_QUOTED + l.cr();
                        dropped.add(k);
                    }
                } else {
                    String[] sv = splitValue(l.rest);
                    String plain = unquote(sv[0]);
                    if (!plain.isEmpty() && !isNullish(sv[0]) && !plain.equals(REDACTED)
                            && (secret || URL_CREDENTIALS.matcher(plain).find()
                                || (l.flow && flowHasSecret(l.rest)))) {
                        out = l.head() + (l.bare ? "" : " ") + REDACTED_QUOTED + sv[1] + l.cr();
                    }
                }
            }
            if (k > 0) sb.append('\n');
            sb.append(out);
        }
        return sb.toString();
    }

    /**
     * {@code incoming} with each {@link #REDACTED} value replaced by the value at the same key path in
     * {@code current} (block scalar and multi-line flow bodies included), so a round trip never writes
     * the sentinel.
     *
     * @throws IllegalArgumentException when a sentinel has no current value to keep, or any sentinel
     *     beyond those already on disk would remain in the result
     */
    static String restoreRedacted(String file, String incoming, String current) {
        if (incoming == null || !incoming.contains(REDACTED)) return incoming;
        List<Line> in = walk(incoming);
        List<Line> cur = current == null ? List.of() : walk(current);
        Map<String, Integer> byPath = new HashMap<>();
        for (int k = 0; k < cur.size(); k++) {
            Line c = cur.get(k);
            if (c.path == null) continue;
            // A bare value line ("key:" then an indented flow) carries the value of its path.
            if (c.bare) byPath.put(c.path, k);
            else byPath.putIfAbsent(c.path, k);
        }
        StringBuilder sb = new StringBuilder(incoming.length() + 64);
        for (int k = 0; k < in.size(); k++) {
            Line l = in.get(k);
            String out = l.raw;
            List<String> body = List.of();
            if (l.path != null && !l.block && REDACTED.equals(unquote(splitValue(l.rest)[0]))) {
                Integer ci = byPath.get(l.path);
                Line c = ci == null ? null : cur.get(ci);
                if (c == null || (!c.block && REDACTED.equals(unquote(splitValue(c.rest)[0])))) {
                    throw new IllegalArgumentException("'" + file + "': '" + l.path + "' is " + REDACTED
                            + " but has no current value to keep; enter the real value");
                }
                out = l.head() + c.rest + l.cr();
                if (c.block) {
                    body = new ArrayList<>();
                    for (int j = ci + 1; j < cur.size() && cur.get(j).owner == ci; j++) body.add(cur.get(j).raw);
                    // Trailing blank lines belong to the document, not the block
                    while (!body.isEmpty() && body.get(body.size() - 1).isBlank()) body.remove(body.size() - 1);
                }
            }
            if (k > 0) sb.append('\n');
            sb.append(out);
            for (String b : body) sb.append('\n').append(b);
        }
        String result = sb.toString();
        // Fail closed: an unmatched sentinel would overwrite the real secret on disk.
        if (countOf(result, REDACTED) > countOf(current == null ? "" : current, REDACTED)) {
            throw new IllegalArgumentException("'" + file + "' still contains " + REDACTED
                    + " where no current value could be matched; enter the real value");
        }
        return result;
    }

    private static int countOf(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    /** {@code lines[k]}'s value plus its owned continuation lines. */
    private static String ownedText(List<Line> lines, int k) {
        StringBuilder sb = new StringBuilder(lines.get(k).rest);
        for (int j = k + 1; j < lines.size() && lines.get(j).owner == k; j++) sb.append('\n').append(lines.get(j).raw);
        return sb.toString();
    }

    /** Flow text holding a secret-named key or URL credentials (over-matching only over-redacts). */
    static boolean flowHasSecret(String text) {
        if (text == null) return false;
        if (URL_CREDENTIALS.matcher(text).find()) return true;
        Matcher m = FLOW_KEY.matcher(text);
        while (m.find()) {
            if (isSecretKey(m.group(2))) return true;
        }
        return false;
    }

    /**
     * Net flow nesting change of one line: brackets outside quotes, up to a comment. A quote opens
     * only at a token start, so an apostrophe inside a plain scalar does not.
     */
    private static int flowDelta(String s) {
        int d = 0;
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) {
                if (q == '"' && c == '\\') {
                    i++;
                } else if (c == q) {
                    q = 0;
                }
                continue;
            }
            char prev = i == 0 ? ' ' : s.charAt(i - 1);
            boolean tokenStart = Character.isWhitespace(prev) || prev == '{' || prev == '[' || prev == ',' || prev == ':';
            if ((c == '"' || c == '\'') && tokenStart) q = c;
            else if (c == '#' && Character.isWhitespace(prev)) break;
            else if (c == '{' || c == '[') d++;
            else if (c == '}' || c == ']') d--;
        }
        return d;
    }

    static boolean isSecretKey(String key) {
        if (key == null) return false;
        String n = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        for (String s : SECRET_SUFFIXES) {
            if (n.endsWith(s)) return true;
        }
        return false;
    }

    private static boolean isSecretPath(String path) {
        for (String seg : path.split("\\.")) {
            if (!seg.startsWith("[") && isSecretKey(seg)) return true;
        }
        return false;
    }

    private static boolean isNullish(String v) {
        return v.equals("~") || v.equalsIgnoreCase("null");
    }

    /** One physical line; {@code path} set for key lines, {@code owner} for block scalar body lines. */
    private static final class Line {
        final String raw;
        String path;
        String rest;
        int headEnd;
        boolean block;
        /** Value is a flow collection; with {@link #block} it continues on owned lines. */
        boolean flow;
        /** Value line without a key (list item or indented value of the enclosing key). */
        boolean bare;
        int owner = -1;

        Line(String raw) {
            this.raw = raw;
        }

        String body() {
            return raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
        }

        String head() {
            return body().substring(0, headEnd);
        }

        String cr() {
            return raw.endsWith("\r") ? "\r" : "";
        }
    }

    /**
     * Indentation-tracked key paths ({@code a.b}, list items {@code a.[0].b}). A flow collection is one
     * value (continuation lines owned like a block scalar body); multi-line plain scalars are opaque.
     */
    private static List<Line> walk(String text) {
        String[] raw = text.split("\n", -1);
        List<Line> out = new ArrayList<>(raw.length);
        List<Integer> indents = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        Map<String, Integer> items = new HashMap<>();
        int blockOwner = -1;
        int blockIndent = -1;
        int flowOwner = -1;
        int flowIndent = -1;
        int flowDepth = 0;
        for (String r : raw) {
            Line l = new Line(r);
            out.add(l);
            String body = l.body();
            int indent = 0;
            while (indent < body.length() && body.charAt(indent) == ' ') indent++;
            if (flowOwner >= 0) {
                String s = body.substring(indent);
                // Flow lines sit deeper than the owner; a shallower non-closer ends an unbalanced flow.
                if (body.isBlank() || indent > flowIndent || s.startsWith("}") || s.startsWith("]")) {
                    l.owner = flowOwner;
                    flowDepth += flowDelta(body);
                    if (flowDepth <= 0) flowOwner = -1;
                    continue;
                }
                flowOwner = -1;
            }
            if (blockOwner >= 0) {
                if (body.isBlank() || indent > blockIndent) {
                    l.owner = blockOwner;
                    continue;
                }
                blockOwner = -1;
            }
            String t = body.substring(indent);
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (t.equals("---") || t.startsWith("--- ")) {
                indents.clear();
                keys.clear();
                items.clear();
                continue;
            }
            int keyIndent = indent;
            if (t.equals("-") || t.startsWith("- ")) {
                pop(indents, keys, indent);
                int n = items.merge(String.join(".", keys) + "@" + indent, 1, Integer::sum) - 1;
                indents.add(indent);
                keys.add("[" + n + "]");
                int d = 1;
                while (d < t.length() && t.charAt(d) == ' ') d++;
                keyIndent = indent + d;
                t = t.substring(d);
                if (t.isEmpty()) continue;
            }
            Matcher m = KEY.matcher(t);
            if (!m.matches()) {
                boolean flowStart = t.startsWith("{") || t.startsWith("[");
                if (!flowStart && !splitValue(t)[0].equals(REDACTED_QUOTED)) continue;
                pop(indents, keys, keyIndent);
                l.path = String.join(".", keys);
                l.rest = t;
                l.headEnd = body.length() - t.length();
                l.bare = true;
            } else {
                pop(indents, keys, keyIndent);
                indents.add(keyIndent);
                keys.add(m.group(3));
                l.path = String.join(".", keys);
                l.rest = m.group(4);
                l.headEnd = body.length() - l.rest.length();
            }
            String v = l.rest.strip();
            if (v.startsWith("|") || v.startsWith(">")) {
                l.block = true;
                blockOwner = out.size() - 1;
                blockIndent = keyIndent;
            } else if (v.startsWith("{") || v.startsWith("[")) {
                l.flow = true;
                int depth = flowDelta(l.rest);
                if (depth > 0) {
                    l.block = true;
                    flowOwner = out.size() - 1;
                    flowIndent = indent;
                    flowDepth = depth;
                }
            }
        }
        return out;
    }

    private static void pop(List<Integer> indents, List<String> keys, int indent) {
        while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
            indents.remove(indents.size() - 1);
            keys.remove(keys.size() - 1);
        }
    }

    /** {value as written, trailing whitespace + comment} of a key line's text after the colon. */
    private static String[] splitValue(String rest) {
        int i = 0;
        while (i < rest.length() && Character.isWhitespace(rest.charAt(i))) i++;
        if (i >= rest.length()) return new String[]{"", ""};
        char q = rest.charAt(i);
        if (q == '"' || q == '\'') {
            int j = i + 1;
            while (j < rest.length()) {
                char c = rest.charAt(j);
                if (q == '"' && c == '\\') {
                    j += 2;
                    continue;
                }
                if (c == q) {
                    if (q == '\'' && j + 1 < rest.length() && rest.charAt(j + 1) == '\'') {
                        j += 2;
                        continue;
                    }
                    break;
                }
                j++;
            }
            int end = Math.min(j + 1, rest.length());
            return new String[]{rest.substring(i, end), rest.substring(end)};
        }
        int h = -1;
        for (int j = i + 1; j < rest.length(); j++) {
            if (rest.charAt(j) == '#' && Character.isWhitespace(rest.charAt(j - 1))) {
                h = j;
                break;
            }
        }
        String v = h < 0 ? rest.substring(i) : rest.substring(i, h);
        String trimmed = v.stripTrailing();
        return new String[]{trimmed, v.substring(trimmed.length()) + (h < 0 ? "" : rest.substring(h))};
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'') && v.charAt(v.length() - 1) == v.charAt(0)) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    // ---- apply paths ----

    static boolean isYamlName(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yml") || lower.endsWith(".yaml");
    }

    /**
     * Apply allow-list: a relative {@code /}-separated {@code *.yml}/{@code *.yaml} path with no empty,
     * {@code .}/{@code ..} or hidden segment, no NUL, backslash or colon, outside {@code editor/}
     * (channel keys, trusted list, exported page).
     *
     * @throws IllegalArgumentException naming the path otherwise
     */
    static void checkConfigPath(String path) {
        boolean ok = path != null && !path.isBlank() && path.indexOf('\0') < 0 && path.indexOf('\\') < 0
                && path.indexOf(':') < 0 && !path.startsWith("/") && isYamlName(path)
                && !path.toLowerCase(Locale.ROOT).startsWith("editor/");
        if (ok) {
            for (String seg : path.split("/", -1)) {
                if (seg.isEmpty() || seg.startsWith(".") || !seg.equals(seg.strip())) {
                    ok = false;
                    break;
                }
            }
        }
        if (!ok) throw new IllegalArgumentException("Illegal file path in payload: '" + path + "'");
    }

    /**
     * {@code target}'s nearest existing path (itself when present) resolves, links followed, inside
     * {@code root}'s real path.
     *
     * @throws IllegalArgumentException when it does not or cannot be resolved
     */
    static void checkRealPath(Path root, Path target, String rel) {
        try {
            Path realRoot = root.toRealPath();
            Path p = target;
            while (p != null && !Files.exists(p, LinkOption.NOFOLLOW_LINKS)) p = p.getParent();
            if (p == null || !p.toRealPath().startsWith(realRoot)) {
                throw new IllegalArgumentException("'" + rel + "' resolves outside the plugin folder");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("'" + rel + "' resolves outside the plugin folder: " + e.getMessage(), e);
        }
    }

    // ---- transport ----

    /** https / wss, or http / ws to a loopback host; anything else is refused. */
    static boolean secureOrLoopback(URI u) {
        if (u == null || u.getScheme() == null || u.getHost() == null) return false;
        String scheme = u.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("https") || scheme.equals("wss")) return true;
        return (scheme.equals("http") || scheme.equals("ws")) && isLoopbackHost(u.getHost());
    }

    static boolean isLoopbackHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("0:0:0:0:0:0:0:1");
    }

    /** Session / bytebin token shape: also keeps it a single path segment. */
    static boolean isToken(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    /** First 6 characters and an ellipsis, for logs. */
    static String tokenPrefix(String token) {
        if (token == null) return "null";
        return token.length() <= 6 ? token : token.substring(0, 6) + "\u2026";
    }

    // ---- scheduling ----

    /** {@code task} on {@code RTP.scheduler}'s async pool; the common pool only without a platform (unit tests). */
    static CompletableFuture<Void> runAsync(Runnable task) {
        return supplyAsync(() -> {
            task.run();
            return null;
        });
    }

    /** As {@link #runAsync}, completing with {@code task}'s result or failure. */
    static <T> CompletableFuture<T> supplyAsync(Supplier<T> task) {
        RTPScheduler scheduler = RTP.scheduler;
        if (scheduler == null) return CompletableFuture.supplyAsync(task);
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            scheduler.runTaskAsynchronously(() -> {
                try {
                    f.complete(task.get());
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                }
            });
        } catch (RuntimeException e) {
            f.completeExceptionally(e);
        }
        return f;
    }
}
