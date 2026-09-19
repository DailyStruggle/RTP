package io.github.dailystruggle.rtp.anvil;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal NBT codec used by the Anvil read-only pre-filter (ADR-016).
 *
 * <p>Decoded model is intentionally plain-Java to keep the pre-filter free of
 * third-party NBT libraries:
 * <ul>
 *   <li>{@code TAG_Compound}   → {@link LinkedHashMap}{@code <String, Object>} (ordered for round-trip)</li>
 *   <li>{@code TAG_List}       → {@code List<Object>} carried through {@link NbtList}</li>
 *   <li>{@code TAG_String}     → {@link String}</li>
 *   <li>{@code TAG_Byte}       → {@link Byte}</li>
 *   <li>{@code TAG_Short}      → {@link Short}</li>
 *   <li>{@code TAG_Int}        → {@link Integer}</li>
 *   <li>{@code TAG_Long}       → {@link Long}</li>
 *   <li>{@code TAG_Float}      → {@link Float}</li>
 *   <li>{@code TAG_Double}     → {@link Double}</li>
 *   <li>{@code TAG_Byte_Array} → {@code byte[]}</li>
 *   <li>{@code TAG_Int_Array}  → {@code int[]}</li>
 *   <li>{@code TAG_Long_Array} → {@code long[]}</li>
 * </ul>
 *
 * <p>Strings use Java's Modified-UTF-8 encoding, which is bit-identical to Minecraft's
 * NBT string format for all code points observed in vanilla chunk data (ASCII identifiers).
 *
 * <p>This codec is read/write symmetric for every tag type it reads: round-tripping
 * {@code bytes → read → write → read} yields structurally-identical values, and - for
 * compound payloads without embedded {@code TAG_List} of mixed-empty shape - also yields
 * byte-identical output of the raw tag tree (compression is separate, see {@link AnvilReader}).
 */
public final class Nbt {

    public static final byte TAG_END        = 0;
    public static final byte TAG_BYTE       = 1;
    public static final byte TAG_SHORT      = 2;
    public static final byte TAG_INT        = 3;
    public static final byte TAG_LONG       = 4;
    public static final byte TAG_FLOAT      = 5;
    public static final byte TAG_DOUBLE     = 6;
    public static final byte TAG_BYTE_ARRAY = 7;
    public static final byte TAG_STRING     = 8;
    public static final byte TAG_LIST       = 9;
    public static final byte TAG_COMPOUND   = 10;
    public static final byte TAG_INT_ARRAY  = 11;
    public static final byte TAG_LONG_ARRAY = 12;

    private Nbt() {}

    /**
     * Typed wrapper for {@code TAG_List}. Carries the element tag id so that empty lists
     * (where the id is not derivable from contents) still round-trip correctly.
     */
    public static final class NbtList {
        public final byte elementType;
        public final List<Object> items;

        public NbtList(byte elementType, List<Object> items) {
            this.elementType = elementType;
            this.items = items;
        }
    }

    /** Named root pair: NBT files are a single named outer tag (usually an empty-named compound). */
    public static final class NamedTag {
        public final String name;
        public final Object value;

        public NamedTag(String name, Object value) {
            this.name = name;
            this.value = value;
        }
    }

    // ------------------------------------------------------------------------------------------ read

