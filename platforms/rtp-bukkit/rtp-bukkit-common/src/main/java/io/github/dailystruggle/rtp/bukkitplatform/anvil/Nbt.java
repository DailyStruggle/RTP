package io.github.dailystruggle.rtp.bukkitplatform.anvil;

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
