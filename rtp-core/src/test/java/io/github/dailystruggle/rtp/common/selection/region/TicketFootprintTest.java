package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.AbstractVerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

@DisplayName("REQ-RTP-F-015: mountainside ticket footprint")
class TicketFootprintTest {
  private SelectionAPI previousSelection;

  @BeforeEach
  void setUp() {
    previousSelection = ActiveSelection.get();
    ActiveSelection.set(new SelectionAPI());
    TicketFootprint.resetForTesting();
  }

  @AfterEach
  void tearDown() {
    TicketFootprint.resetForTesting();
    ActiveSelection.set(previousSelection);
  }

  @Test
  @DisplayName("only enabled hill checks require a resident 3x3 footprint")
  void hillCheckControlsTicketDistance() {
    assertEquals(0, TicketFootprint.requiredDistance(List.of(-1.0d)));
    assertEquals(1, TicketFootprint.requiredDistance(List.of(-1.0d, 30.0d)));
    assertEquals(0, TicketFootprint.requiredDistance(List.of()));
  }

  @Test
  @DisplayName("runtime lookup uses permanent and temporary regions and caches the footprint")
  @SuppressWarnings({"rawtypes", "unchecked"})
  void runtimeLookupReadsBothRegionMapsAndCachesResult() {
    SelectionAPI selection = new SelectionAPI();
    SelectionAPI emptySelection = new SelectionAPI();

    Region permanentRegion = mock(Region.class);
    AbstractVerticalAdjustor<?> permanentVert = mock(AbstractVerticalAdjustor.class);
    doReturn(permanentVert).when(permanentRegion).getVert();
    doReturn(30.0d).when(permanentVert).maxHillAngle();
    selection.permRegionLookup.put("permanent", permanentRegion);

    Region temporaryRegion = mock(Region.class);
    AbstractVerticalAdjustor<?> temporaryVert = mock(AbstractVerticalAdjustor.class);
    doReturn(temporaryVert).when(temporaryRegion).getVert();
    doReturn(30.0d).when(temporaryVert).maxHillAngle();
    selection.tempRegions.put(UUID.randomUUID(), temporaryRegion);

    ActiveSelection.set(selection);
    assertEquals(1, TicketFootprint.ticketDistance());

    ActiveSelection.set(emptySelection);
    assertEquals(1, TicketFootprint.ticketDistance());
  }

  @Test
  @DisplayName("runtime lookup caches a zero footprint until the snapshot expires")
  @SuppressWarnings({"rawtypes", "unchecked"})
  void runtimeLookupCachesZeroFootprint() {
    SelectionAPI selection = new SelectionAPI();
    Region region = mock(Region.class);
    AbstractVerticalAdjustor<?> vert = mock(AbstractVerticalAdjustor.class);
    doReturn(vert).when(region).getVert();
    doReturn(30.0d).when(vert).maxHillAngle();
    ActiveSelection.set(selection);

    assertEquals(0, TicketFootprint.ticketDistance());
    selection.permRegionLookup.put("enabled", region);
    assertEquals(0, TicketFootprint.ticketDistance());
  }

  @Test
  @DisplayName("a missing selection requires no ticket footprint")
  void missingSelectionReturnsZero() {
    ActiveSelection.set(null);

    assertEquals(0, TicketFootprint.ticketDistance());
  }

  @Test
  @DisplayName("non-abstract vertical adjustors do not expand the ticket footprint")
  @SuppressWarnings({"rawtypes", "unchecked"})
  void nonAbstractVerticalAdjustorIsIgnored() {
    SelectionAPI selection = new SelectionAPI();
    Region region = mock(Region.class);
    VerticalAdjustor<?> vert = mock(VerticalAdjustor.class);
    doReturn(vert).when(region).getVert();
    selection.permRegionLookup.put("non-hill", region);
    ActiveSelection.set(selection);

    assertEquals(0, TicketFootprint.ticketDistance());
  }
}
