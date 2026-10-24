package io.github.dailystruggle.bit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bidirectional {@code (namespace, int) <-> String} catalog of BIT codes, populated by registration.
 *
 * <p>Owners reserve a namespace (key prefix) and register codes next to the check that emits them;
 * no central enum is edited to add a test. Invariants: namespace/id pairs and keys are unique,
 * every code belongs to exactly one reserved namespace, and retired identities stay reserved.
 * Re-registering an identical code is a no-op so reloads and repeated class initialisation are safe.
 *
 * <p>Thread-safe: writes are serialised, lookups are lock-free. The {@code BIT} namespace
 * ({@link BitCodes}) is reserved and populated by the constructor.
 */
public final class BitCodeRegistry {
    private final Map<NamespaceId, BitCode> byId = new ConcurrentHashMap<>();
    private final Map<String, BitCode> byKey = new ConcurrentHashMap<>();
    private final Map<String, BitCodeNamespace> namespaces = new ConcurrentHashMap<>();
    private final Map<NamespaceId, RetiredBitCode> retiredById = new ConcurrentHashMap<>();
    private final Map<String, RetiredBitCode> retiredByKey = new ConcurrentHashMap<>();
    private final Map<String, String> owners = new ConcurrentHashMap<>();
    private final List<NamespaceConflict> conflicts = new java.util.concurrent.CopyOnWriteArrayList<>();

    public BitCodeRegistry() {
        reserve(BitCodes.PREFIX, BitCodes.OWNER);
        BitCodes.all().forEach(this::register);
    }

    /**
     * Reserves {@code prefix} without naming an owner; repeating the reservation returns the
     * existing namespace. Prefixes are unique, while ids are local to each prefix.
     */
    public BitCodeNamespace reserve(String prefix) {
        return reserve(prefix, "");
    }

