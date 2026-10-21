package io.github.dailystruggle.rtp.common.factory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR-115 invariant 3: moving the factory keys out of {@code RTP} keeps their names and order.
 * Registration during {@code RTP} static init is covered by {@code RTPStaticInitOrderTest}.
 */
@DisplayName("ADR-115 invariant 3: FactoryNames keeps RTP's factory keys")
class FactoryNamesTest {

  @Test
  @DisplayName("FactoryNames keeps the former RTP.factoryNames constants in declaration order")
  void keyOrderUnchanged() {
    assertEquals(List.of("shape", "vert", "singleConfig", "multiConfig"),
        Arrays.stream(FactoryNames.values()).map(Enum::name).toList());
  }
}
