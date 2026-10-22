package io.github.dailystruggle.rtp.common.selection.region;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("REQ-RTP-F-015: mountainside ticket footprint")
class TicketFootprintTest {
  @Test
  @DisplayName("only enabled hill checks require a resident 3x3 footprint")
  void hillCheckControlsTicketDistance() {
    assertEquals(0, TicketFootprint.requiredDistance(List.of(-1.0d)));
    assertEquals(1, TicketFootprint.requiredDistance(List.of(-1.0d, 30.0d)));
    assertEquals(0, TicketFootprint.requiredDistance(List.of()));
  }
}