    public static NamedTag readNamedRoot(byte[] rawUncompressedNbt) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(rawUncompressedNbt))) {
            byte type = in.readByte();
            if (type == TAG_END) return new NamedTag("", null);
            String name = in.readUTF();
            Object value = readPayload(in, type);
            return new NamedTag(name, value);
        }
    }

    @SuppressWarnings("unchecked")
    public static LinkedHashMap<String, Object> readRootCompound(byte[] rawUncompressedNbt) throws IOException {
        NamedTag root = readNamedRoot(rawUncompressedNbt);
        if (!(root.value instanceof LinkedHashMap)) {
            throw new IOException("NBT root is not a TAG_Compound (got " + (root.value == null ? "null" : root.value.getClass().getSimpleName()) + ")");
        }
        return (LinkedHashMap<String, Object>) root.value;
    }

    private static Object readPayload(DataInput in, byte type) throws IOException {
        switch (type) {
            case TAG_END:        throw new IOException("Unexpected TAG_End in payload position");
            case TAG_BYTE:       return in.readByte();
            case TAG_SHORT:      return in.readShort();
            case TAG_INT:        return in.readInt();
            case TAG_LONG:       return in.readLong();
            case TAG_FLOAT:      return in.readFloat();
            case TAG_DOUBLE:     return in.readDouble();
            case TAG_BYTE_ARRAY: {
                int n = in.readInt();
                byte[] a = new byte[n];
                in.readFully(a);
                return a;
            }
            case TAG_STRING:     return in.readUTF();
            case TAG_LIST: {
                byte elemType = in.readByte();
                int n = in.readInt();
                List<Object> items = new ArrayList<>(Math.max(0, n));
                if (elemType == TAG_END) {
                    if (n > 0) throw new IOException("TAG_List declared TAG_End element type with nonzero length " + n);
                    return new NbtList(elemType, items);
                }
                for (int i = 0; i < n; i++) {
                    items.add(readPayload(in, elemType));
                }
                return new NbtList(elemType, items);
            }
            case TAG_COMPOUND: {
                LinkedHashMap<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    byte childType = in.readByte();
                    if (childType == TAG_END) return map;
                    String childName = in.readUTF();
                    map.put(childName, readPayload(in, childType));
                }
            }
            case TAG_INT_ARRAY: {
                int n = in.readInt();
                int[] a = new int[n];
                for (int i = 0; i < n; i++) a[i] = in.readInt();
                return a;
            }
            case TAG_LONG_ARRAY: {
                int n = in.readInt();
                long[] a = new long[n];
                for (int i = 0; i < n; i++) a[i] = in.readLong();
                return a;
            }
            default:
                throw new IOException("Unknown NBT tag type: " + (type & 0xFF));
        }
    }

    // ------------------------------------------------------------------------------------- skip

    /**
     * Consumes the payload for a tag of {@code type} without materialising it.
     *
     * <p>Bytes are read from {@code in} exactly as {@link #readPayload} would have read
     * them, but no Java objects are allocated for the payload contents. This lets a
     * selective parser skip uninteresting root/section subtrees (e.g. {@code block_entities},
     * {@code Heightmaps} when only biomes are needed) while keeping the byte stream aligned
     * for subsequent tags.
     *
     * <p>Recursive payloads ({@code TAG_COMPOUND}, {@code TAG_LIST} of compounds/lists)
     * are skipped recursively. Non-recursive payloads advance the stream by their fixed
     * or length-prefixed width.
     *
     * @throws IOException on malformed input or an unknown tag id (mirrors {@link #readPayload})
     */
    public static void skipPayload(DataInput in, byte type) throws IOException {
        switch (type) {
            case TAG_END:
                throw new IOException("Unexpected TAG_End in payload position");
            case TAG_BYTE:       in.readByte();                                 return;
            case TAG_SHORT:      in.readShort();                                return;
            case TAG_INT:        in.readInt();                                  return;
            case TAG_LONG:       in.readLong();                                 return;
            case TAG_FLOAT:      in.readFloat();                                return;
            case TAG_DOUBLE:     in.readDouble();                               return;
            case TAG_BYTE_ARRAY: {
                int n = in.readInt();
                if (n < 0) throw new IOException("Negative TAG_Byte_Array length " + n);
                skipBytes(in, n);
                return;
            }
            case TAG_STRING:     in.readUTF();                                  return;
            case TAG_LIST: {
                byte elemType = in.readByte();
                int n = in.readInt();
                if (elemType == TAG_END) {
                    if (n > 0) throw new IOException("TAG_List declared TAG_End element type with nonzero length " + n);
                    return;
                }
                int fixed = fixedPayloadSize(elemType);
                if (fixed > 0) {
                    // All fixed-width numeric elements - bulk skip.
                    long total = (long) fixed * (long) n;
                    skipBytes(in, total);
                    return;
                }
                for (int i = 0; i < n; i++) {
                    skipPayload(in, elemType);
                }
                return;
            }
            case TAG_COMPOUND: {
                while (true) {
                    byte childType = in.readByte();
                    if (childType == TAG_END) return;
                    in.readUTF(); // child name, discarded
                    skipPayload(in, childType);
                }
            }
            case TAG_INT_ARRAY: {
                int n = in.readInt();
                if (n < 0) throw new IOException("Negative TAG_Int_Array length " + n);
                skipBytes(in, (long) n * 4L);
                return;
            }
            case TAG_LONG_ARRAY: {
                int n = in.readInt();
                if (n < 0) throw new IOException("Negative TAG_Long_Array length " + n);
                skipBytes(in, (long) n * 8L);
                return;
            }
            default:
                throw new IOException("Unknown NBT tag type: " + (type & 0xFF));
        }
    }

    /** Returns the fixed byte width of {@code type}, or {@code -1} if the width is variable. */
    private static int fixedPayloadSize(byte type) {
        switch (type) {
            case TAG_BYTE:   return 1;
            case TAG_SHORT:  return 2;
            case TAG_INT:    return 4;
            case TAG_LONG:   return 8;
            case TAG_FLOAT:  return 4;
            case TAG_DOUBLE: return 8;
            default:         return -1;
        }
    }

    private static void skipBytes(DataInput in, long n) throws IOException {
        // DataInput#skipBytes is not guaranteed to skip the full count; loop.
        long remaining = n;
        while (remaining > 0) {
            int want = (int) Math.min(remaining, (long) Integer.MAX_VALUE);
            int skipped = in.skipBytes(want);
            if (skipped <= 0) {
                // Fall back to a byte read to force progress / surface EOF.
                in.readByte();
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    // ---------------------------------------------------------------------------- selective read

    /**
     * Predicate for selective compound parsing.
     *
     * <p>Called for every named child of a compound being parsed selectively. Receives
     * the parent-path (list of ancestor child names, root-first) and the current child's
     * name + tag type, and returns whether the child should be fully materialised
     * ({@code KEEP}), descended into as another selective compound ({@code RECURSE}),
     * or skipped without allocation ({@code SKIP}).
     *
     * <p>{@code RECURSE} is only meaningful for {@code TAG_COMPOUND} children. For
     * {@code TAG_LIST} elements, the filter is consulted once per element using the
     * synthetic child name {@code "[]"} - returning {@code RECURSE} there descends into
     * each element compound individually (useful e.g. for {@code sections[*]}).
     */
    @FunctionalInterface
    public interface SelectiveFilter {
        enum Decision { KEEP, RECURSE, SKIP }
        Decision decide(List<String> path, String childName, byte childType);
    }

    /**
     * Reads the named-root compound from {@code rawUncompressedNbt} into a
     * {@link LinkedHashMap}, but invokes {@code filter} for every child to decide whether
     * to fully read, selectively recurse, or skip the payload. Uninteresting subtrees are
     * never materialised, producing a significantly smaller decoded map when the caller
     * only needs a subset of the tree.
     *
     * <p>Returns the (possibly sparse) root compound. Children that were skipped are
     * absent from the map; children that were kept/recursed are present with their decoded
     * values (recursed compounds are themselves selectively-filtered).
     *
     * <p>{@code TAG_LIST} elements are always materialised in order, but each element is
     * subject to {@code filter} under the synthetic name {@code "[]"}: {@code SKIP} drops
     * the element entirely (the list is shortened), {@code RECURSE} descends into
     * compound elements selectively, {@code KEEP} materialises the element fully. Mixed
     * decisions within a single list are allowed.
     */
    public static LinkedHashMap<String, Object> readRootCompoundSelective(
            byte[] rawUncompressedNbt, SelectiveFilter filter) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(rawUncompressedNbt))) {
            byte type = in.readByte();
            if (type == TAG_END) return new LinkedHashMap<>();
            if (type != TAG_COMPOUND) {
                throw new IOException("NBT root is not a TAG_Compound (got type " + (type & 0xFF) + ")");
            }
            in.readUTF(); // root name, discarded
            ArrayList<String> path = new ArrayList<>();
            return readCompoundSelective(in, path, filter);
        }
    }

    private static LinkedHashMap<String, Object> readCompoundSelective(
            DataInput in, ArrayList<String> path, SelectiveFilter filter) throws IOException {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        while (true) {
            byte childType = in.readByte();
            if (childType == TAG_END) return map;
            String childName = in.readUTF();
            SelectiveFilter.Decision d = filter.decide(path, childName, childType);
            switch (d) {
                case SKIP:
                    skipPayload(in, childType);
                    break;
                case RECURSE:
                    if (childType == TAG_COMPOUND) {
                        path.add(childName);
                        try {
                            map.put(childName, readCompoundSelective(in, path, filter));
                        } finally {
                            path.remove(path.size() - 1);
                        }
                    } else if (childType == TAG_LIST) {
                        path.add(childName);
                        try {
                            map.put(childName, readListSelective(in, path, filter));
                        } finally {
                            path.remove(path.size() - 1);
                        }
                    } else {
                        // RECURSE only meaningful for compound/list; fall back to KEEP.
                        map.put(childName, readPayload(in, childType));
                    }
                    break;
                case KEEP:
                default:
                    map.put(childName, readPayload(in, childType));
                    break;
            }
        }
    }

    private static NbtList readListSelective(
            DataInput in, ArrayList<String> path, SelectiveFilter filter) throws IOException {
        byte elemType = in.readByte();
        int n = in.readInt();
        List<Object> items = new ArrayList<>(Math.max(0, n));
        if (elemType == TAG_END) {
            if (n > 0) throw new IOException("TAG_List declared TAG_End element type with nonzero length " + n);
            return new NbtList(elemType, items);
        }
        for (int i = 0; i < n; i++) {
            SelectiveFilter.Decision d = filter.decide(path, "[]", elemType);
            switch (d) {
                case SKIP:
                    skipPayload(in, elemType);
                    break;
                case RECURSE:
                    if (elemType == TAG_COMPOUND) {
                        path.add("[]");
                        try {
                            items.add(readCompoundSelective(in, path, filter));
                        } finally {
                            path.remove(path.size() - 1);
                        }
                    } else if (elemType == TAG_LIST) {
                        path.add("[]");
                        try {
                            items.add(readListSelective(in, path, filter));
                        } finally {
                            path.remove(path.size() - 1);
                        }
                    } else {
                        items.add(readPayload(in, elemType));
                    }
                    break;
                case KEEP:
                default:
                    items.add(readPayload(in, elemType));
                    break;
            }
        }
        return new NbtList(elemType, items);
    }

    // ----------------------------------------------------------------------------------------- write

    public static byte[] writeNamedRoot(String name, Object value) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        try (DataOutputStream out = new DataOutputStream(baos)) {
            byte type = inferType(value);
            out.writeByte(type);
            out.writeUTF(name);
            writePayload(out, type, value);
        }
        return baos.toByteArray();
    }

    private static void writePayload(DataOutput out, byte type, Object value) throws IOException {
        switch (type) {
            case TAG_BYTE:   out.writeByte(((Number) value).byteValue());   break;
            case TAG_SHORT:  out.writeShort(((Number) value).shortValue()); break;
            case TAG_INT:    out.writeInt(((Number) value).intValue());     break;
            case TAG_LONG:   out.writeLong(((Number) value).longValue());   break;
            case TAG_FLOAT:  out.writeFloat(((Number) value).floatValue()); break;
            case TAG_DOUBLE: out.writeDouble(((Number) value).doubleValue()); break;
            case TAG_BYTE_ARRAY: {
                byte[] a = (byte[]) value;
                out.writeInt(a.length);
                out.write(a);
                break;
            }
            case TAG_STRING: out.writeUTF((String) value); break;
            case TAG_LIST: {
                NbtList list = (NbtList) value;
                out.writeByte(list.elementType);
                out.writeInt(list.items.size());
                for (Object item : list.items) {
                    writePayload(out, list.elementType, item);
                }
                break;
            }
            case TAG_COMPOUND: {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) value;
                for (Map.Entry<String, Object> e : map.entrySet()) {
                    byte childType = inferType(e.getValue());
                    out.writeByte(childType);
                    out.writeUTF(e.getKey());
                    writePayload(out, childType, e.getValue());
                }
                out.writeByte(TAG_END);
                break;
            }
            case TAG_INT_ARRAY: {
                int[] a = (int[]) value;
                out.writeInt(a.length);
                for (int v : a) out.writeInt(v);
                break;
            }
            case TAG_LONG_ARRAY: {
                long[] a = (long[]) value;
                out.writeInt(a.length);
                for (long v : a) out.writeLong(v);
                break;
            }
            default:
                throw new IOException("Cannot write unknown NBT tag type: " + (type & 0xFF));
        }
    }

    /** Deduces the tag id from the Java runtime type of {@code value}. */
    public static byte inferType(Object value) throws IOException {
        if (value instanceof Byte)       return TAG_BYTE;
        if (value instanceof Short)      return TAG_SHORT;
        if (value instanceof Integer)    return TAG_INT;
        if (value instanceof Long)       return TAG_LONG;
        if (value instanceof Float)      return TAG_FLOAT;
        if (value instanceof Double)     return TAG_DOUBLE;
        if (value instanceof byte[])     return TAG_BYTE_ARRAY;
        if (value instanceof String)     return TAG_STRING;
        if (value instanceof NbtList)    return TAG_LIST;
        if (value instanceof Map)        return TAG_COMPOUND;
        if (value instanceof int[])      return TAG_INT_ARRAY;
        if (value instanceof long[])     return TAG_LONG_ARRAY;
        throw new IOException("Cannot infer NBT tag type from Java class " + (value == null ? "null" : value.getClass().getName()));
    }
}