    /**
     * Reserves {@code prefix} for {@code owner} (for example a plugin or mod id). The same owner may
     * reserve again. A different owner still receives the namespace, so its own key and id
     * collisions surface at registration, but the clash is recorded in {@link #conflicts()} for a
     * startup check to report instead of failing silently.
     */
    public synchronized BitCodeNamespace reserve(String prefix, String owner) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        String claimant = owner == null ? "" : owner.trim();
        if (!BitCode.KEY_PATTERN.matcher(prefix).matches() || prefix.contains("_")) {
            throw new IllegalArgumentException("prefix must be a single UPPER_CASE word: " + prefix);
        }
        String holder = owners.getOrDefault(prefix, "");
        if (holder.isEmpty()) {
            if (!claimant.isEmpty()) owners.put(prefix, claimant);
        } else if (!claimant.isEmpty() && !holder.equals(claimant)) {
            NamespaceConflict conflict = new NamespaceConflict(prefix, holder, claimant);
            if (!conflicts.contains(conflict)) conflicts.add(conflict);
        }
        BitCodeNamespace existing = namespaces.get(prefix);
        if (existing != null) return existing;
        BitCodeNamespace namespace = new BitCodeNamespace(this, prefix);
        namespaces.put(prefix, namespace);
        return namespace;
    }

    /** Owner recorded for {@code prefix}, or empty when it was reserved without one. */
    public Optional<String> ownerOf(String prefix) {
        if (prefix == null) return Optional.empty();
        String owner = owners.get(normalizePrefix(prefix));
        return owner == null || owner.isEmpty() ? Optional.empty() : Optional.of(owner);
    }

    /** Prefixes claimed by more than one owner, in the order the clashes occurred. */
    public List<NamespaceConflict> conflicts() {
        return List.copyOf(conflicts);
    }

    /**
     * Registers {@code code}. Returns the registered instance; an identical prior registration is
     * returned unchanged. Throws {@link IllegalStateException} when the id or key is taken by a
     * different code or when no reserved namespace owns the key prefix.
     */
    public synchronized BitCode register(BitCode code) {
        Objects.requireNonNull(code, "code must not be null");
        String namespace = code.namespace();
        BitCodeNamespace owner = namespaces.get(namespace);
        if (owner == null) {
            throw new IllegalStateException("no namespace reserves key " + code.key());
        }
        if (!owner.ownsKey(code.key())) {
            throw new IllegalStateException(code.key() + " (" + code.id() + ") must use prefix "
                    + owner.prefix() + "_ of namespace " + owner.prefix());
        }
        NamespaceId identity = new NamespaceId(namespace, code.id());
        RetiredBitCode retiredId = retiredById.get(identity);
        RetiredBitCode retiredKey = retiredByKey.get(code.key());
        if (retiredId != null || retiredKey != null) {
            throw new IllegalStateException(code.key() + " (" + namespace + ":" + code.id()
                    + ") is retired as " + (retiredId != null ? retiredId.key() : retiredKey.key()));
        }
        BitCode sameId = byId.get(identity);
        BitCode sameKey = byKey.get(code.key());
        if (code.equals(sameId) && code.equals(sameKey)) return sameId;
        if (sameId != null) {
            throw new IllegalStateException(namespace + ":" + code.id() + " is already registered as " + sameId.key());
        }
        if (sameKey != null) {
            throw new IllegalStateException(code.key() + " is already registered with id " + sameKey.id());
        }
        byId.put(identity, code);
        byKey.put(code.key(), code);
        return code;
    }

    /** Namespace-qualified lookup; namespace prefixes are case-insensitive. */
    public Optional<BitCode> byId(String namespace, int id) {
        return namespace == null ? Optional.empty()
                : Optional.ofNullable(byId.get(new NamespaceId(normalizePrefix(namespace), id)));
    }

    /** Case-insensitive key lookup. */
    public Optional<BitCode> byKey(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(byKey.get(key.trim().toUpperCase(Locale.ROOT)));
    }

    public OptionalInt idOf(String key) {
        return byKey(key).map(code -> OptionalInt.of(code.id())).orElse(OptionalInt.empty());
    }

    public Optional<String> keyOf(String namespace, int id) {
        return byId(namespace, id).map(BitCode::key);
    }

    /** Returns the code for {@code key} or throws {@link IllegalArgumentException} if it is unregistered. */
    public BitCode require(String key) {
        return byKey(key).orElseThrow(() -> new IllegalArgumentException("unregistered BIT code: " + key));
    }

    public Optional<BitCodeNamespace> namespace(String prefix) {
        return prefix == null ? Optional.empty() : Optional.ofNullable(namespaces.get(normalizePrefix(prefix)));
    }

    synchronized RetiredBitCode retire(RetiredBitCode code) {
        Objects.requireNonNull(code, "code must not be null");
        BitCodeNamespace owner = namespaces.get(code.namespace());
        if (owner == null || !owner.ownsKey(code.key())) {
            throw new IllegalStateException("no namespace reserves retired code " + code.key());
        }
        NamespaceId identity = new NamespaceId(code.namespace(), code.id());
        if (byId.containsKey(identity) || byKey.containsKey(code.key())) {
            throw new IllegalStateException("active code cannot be retired before removal: " + code.key());
        }
        RetiredBitCode sameId = retiredById.get(identity);
        RetiredBitCode sameKey = retiredByKey.get(code.key());
        if (code.equals(sameId) && code.equals(sameKey)) return sameId;
        if (sameId != null) {
            throw new IllegalStateException(identity + " is already retired as " + sameId.key());
        }
        if (sameKey != null) {
            throw new IllegalStateException(code.key() + " is already retired with id " + sameKey.id());
        }
        retiredById.put(identity, code);
        retiredByKey.put(code.key(), code);
        return code;
    }

    /** All retired code identities ordered by namespace and id. */
    public List<RetiredBitCode> retired() {
        List<RetiredBitCode> list = new ArrayList<>(retiredById.values());
        list.sort(Comparator.comparing(RetiredBitCode::namespace).thenComparingInt(RetiredBitCode::id));
        return List.copyOf(list);
    }

    /** All registered codes ordered by namespace and namespace-local id. */
    public List<BitCode> codes() {
        List<BitCode> codes = new ArrayList<>(byId.values());
        codes.sort(Comparator.comparing(BitCode::namespace).thenComparingInt(BitCode::id));
        return List.copyOf(codes);
    }

    /** All reserved namespaces ordered by prefix. */
    public List<BitCodeNamespace> namespaces() {
        List<BitCodeNamespace> list = new ArrayList<>(namespaces.values());
        list.sort(Comparator.comparing(BitCodeNamespace::prefix));
        return List.copyOf(list);
    }

    /** Versioned JSON export of the catalog, for generated docs and LLM lookup of remediation text. */
    public String toCatalogJson() {
        StringBuilder json = new StringBuilder("{\"schema\":").append(BitReport.CURRENT_SCHEMA).append(",\"namespaces\":[");
        List<BitCodeNamespace> reserved = namespaces();
        for (int i = 0; i < reserved.size(); i++) {
            BitCodeNamespace ns = reserved.get(i);
            if (i > 0) json.append(',');
            json.append("{\"prefix\":\"").append(BitJson.escape(ns.prefix())).append("\"}");
        }
        json.append("],\"codes\":[");
        List<BitCode> codes = codes();
        for (int i = 0; i < codes.size(); i++) {
            BitCode code = codes.get(i);
            if (i > 0) json.append(',');
            json.append("{\"id\":").append(code.id())
                    .append(",\"ns\":\"").append(BitJson.escape(code.namespace())).append('"')
                    .append(",\"key\":\"").append(BitJson.escape(code.key()))
                    .append("\",\"severity\":\"").append(code.defaultSeverity().name())
                    .append("\",\"tier\":\"").append(code.tier().name())
                    .append("\",\"subsystem\":\"").append(BitJson.escape(code.subsystem()))
                    .append("\",\"messageKey\":\"").append(BitJson.escape(code.messageKey()))
                    .append("\",\"remediation\":\"").append(BitJson.escape(code.remediation())).append("\"}");
        }
        json.append("],\"retired\":[");
        List<RetiredBitCode> retired = retired();
        for (int i = 0; i < retired.size(); i++) {
            RetiredBitCode code = retired.get(i);
            if (i > 0) json.append(',');
            json.append("{\"ns\":\"").append(BitJson.escape(code.namespace()))
                    .append("\",\"id\":").append(code.id())
                    .append(",\"key\":\"").append(BitJson.escape(code.key()))
                    .append("\",\"since\":\"").append(BitJson.escape(code.since())).append("\"}");
        }
        return json.append("]}").toString();
    }

    private static String normalizePrefix(String prefix) {
        return prefix.trim().toUpperCase(Locale.ROOT);
    }

    private record NamespaceId(String namespace, int id) {}
}
