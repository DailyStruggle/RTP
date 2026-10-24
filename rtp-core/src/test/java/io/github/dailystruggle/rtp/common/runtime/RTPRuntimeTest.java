package io.github.dailystruggle.rtp.common.runtime;

import io.github.dailystruggle.rtp.api.economy.RTPEconomy;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * ADR-115 seam 4: the Tier 1 runtime holder keeps the bootstrap logging contract formerly in
 * {@code RTP.log} - JUL fallback before an accessor exists, gated sub-INFO buffering with a hard
 * cap, and in-order replay on flush.
 */
@DisplayName("ADR-115 RTPRuntime holder: slots, JUL fallback, gated log buffer")
class RTPRuntimeTest {
  private RTPServerAccessor savedAccessor;
  private RTPScheduler savedScheduler;
  private RTPEconomy savedEconomy;
  private boolean savedBuffered;
  private BooleanSupplier savedGate;

  @BeforeEach
  void save() {
    savedAccessor = RTPRuntime.serverAccessor();
    savedScheduler = RTPRuntime.scheduler();
    savedEconomy = RTPRuntime.economy();
    savedBuffered = RTPRuntime.logsBufferedForTests();
    savedGate = RTPRuntime.logBufferGateForTests();
    RTPRuntime.resetLogBufferForTests();
  }

  @AfterEach
  void restore() {
    RTPRuntime.resetLogBufferForTests();
    RTPRuntime.setLogsBufferedForTests(savedBuffered);
    RTPRuntime.setServerAccessor(savedAccessor);
    RTPRuntime.setScheduler(savedScheduler);
    RTPRuntime.setEconomy(savedEconomy);
    RTPRuntime.setLogBufferGate(savedGate);
  }

  @Test
  @DisplayName("slots start from what was installed and context() reads them live")
  void slotsAndContext() {
    RTPServerAccessor accessor = mock(RTPServerAccessor.class);
    RTPScheduler scheduler = mock(RTPScheduler.class);
    RTPEconomy economy = mock(RTPEconomy.class);
    RTPRuntime.setServerAccessor(accessor);
    RTPRuntime.setScheduler(scheduler);
    RTPRuntime.setEconomy(economy);

    RTPContext ctx = RTPRuntime.context();
    assertSame(accessor, ctx.serverAccessor());
    assertSame(scheduler, ctx.scheduler());
    assertSame(economy, ctx.economy());

    RTPRuntime.setServerAccessor(null);
    assertEquals(null, ctx.serverAccessor(), "context is a live view, not a snapshot");
  }

  @Test
  @DisplayName("with no accessor, INFO and above go to the JUL 'RTP' logger without NPE")
  void julFallbackBeforeAccessor() {
    RTPRuntime.setServerAccessor(null);
    RTPRuntime.setLogBufferGate(() -> true);
    List<LogRecord> records = new ArrayList<>();
    Handler handler = capture(records);
    Logger jul = Logger.getLogger("RTP");
    jul.addHandler(handler);
    try {
      RTPRuntime.log(Level.WARNING, "pre-enable warning");
      RecordingThrowable t = new RecordingThrowable();
      RTPRuntime.log(Level.SEVERE, "pre-enable severe", t);
    } finally {
      jul.removeHandler(handler);
    }
    assertEquals(2, records.size());
    assertEquals("pre-enable warning", records.get(0).getMessage());
    assertEquals(Level.SEVERE, records.get(1).getLevel());
    assertEquals(RecordingThrowable.class, records.get(1).getThrown().getClass());
  }

  @Test
  @DisplayName("gate closed: FINE is buffered, not emitted; INFO bypasses the buffer")
  void closedGateBuffersSubInfo() {
    RTPServerAccessor accessor = mock(RTPServerAccessor.class);
    RTPRuntime.setServerAccessor(accessor);
    RTPRuntime.setLogBufferGate(() -> true);

    RTPRuntime.log(Level.FINE, "buffered");
    RTPRuntime.log(Level.INFO, "direct");

    verify(accessor, never()).log(Level.FINE, "buffered");
    verify(accessor).log(Level.INFO, "direct");
    assertEquals(1, RTPRuntime.pendingLogCountForTests());
  }

  @Test
  @DisplayName("gate open: FINE goes straight to the accessor")
  void openGateEmitsSubInfo() {
    RTPServerAccessor accessor = mock(RTPServerAccessor.class);
    RTPRuntime.setServerAccessor(accessor);
    RTPRuntime.setLogBufferGate(() -> false);

    RTPRuntime.log(Level.FINE, "direct-fine");

    verify(accessor).log(Level.FINE, "direct-fine");
    assertEquals(0, RTPRuntime.pendingLogCountForTests());
  }

  @Test
  @DisplayName("buffer is capped at PENDING_LOGS_CAP; overflow is dropped")
  void bufferCap() {
    RTPServerAccessor accessor = mock(RTPServerAccessor.class);
    RTPRuntime.setServerAccessor(accessor);
    RTPRuntime.setLogBufferGate(() -> true);

    for (int i = 0; i < RTPRuntime.PENDING_LOGS_CAP + 10; i++) {
      RTPRuntime.log(Level.FINER, "m" + i);
    }

    assertEquals(4096, RTPRuntime.PENDING_LOGS_CAP);
    assertEquals(RTPRuntime.PENDING_LOGS_CAP, RTPRuntime.pendingLogCountForTests());
    verifyNoInteractions(accessor);
  }

  @Test
  @DisplayName("flushPendingLogs replays to the accessor in emission order and opens the buffer")
  void flushReplaysInOrder() {
    RTPServerAccessor accessor = mock(RTPServerAccessor.class);
    RTPRuntime.setServerAccessor(accessor);
    RTPRuntime.setLogBufferGate(() -> true);
    RecordingThrowable t = new RecordingThrowable();

    RTPRuntime.log(Level.FINE, "first");
    RTPRuntime.log(Level.FINER, "second", t);
    RTPRuntime.log(Level.FINEST, "third");
    verifyNoInteractions(accessor);

    RTPRuntime.flushPendingLogs();

    InOrder order = inOrder(accessor);
    order.verify(accessor).log(Level.FINE, "first");
    order.verify(accessor).log(Level.FINER, "second", t);
    order.verify(accessor).log(Level.FINEST, "third");
    assertEquals(0, RTPRuntime.pendingLogCountForTests());

    // After the flush the gate no longer applies, even if it still reports closed.
    RTPRuntime.log(Level.FINE, "after");
    verify(accessor).log(Level.FINE, "after");
  }

  private static Handler capture(List<LogRecord> sink) {
    return new Handler() {
      @Override
      public void publish(LogRecord record) {
        sink.add(record);
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };
  }

  private static final class RecordingThrowable extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
