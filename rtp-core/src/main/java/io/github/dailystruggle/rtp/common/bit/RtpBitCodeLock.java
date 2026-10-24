package io.github.dailystruggle.rtp.common.bit;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitCodeRegistry;
import io.github.dailystruggle.bit.RetiredBitCode;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reads and verifies the append-only release lock for BIT code identities. */
public final class RtpBitCodeLock {
    private static final String HEADER = "# namespace id key defaultSeverity\n";

    private RtpBitCodeLock() {}

    public record Entry(String namespace, int id, String key, Level severity) {
        public Entry {
            namespace = Objects.requireNonNull(namespace, "namespace must not be null").trim();
            key = Objects.requireNonNull(key, "key must not be null").trim();
            if (!namespace.matches("[A-Z][A-Z0-9]*") || id < 1 || !key.startsWith(namespace + "_")
                    || !key.matches("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*")) {
                throw new IllegalArgumentException("invalid BIT lock identity: " + namespace + " " + id + " " + key);
            }
            Objects.requireNonNull(severity, "severity must not be null");
            if (severity == Level.ALL || severity == Level.OFF) {
                throw new IllegalArgumentException("severity must be concrete: " + severity);
            }
        }

        String identity() {
            return namespace + ":" + id;
        }
    }

    /** Parses nonblank, non-comment lines in {@code NAMESPACE ID KEY SEVERITY} format. */
    public static List<Entry> parse(String content) {
        Objects.requireNonNull(content, "content must not be null");
        List<Entry> entries = new ArrayList<>();
        for (String line : content.split("\\R")) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) continue;
            String[] fields = value.split("\\s+");
            if (fields.length != 4) throw new IllegalArgumentException("invalid BIT lock row: " + line);
            try {
                entries.add(new Entry(fields[0], Integer.parseInt(fields[1]), fields[2], Level.valueOf(fields[3])));
            } catch (IllegalArgumentException invalidRow) {
                throw new IllegalArgumentException("invalid BIT lock row: " + line, invalidRow);
            }
        }
        validateEntries(entries);
        return sorted(entries);
    }

    /** Renders a canonical lock file. */
    public static String render(List<Entry> entries) {
        Objects.requireNonNull(entries, "entries must not be null");
        validateEntries(entries);
        StringBuilder output = new StringBuilder(HEADER);
        for (Entry entry : sorted(entries)) {
            output.append(entry.namespace()).append(' ').append(entry.id()).append(' ')
                    .append(entry.key()).append(' ').append(entry.severity().name()).append('\n');
        }
        return output.toString();
    }

    /** Reports removed, renamed, renumbered, reused, or severity-changed locked rows. */
    public static List<String> violations(BitCodeRegistry registry, List<Entry> lock) {
        return violations(registry, lock, null);
    }

    /** Reports violations for only the selected addon's namespace. */
    public static List<String> violations(BitCodeRegistry registry, List<Entry> lock, String namespaceFilter) {
        return check(registry, lock, namespaceFilter).stream().map(Violation::message).toList();
    }

    /** Appends new active identities and refreshes severity, refusing any identity mutation. */
    public static List<Entry> update(BitCodeRegistry registry, List<Entry> lock) {
        return update(registry, lock, null);
    }

    /** Appends and refreshes rows for only the selected addon's namespace. */
    public static List<Entry> update(BitCodeRegistry registry, List<Entry> lock, String namespaceFilter) {
        List<Violation> violations = check(registry, lock, namespaceFilter);
        List<String> identityViolations = violations.stream().filter(Violation::identity)
                .map(Violation::message).toList();
        if (!identityViolations.isEmpty()) {
            throw new IllegalStateException("BIT code lock identity violations: " + String.join("; ", identityViolations));
        }

        Map<String, Entry> updated = new HashMap<>();
        String prefix = normalizeNamespace(namespaceFilter);
        for (Entry entry : lock.stream().filter(value -> prefix == null || value.namespace().equals(prefix)).toList()) {
            BitCode active = registry.byKey(entry.key()).orElse(null);
            Level severity = active == null ? entry.severity() : active.defaultSeverity();
            updated.put(entry.identity(), new Entry(entry.namespace(), entry.id(), entry.key(), severity));
        }
        for (BitCode code : registry.codes()) {
            if (prefix != null && !code.namespace().equals(prefix)) continue;
            updated.putIfAbsent(identity(code.namespace(), code.id()),
                    new Entry(code.namespace(), code.id(), code.key(), code.defaultSeverity()));
        }
        return sorted(updated.values());
    }

    private static List<Violation> check(BitCodeRegistry registry, List<Entry> lock, String namespaceFilter) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(lock, "lock must not be null");
        String prefix = normalizeNamespace(namespaceFilter);
        List<Entry> scopedLock = lock.stream()
                .filter(entry -> prefix == null || entry.namespace().equals(prefix)).toList();
        validateEntries(scopedLock);
        Map<String, Entry> lockedByIdentity = new HashMap<>();
        Map<String, Entry> lockedByKey = new HashMap<>();
        scopedLock.forEach(entry -> {
            lockedByIdentity.put(entry.identity(), entry);
            lockedByKey.put(entry.key(), entry);
        });

        List<Violation> violations = new ArrayList<>();
        for (Entry entry : scopedLock) {
            BitCode currentId = registry.byId(entry.namespace(), entry.id()).orElse(null);
            BitCode currentKey = registry.byKey(entry.key()).orElse(null);
            if (currentId != null && currentId.key().equals(entry.key())) {
                if (currentId.defaultSeverity() != entry.severity()) {
                    violations.add(new Violation(false, entry.identity() + " " + entry.key()
                            + " severity changed from " + entry.severity() + " to " + currentId.defaultSeverity()));
                }
                continue;
            }
            RetiredBitCode retired = registry.retired().stream()
                    .filter(code -> prefix == null || code.namespace().equals(prefix))
                    .filter(code -> code.namespace().equals(entry.namespace()) && code.id() == entry.id()
                            && code.key().equals(entry.key()))
                    .findFirst().orElse(null);
            if (retired != null) continue;
            if (currentId != null) {
                violations.add(new Violation(true, entry.identity() + " was " + entry.key()
                        + " but is now " + currentId.key()));
            } else if (currentKey != null) {
                violations.add(new Violation(true, entry.key() + " moved from " + entry.identity()
                        + " to " + identity(currentKey.namespace(), currentKey.id())));
            } else {
                violations.add(new Violation(true, entry.identity() + " " + entry.key()
                        + " is missing and has no matching retirement marker"));
            }
        }

        for (RetiredBitCode retired : registry.retired()) {
            if (prefix != null && !retired.namespace().equals(prefix)) continue;
            if (!lockedByIdentity.containsKey(identity(retired.namespace(), retired.id()))
                    && !lockedByKey.containsKey(retired.key())) {
                violations.add(new Violation(true, identity(retired.namespace(), retired.id()) + " " + retired.key()
                        + " is retired but absent from the lock; its previous severity cannot be inferred"));
            }
        }
        return List.copyOf(violations);
    }

    private static void validateEntries(List<Entry> entries) {
        Set<String> identities = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (Entry entry : entries) {
            Objects.requireNonNull(entry, "lock entry must not be null");
            if (!identities.add(entry.identity())) throw new IllegalArgumentException("duplicate BIT lock id: " + entry.identity());
            if (!keys.add(entry.key())) throw new IllegalArgumentException("duplicate BIT lock key: " + entry.key());
        }
    }

    private static List<Entry> sorted(Iterable<Entry> entries) {
        List<Entry> result = new ArrayList<>();
        entries.forEach(result::add);
        result.sort(Comparator.comparing(Entry::namespace).thenComparingInt(Entry::id));
        return List.copyOf(result);
    }

    private static String identity(String namespace, int id) {
        return namespace + ":" + id;
    }

    private static String normalizeNamespace(String namespace) {
        return namespace == null || namespace.isBlank() ? null : namespace.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private record Violation(boolean identity, String message) {}
}
