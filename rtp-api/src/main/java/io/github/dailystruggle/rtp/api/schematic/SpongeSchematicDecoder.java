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
 * Dependency-free GZIP-NBT decoder for Sponge schematics (v2/v3) and Litematica ({@code .litematic})
 * schematics (ADR-058).
 * Extracts bounds, block-state palette, grid indices, and block entities.
 * Blocking I/O: must be invoked off the tick/region thread (S-005).
 *
 * @governedBy ADR-115
 * @governedBy ADR-058
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
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

      // Litematica (.litematic) format detection: top-level "Regions" compound.
      Map<String, Object> litematicRegions = asCompound(root.get("Regions"));
      if (litematicRegions != null && !litematicRegions.isEmpty()) {
        return decodeLitematicRegions(source, litematicRegions);
      }

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

  /**
   * Decodes a Litematica ({@code .litematic}) {@code Regions} compound (supporting negative region
   * {@code Size} axes, {@code BlockStatePalette} property maps, cross-long bit-packed
   * {@code BlockStates} {@code long[]}, and {@code TileEntities}) into a unified
   * {@link DecodedSchematic}.
   */
  private static DecodedSchematic decodeLitematicRegions(
      SchematicSource source, Map<String, Object> regions) throws IOException {
    final class RegionBox {
      final int minX;
      final int minY;
      final int minZ;
      final int width;
      final int height;
      final int length;
      final List<String> palette;
      final long[] blockStates;
      final List<Object> tileEntities;

      RegionBox(
          int minX,
          int minY,
          int minZ,
          int width,
          int height,
          int length,
          List<String> palette,
          long[] blockStates,
          List<Object> tileEntities) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.width = width;
        this.height = height;
        this.length = length;
        this.palette = palette;
        this.blockStates = blockStates;
        this.tileEntities = tileEntities;
      }
    }

    List<RegionBox> boxes = new ArrayList<>(regions.size());
    int globalMinX = Integer.MAX_VALUE;
    int globalMinY = Integer.MAX_VALUE;
    int globalMinZ = Integer.MAX_VALUE;
    int globalMaxX = Integer.MIN_VALUE;
    int globalMaxY = Integer.MIN_VALUE;
    int globalMaxZ = Integer.MIN_VALUE;

    for (Object regObj : regions.values()) {
      Map<String, Object> reg = asCompound(regObj);
      if (reg == null) {
        continue;
      }
      Map<String, Object> posMap = asCompound(reg.get("Position"));
      Map<String, Object> sizeMap = asCompound(reg.get("Size"));
      if (sizeMap == null) {
        throw new IOException("litematic region missing 'Size' compound");
      }
      int px = posMap != null ? intValue(posMap.get("x"), "Position.x") : 0;
      int py = posMap != null ? intValue(posMap.get("y"), "Position.y") : 0;
      int pz = posMap != null ? intValue(posMap.get("z"), "Position.z") : 0;
      int sx = intValue(sizeMap.get("x"), "Size.x");
      int sy = intValue(sizeMap.get("y"), "Size.y");
      int sz = intValue(sizeMap.get("z"), "Size.z");
      int rw = Math.abs(sx);
      int rh = Math.abs(sy);
      int rl = Math.abs(sz);
      if (rw == 0 || rh == 0 || rl == 0) {
        continue;
      }
      int minX = sx < 0 ? px + sx + 1 : px;
      int minY = sy < 0 ? py + sy + 1 : py;
      int minZ = sz < 0 ? pz + sz + 1 : pz;
      globalMinX = Math.min(globalMinX, minX);
      globalMinY = Math.min(globalMinY, minY);
      globalMinZ = Math.min(globalMinZ, minZ);
      globalMaxX = Math.max(globalMaxX, minX + rw);
      globalMaxY = Math.max(globalMaxY, minY + rh);
      globalMaxZ = Math.max(globalMaxZ, minZ + rl);

      List<Object> rawPalette = asList(reg.get("BlockStatePalette"));
      if (rawPalette == null || rawPalette.isEmpty()) {
        throw new IOException("litematic region has empty BlockStatePalette");
      }
      List<String> regPalette = new ArrayList<>(rawPalette.size());
      for (Object pObj : rawPalette) {
        regPalette.add(formatLitematicPaletteEntry(asCompound(pObj)));
      }
      Object rawStates = reg.get("BlockStates");
      if (!(rawStates instanceof long[] blockStates)) {
        throw new IOException("litematic region missing long[] BlockStates");
      }
      List<Object> tes = asList(reg.get("TileEntities"));
      boxes.add(new RegionBox(minX, minY, minZ, rw, rh, rl, regPalette, blockStates, tes));
    }

    if (boxes.isEmpty()) {
      throw new IOException("litematic file contains no non-empty regions");
    }

    int totalW = globalMaxX - globalMinX;
    int totalH = globalMaxY - globalMinY;
    int totalL = globalMaxZ - globalMinZ;
    int[] indices = new int[totalW * totalH * totalL];
    Map<String, Integer> unifiedPaletteMap = new LinkedHashMap<>();
    unifiedPaletteMap.put("minecraft:air", 0);
    List<BlockEntityData> blockEntities = new ArrayList<>();

    for (RegionBox box : boxes) {
      int[] palMap = new int[box.palette.size()];
      for (int i = 0; i < box.palette.size(); i++) {
        String stateStr = box.palette.get(i);
        palMap[i] = unifiedPaletteMap.computeIfAbsent(stateStr, k -> unifiedPaletteMap.size());
      }
      int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, box.palette.size() - 1)));
      long mask = (1L << bits) - 1L;
      long[] longs = box.blockStates;
      int ox = box.minX - globalMinX;
      int oy = box.minY - globalMinY;
      int oz = box.minZ - globalMinZ;

      for (int y = 0; y < box.height; y++) {
        for (int z = 0; z < box.length; z++) {
          for (int x = 0; x < box.width; x++) {
            int localIdx = (y * box.length + z) * box.width + x;
            long bitIdx = (long) localIdx * bits;
            int li = (int) (bitIdx >>> 6);
            int bo = (int) (bitIdx & 63L);
            int rawPalIdx;
            if (bo + bits <= 64) {
              rawPalIdx = (int) ((longs[li] >>> bo) & mask);
            } else {
              int low = 64 - bo;
              rawPalIdx = (int) (((longs[li] >>> bo) | (longs[li + 1] << low)) & mask);
            }
            if (rawPalIdx < 0 || rawPalIdx >= palMap.length) {
              rawPalIdx = 0;
            }
            int globalIdx = ((oy + y) * totalL + (oz + z)) * totalW + (ox + x);
            indices[globalIdx] = palMap[rawPalIdx];
          }
        }
      }

      if (box.tileEntities != null) {
        for (Object teObj : box.tileEntities) {
          Map<String, Object> te = asCompound(teObj);
          if (te == null) {
            continue;
          }
          int tx = te.get("x") instanceof Number n ? n.intValue() : 0;
          int ty = te.get("y") instanceof Number n ? n.intValue() : 0;
          int tz = te.get("z") instanceof Number n ? n.intValue() : 0;
          String id = (String) te.get("id");
          if (id == null) {
            id = (String) te.get("Id");
          }
          if (id == null) {
            id = "minecraft:unknown";
          }
          Map<String, Object> nbt = new LinkedHashMap<>(te);
          nbt.remove("x");
          nbt.remove("y");
          nbt.remove("z");
          nbt.remove("id");
          nbt.remove("Id");
          blockEntities.add(new BlockEntityData(ox + tx, oy + ty, oz + tz, id, nbt));
        }
      }
    }

    List<String> unifiedPalette = new ArrayList<>(unifiedPaletteMap.keySet());
    return new DecodedSchematic(
        source, totalW, totalH, totalL, unifiedPalette, indices, blockEntities, new int[] {0, 0, 0});
  }

  private static String formatLitematicPaletteEntry(Map<String, Object> entry) {
    if (entry == null) {
      return "minecraft:air";
    }
    Object nameObj = entry.get("Name");
    String name = (nameObj instanceof String s && !s.isEmpty()) ? s : "minecraft:air";
    Map<String, Object> props = asCompound(entry.get("Properties"));
    if (props == null || props.isEmpty()) {
      return name;
    }
    StringBuilder sb = new StringBuilder(name).append('[');
    boolean first = true;
    for (Map.Entry<String, Object> e : props.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append(e.getKey()).append('=').append(e.getValue());
    }
    sb.append(']');
    return sb.toString();
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
