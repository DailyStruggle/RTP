package io.github.dailystruggle.rtp.api.schematic;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Dependency-free decoder for the Sponge schematic format (v2 and v3), the modern
 * WorldEdit / FAWE {@code .schem} container. A Sponge schematic is GZIP-compressed,
 * big-endian NBT; this reader needs no WorldEdit, no Minecraft types, and no live world,
 * so it is platform-neutral and shared by every adapter (ADR-058 Amendment 1).
 *
 * <p>The output {@link DecodedSchematic} exposes the bounding box (so {@code rtp-core} can
 * run the S-003 footprint claim check before any block write), the block-state palette,
 * a dense palette-index grid, and the decoded block entities. Block-state strings are
 * handed verbatim to each platform's native parser at paste time.
 *
 * <p>This class performs blocking file I/O and MUST be invoked off any tick / region
 * thread (S-005); {@link AbstractFileSchematicPaster} wraps it accordingly.
 */
public final class SpongeSchematicDecoder {

  private SpongeSchematicDecoder() {
  }

  /**
   * Decodes the file named by {@code source}.
   *
   * @param source the resolved source; never {@code null}
   * @return the decoded schematic
   * @throws IOException when the file is missing, not GZIP-NBT, or not a recognizable
   *     Sponge schematic
   */
  public static DecodedSchematic decode(SchematicSource source) throws IOException {
    try (InputStream in = Files.newInputStream(source.path())) {
      return decode(in, source);
    }
  }

  /**
   * Decodes a Sponge schematic from a raw (GZIP-compressed) stream.
   *
   * @param rawIn  the GZIP-NBT stream; never {@code null}
   * @param source the descriptor to attach to the result; never {@code null}
   * @return the decoded schematic
   * @throws IOException on malformed input
   */
  public static DecodedSchematic decode(InputStream rawIn, SchematicSource source)
      throws IOException {
    try (DataInputStream dis =
             new DataInputStream(new BufferedInputStream(new GZIPInputStream(rawIn)))) {
      int rootTag = dis.readUnsignedByte();
      if (rootTag != 10) {
        throw new IOException("root NBT tag is not a compound (got " + rootTag + ")");
      }
      readName(dis); // root name (unnamed in v3, "Schematic" in some v2 writers)
      Map<String, Object> root = readCompound(dis);

      // v3 nests everything under a "Schematic" compound; v2 keeps it at the root.
      Map<String, Object> schem = asCompound(root.get("Schematic"));
      if (schem == null) {
        schem = root;
      }

      int width = intValue(schem.get("Width"), "Width");
      int height = intValue(schem.get("Height"), "Height");
      int length = intValue(schem.get("Length"), "Length");
      int[] offset = intArray(schem.get("Offset"));

      Map<String, Object> blocks = asCompound(schem.get("Blocks"));
      Map<String, Object> paletteTag;
      byte[] data;
      List<Object> rawBlockEntities;
      if (blocks != null) {
        // Sponge v3.
        paletteTag = asCompound(blocks.get("Palette"));
        data = byteArray(blocks.get("Data"), "Blocks.Data");
        rawBlockEntities = asList(blocks.get("BlockEntities"));
      } else {
        // Sponge v2.
        paletteTag = asCompound(schem.get("Palette"));
        data = byteArray(schem.get("BlockData"), "BlockData");
        rawBlockEntities = asList(schem.get("BlockEntities"));
      }
      if (paletteTag == null) {
        throw new IOException("schematic has no block Palette");
      }

      // Palette is name -> index; invert into an index-ordered array.
      int max = -1;
      for (Object v : paletteTag.values()) {
        max = Math.max(max, ((Number) v).intValue());
      }
      String[] palette = new String[max + 1];
      for (Map.Entry<String, Object> e : paletteTag.entrySet()) {
        palette[((Number) e.getValue()).intValue()] = e.getKey();
      }

      int[] indices = decodeVarints(data, width * height * length);

      List<BlockEntityData> blockEntities = new ArrayList<>();
      if (rawBlockEntities != null) {
        for (Object o : rawBlockEntities) {
          Map<String, Object> be = asCompound(o);
          if (be == null) {
            continue;
          }
          int[] pos = intArray(be.get("Pos"));
          String id = (String) be.get("Id");
          if (id == null) {
            id = (String) be.get("id");
          }
          if (id == null) {
            id = "minecraft:unknown";
          }
          Map<String, Object> nbt = asCompound(be.get("Data"));
          if (nbt == null) {
            // v2 inlines payload alongside Pos/Id.
            nbt = new LinkedHashMap<>(be);
            nbt.remove("Pos");
            nbt.remove("Id");
            nbt.remove("id");
          }
          blockEntities.add(new BlockEntityData(pos[0], pos[1], pos[2], id, nbt));
        }
      }

      return new DecodedSchematic(source, width, height, length,
          List.of(palette), indices, blockEntities, offset);
    }
  }

