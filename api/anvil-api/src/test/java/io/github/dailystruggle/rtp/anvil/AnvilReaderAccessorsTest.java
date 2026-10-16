package io.github.dailystruggle.rtp.anvil;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnvilReaderAccessorsTest {

  @Test
  @DisplayName("getSections returns null for a null chunk root instead of throwing")
  void testGetSectionsNullRoot() {
    assertNull(AnvilReader.getSections(null));
  }

  @Test
  @DisplayName("getSections returns null when the chunk root has no sections list")
  void testGetSectionsAbsent() {
    assertNull(AnvilReader.getSections(new LinkedHashMap<>()));
  }
}
