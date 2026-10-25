package io.github.dailystruggle.rtp.api.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BlockStateStringTest {

  @Test
  @DisplayName("split and parse validation, whitespace handling, and error branches")
  void testSplitAndParseErrors() {
    assertThrows(NullPointerException.class, () -> BlockStateString.split(null));
    assertThrows(IllegalArgumentException.class, () -> BlockStateString.split(""));
    assertThrows(IllegalArgumentException.class, () -> BlockStateString.split("   "));

    assertThrows(IllegalArgumentException.class, () -> BlockStateString.split("[key=value]")); // no head
    assertThrows(IllegalArgumentException.class, () -> BlockStateString.split("stone[")); // unclosed
    assertThrows(IllegalArgumentException.class, () -> BlockStateString.split("stone]")); // misplaced close
    assertThrows(IllegalArgumentException.class, () -> BlockStateString.split("stone[a=b]extra")); // chars after close

    assertThrows(IllegalArgumentException.class, () -> BlockStateString.parse("stone[]")); // empty body
    assertThrows(IllegalArgumentException.class, () -> BlockStateString.parse("stone[=val]")); // no key
    assertThrows(IllegalArgumentException.class, () -> BlockStateString.parse("stone[key=]")); // no val
    assertThrows(IllegalArgumentException.class, () -> BlockStateString.parse("stone[key]")); // no equals
    assertThrows(IllegalArgumentException.class, () -> BlockStateString.parse("stone[ = ]")); // whitespace key/val
  }

  @Test
  @DisplayName("valid parsing, properties, isAir detection, equals and hashCode")
  void testValidBlockStateString() {
    BlockStateString simple = BlockStateString.parse("stone");
    assertEquals("stone", simple.head());
    assertEquals("minecraft", simple.namespace());
    assertEquals("stone", simple.path());
    assertEquals("minecraft:stone", simple.canonical());
    assertEquals("minecraft:stone", simple.toString());
    assertEquals("stone", simple.lowerCaseHead());
    assertTrue(simple.properties().isEmpty());
    assertFalse(simple.isAir());

    // isAir variants
    assertTrue(BlockStateString.parse("air").isAir());
    assertTrue(BlockStateString.parse("minecraft:cave_air").isAir());
    assertTrue(BlockStateString.parse("minecraft:void_air").isAir());
    assertFalse(BlockStateString.parse("custom:air").isAir());

    // With properties
    BlockStateString slab = BlockStateString.parse("minecraft:oak_slab[type=top,waterlogged=false]");
    assertEquals("minecraft:oak_slab", slab.head());
    assertEquals("minecraft", slab.namespace());
    assertEquals("oak_slab", slab.path());
    assertEquals(2, slab.properties().size());
    assertEquals("top", slab.properties().get("type"));
    assertEquals("false", slab.properties().get("waterlogged"));
    assertEquals("minecraft:oak_slab[type=top,waterlogged=false]", slab.canonical());

    // equals and hashCode
    BlockStateString sameSlab = BlockStateString.parse("minecraft:oak_slab[type=top,waterlogged=false]");
    assertEquals(slab, sameSlab);
    assertEquals(slab.hashCode(), sameSlab.hashCode());

    BlockStateString diffSlab = BlockStateString.parse("minecraft:oak_slab[type=bottom,waterlogged=false]");
    assertFalse(slab.equals(diffSlab));
    assertFalse(slab.equals(null));
    assertFalse(slab.equals("string"));

    // Split object methods
    BlockStateString.Split splitSimple = BlockStateString.split("stone");
    assertEquals("stone", splitSimple.head());
    assertNull(splitSimple.body());

    BlockStateString.Split splitProps = BlockStateString.split("stone[a=b]");
    assertEquals("stone", splitProps.head());
    assertEquals("a=b", splitProps.body());
  }
}