  /** Decodes the LEB128 varint-packed palette-index stream. */
  private static int[] decodeVarints(byte[] data, int expected) throws IOException {
    int[] out = new int[expected];
    int count = 0;
    int i = 0;
    while (i < data.length) {
      int value = 0;
      int shift = 0;
      while (true) {
        if (i >= data.length) {
          throw new IOException("truncated varint in block Data");
        }
        byte b = data[i++];
        value |= (b & 0x7F) << shift;
        if ((b & 0x80) == 0) {
          break;
        }
        shift += 7;
      }
      if (count >= expected) {
        throw new IOException("block Data has more cells than width*height*length");
      }
      out[count++] = value;
    }
    if (count != expected) {
      throw new IOException("block Data cell count " + count + " != width*height*length "
          + expected);
    }
    return out;
  }

  // ------------------------------------------------------------------
  // Minimal big-endian NBT reader.
  // ------------------------------------------------------------------

  private static String readName(DataInputStream in) throws IOException {
    int len = in.readUnsignedShort();
    byte[] b = new byte[len];
    in.readFully(b);
    return new String(b, StandardCharsets.UTF_8);
  }

  private static Map<String, Object> readCompound(DataInputStream in) throws IOException {
    Map<String, Object> c = new LinkedHashMap<>();
    while (true) {
      int tag = in.readUnsignedByte();
      if (tag == 0) {
        break;
      }
      String name = readName(in);
      c.put(name, readPayload(in, tag));
    }
    return c;
  }

  private static Object readPayload(DataInputStream in, int tag) throws IOException {
    switch (tag) {
      case 1:
        return (int) in.readByte();
      case 2:
        return (int) in.readShort();
      case 3:
        return in.readInt();
      case 4:
        return in.readLong();
      case 5:
        return in.readFloat();
      case 6:
        return in.readDouble();
      case 7: {
        int len = in.readInt();
        byte[] b = new byte[len];
        in.readFully(b);
        return b;
      }
      case 8:
        return readName(in);
      case 9: {
        int elemTag = in.readUnsignedByte();
        int len = in.readInt();
        List<Object> list = new ArrayList<>(Math.max(0, len));
        for (int i = 0; i < len; i++) {
          list.add(readPayload(in, elemTag));
        }
        return list;
      }
      case 10:
        return readCompound(in);
      case 11: {
        int len = in.readInt();
        int[] arr = new int[len];
        for (int i = 0; i < len; i++) {
          arr[i] = in.readInt();
        }
        return arr;
      }
      case 12: {
        int len = in.readInt();
        long[] arr = new long[len];
        for (int i = 0; i < len; i++) {
          arr[i] = in.readLong();
        }
        return arr;
      }
      default:
        throw new IOException("unsupported NBT tag " + tag);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asCompound(Object o) {
    return (o instanceof Map) ? (Map<String, Object>) o : null;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> asList(Object o) {
    return (o instanceof List) ? (List<Object>) o : null;
  }

  private static int intValue(Object o, String field) throws IOException {
    if (!(o instanceof Number)) {
      throw new IOException("missing or non-numeric '" + field + "'");
    }
    return ((Number) o).intValue();
  }

  private static byte[] byteArray(Object o, String field) throws IOException {
    if (!(o instanceof byte[])) {
      throw new IOException("missing or non-byte-array '" + field + "'");
    }
    return (byte[]) o;
  }

  private static int[] intArray(Object o) {
    if (o instanceof int[] && ((int[]) o).length == 3) {
      return (int[]) o;
    }
    return new int[] {0, 0, 0};
  }
}
