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
 * Bidirectional {@code int <-> String} catalog of BIT codes, populated by registration.
 *
 * <p>Owners reserve a namespace (key prefix plus id range) and register codes next to the check that
 * emits them; no central enum is edited to add a test. Invariants: ids and keys are unique, every
 * code lies in exactly one reserved namespace, and re-registering an identical code is a no-op so
 * reloads and repeated class initialisation are safe. A conflicting registration throws.
 *
 * <p>Thread-safe: writes are serialised, lookups are lock-free. The {@code BIT} namespace
 * ({@link BitCodes}) is reserved and populated by the constructor.
 */
public final class BitCodeRegistry {
    private final Map<Integer, BitCode> byId = new ConcurrentHashMap<>();
    private final Map<String, BitCode> byKey = new ConcurrentHashMap<>();
    private final Map<String, BitCodeNamespace> namespaces = new ConcurrentHashMap<>();

    public BitCodeRegistry() {
        reserve(BitCodes.PREFIX, BitCodes.FIRST_ID, BitCodes.LAST_ID);
        BitCodes.all().forEach(this::register);
    }

    /**
     * Reserves {@code prefix} with ids {@code [firstId, lastId]}. Reserving the identical range again
     * returns the existing namespace; a different range for the prefix, or an overlap with another
     * namespace, throws {@link IllegalStateException}.
     */
    public synchronized BitCodeNamespace reserve(String prefix, int firstId, int lastId) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        if (!BitCode.KEY_PATTERN.matcher(prefix).matches() || prefix.contains("_")) {
            throw new IllegalArgumentException("prefix must be a single UPPER_CASE word: " + prefix);
        }
        if (firstId < 1 || lastId < firstId) {
            throw new IllegalArgumentException("invalid id range " + firstId + ".." + lastId);
        }
        BitCodeNamespace existing = namespaces.get(prefix);
        if (existing != null) {
            if (existing.firstId() == firstId && existing.lastId() == lastId) return existing;
            throw new IllegalStateException("prefix " + prefix + " is already reserved as " + existing);
        }
        for (BitCodeNamespace other : namespaces.values()) {
            if (other.overlaps(firstId, lastId)) {
                throw new IllegalStateException(prefix + "[" + firstId + ".." + lastId + "] overlaps " + other);
            }
        }
        BitCodeNamespace namespace = new BitCodeNamespace(this, prefix, firstId, lastId);
        namespaces.put(prefix, namespace);
        return namespace;
    }

    /**
     * Registers {@code code}. Returns the registered instance; an identical prior registration is
     * returned unchanged. Throws {@link IllegalStateException} when the id or key is taken by a
     * different code or when no reserved namespace owns both the id and the key prefix.
     */
    public synchronized BitCode register(BitCode code) {
        Objects.requireNonNull(code, "code must not be null");
        BitCodeNamespace owner = namespaceForId(code.id())
                .orElseThrow(() -> new IllegalStateException("no namespace reserves id " + code.id()
                        + " for " + code.key()));
        if (!owner.ownsKey(code.key())) {
            throw new IllegalStateException(code.key() + " (" + code.id() + ") must use prefix "
                    + owner.prefix() + "_ of namespace " + owner);
        }
        BitCode sameId = byId.get(code.id());
        BitCode sameKey = byKey.get(code.key());
        if (code.equals(sameId) && code.equals(sameKey)) return sameId;
        if (sameId != null) {
            throw new IllegalStateException("id " + code.id() + " is already registered as " + sameId.key());
        }
        if (sameKey != null) {
            throw new IllegalStateException(code.key() + " is already registered with id " + sameKey.id());
        }
        byId.put(code.id(), code);
        byKey.put(code.key(), code);
        return code;
    }

    public Optional<BitCode> byId(int id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Case-insensitive key lookup. */
    public Optional<BitCode> byKey(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(byKey.get(key.trim().toUpperCase(Locale.ROOT)));
    }

    public OptionalInt idOf(String key) {
        return byKey(key).map(code -> OptionalInt.of(code.id())).orElse(OptionalInt.empty());
    }

    public Optional<String> keyOf(int id) {
        return byId(id).map(BitCode::key);
    }

    /** Returns the code for {@code key} or throws {@link IllegalArgumentException} if it is unregistered. */
    public BitCode require(String key) {
        return byKey(key).orElseThrow(() -> new IllegalArgumentException("unregistered BIT code: " + key));
    }

    public Optional<BitCodeNamespace> namespace(String prefix) {
        return prefix == null ? Optional.empty() : Optional.ofNullable(namespaces.get(prefix));
    }

    public Optional<BitCodeNamespace> namespaceForId(int id) {
        return namespaces.values().stream().filter(ns -> ns.containsId(id)).findFirst();
    }

    /** All registered codes ordered by id. */
    public List<BitCode> codes() {
        List<BitCode> codes = new ArrayList<>(byId.values());
        codes.sort(Comparator.comparingInt(BitCode::id));
        return List.copyOf(codes);
    }

    /** All reserved namespaces ordered by first id. */
    public List<BitCodeNamespace> namespaces() {
        List<BitCodeNamespace> list = new ArrayList<>(namespaces.values());
        list.sort(Comparator.comparingInt(BitCodeNamespace::firstId));
        return List.copyOf(list);
    }

    /** Versioned JSON export of the catalog, for generated docs and LLM lookup of remediation text. */
    public String toCatalogJson() {
        StringBuilder json = new StringBuilder("{\"schema\":").append(BitReport.CURRENT_SCHEMA).append(",\"namespaces\":[");
        List<BitCodeNamespace> reserved = namespaces();
        for (int i = 0; i < reserved.size(); i++) {
            BitCodeNamespace ns = reserved.get(i);
            if (i > 0) json.append(',');
            json.append("{\"prefix\":\"").append(BitJson.escape(ns.prefix()))
                    .append("\",\"firstId\":").append(ns.firstId())
                    .append(",\"lastId\":").append(ns.lastId()).append('}');
        }
        json.append("],\"codes\":[");
        List<BitCode> codes = codes();
        for (int i = 0; i < codes.size(); i++) {
            BitCode code = codes.get(i);
            if (i > 0) json.append(',');
            json.append("{\"id\":").append(code.id())
                    .append(",\"key\":\"").append(BitJson.escape(code.key()))
                    .append("\",\"severity\":\"").append(code.defaultSeverity().name())
                    .append("\",\"tier\":\"").append(code.tier().name())
                    .append("\",\"subsystem\":\"").append(BitJson.escape(code.subsystem()))
                    .append("\",\"messageKey\":\"").append(BitJson.escape(code.messageKey()))
                    .append("\",\"remediation\":\"").append(BitJson.escape(code.remediation())).append("\"}");
        }
        return json.append("]}").toString();
    }
}
