package io.github.dailystruggle.rtp.api.schematic;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.server.api.ServerAccessor;
import io.github.dailystruggle.server.api.ServerAccessors;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the decode cache added to {@link AbstractFileSchematicPaster} (ADR-058): a schematic
 * file is decoded on its first {@link AbstractFileSchematicPaster#load load} and served from
 * memory afterwards, and {@link AbstractFileSchematicPaster#clearCache()} forces a re-read.
 */
class AbstractFileSchematicPasterCacheTest {

  /** Minimal concrete paster: it never pastes, so we only exercise the cached {@code load}. */
  private static final class NoopPaster extends AbstractFileSchematicPaster {
    @Override
    public PasteResult paste(LoadedSchematic schematic, RTPLocation at, PasteOptions options) {
      return PasteResult.PASTE_ERROR;
    }
  }

  private static Path writeFixture() throws Exception {
    Path file = Files.createTempFile("rtp-cache-test", ".schem");
    try (InputStream in = AbstractFileSchematicPasterCacheTest.class
        .getResourceAsStream("/schematics/skyblock_island.schem")) {
      assertNotNull(in, "fixture must be on the test classpath");
      Files.copy(in, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    file.toFile().deleteOnExit();
    return file;
  }

  @Test
  void decodesOnceThenServesFromCache() throws Exception {
    AbstractFileSchematicPaster.clearCache();
    Path file = writeFixture();
    SchematicSource source = new SchematicSource("skyblock_island", file, "schem");
    NoopPaster paster = new NoopPaster();

    LoadedSchematic first = load(paster, source);
    LoadedSchematic second = load(paster, source);
    assertNotNull(first, "first load decodes the file");
    assertSame(first, second, "second load is served from cache (no re-decode)");

    // A different paster instance shares the same static cache keyed by absolute path.
    assertSame(first, load(new NoopPaster(), source), "cache is shared across paster instances");

    AbstractFileSchematicPaster.clearCache();
    LoadedSchematic afterClear = load(paster, source);
    assertNotNull(afterClear, "file is re-read after clearCache");
    assertNotSame(first, afterClear, "clearCache forces a fresh decode");
  }

  @Test
  void testSupportsAndLoadEdgeCases() throws Exception {
    NoopPaster paster = new NoopPaster();

    // supports
    org.junit.jupiter.api.Assertions.assertFalse(paster.supports(null));
    Path file = writeFixture();
    SchematicSource schemSource = new SchematicSource("test", file, "schem");
    SchematicSource schematicSource = new SchematicSource("test", file, "schematic");
    SchematicSource emptyHintSource = new SchematicSource("test", file, "");
    SchematicSource invalidHintSource = new SchematicSource("test", file, "nbt");
    SchematicSource nonExistentSource = new SchematicSource("test", Path.of("does_not_exist.schem"), "schem");

    org.junit.jupiter.api.Assertions.assertTrue(paster.supports(schemSource));
    org.junit.jupiter.api.Assertions.assertTrue(paster.supports(schematicSource));
    org.junit.jupiter.api.Assertions.assertTrue(paster.supports(emptyHintSource));
    org.junit.jupiter.api.Assertions.assertFalse(paster.supports(invalidHintSource));
    org.junit.jupiter.api.Assertions.assertFalse(paster.supports(nonExistentSource));

    // load null
    org.junit.jupiter.api.Assertions.assertNull(paster.load(null).get());

    // load failing file
    org.junit.jupiter.api.Assertions.assertNull(paster.load(nonExistentSource).get());
  }

  @Test
  void loadDecodeFailureLogsViaServerAccessorsFirstAndFallsBackToRtpApi() throws Exception {
    NoopPaster paster = new NoopPaster();
    SchematicSource nonExistent =
        new SchematicSource("bad", Path.of("does_not_exist_logging.schem"), "schem");

    ServerAccessor prevServerAccessor = ServerAccessors.get();
    RTPServerAccessor prevRtpAccessor = RTPAPI.serverAccessor;
    try {
      List<String> serverApiLogs = new ArrayList<>();
      ServerAccessor standaloneAccessor =
          new ServerAccessor() {
            @Override
            public String getServerVersion() {
              return "test";
            }

            @Override
            public String getPlatform() {
              return "paper";
            }

            @Override
            public Integer getServerIntVersion() {
              return 21;
            }

            @Override
            public void log(Level level, String message) {
              serverApiLogs.add(level + ":" + message);
            }

            @Override
            public void log(Level level, String message, Throwable throwable) {
              serverApiLogs.add(level + ":" + message);
            }
          };

      ServerAccessors.set(standaloneAccessor);
      RTPAPI.serverAccessor = null;
      paster.load(nonExistent).get();
      assertEquals(1, serverApiLogs.size(), "logged via standalone ServerAccessors.get()");
      assertTrue(serverApiLogs.get(0).contains("does_not_exist_logging.schem"));
      AbstractFileSchematicPaster.logIfPossible(Level.INFO, "direct message");
      assertEquals(2, serverApiLogs.size());

      List<String> rtpFallbackLogs = new ArrayList<>();
      RTPServerAccessor fallbackAccessor =
          (RTPServerAccessor)
              Proxy.newProxyInstance(
                  RTPServerAccessor.class.getClassLoader(),
                  new Class<?>[] {RTPServerAccessor.class},
                  (proxy, method, args) -> {
                    if ("log".equals(method.getName()) && args != null && args.length >= 2) {
                      rtpFallbackLogs.add(args[0] + ":" + args[1]);
                    }
                    return null;
                  });
      ServerAccessors.set(null);
      RTPAPI.serverAccessor = fallbackAccessor;
      paster.load(nonExistent).get();
      assertEquals(1, rtpFallbackLogs.size(), "fell back to RTPAPI.serverAccessor when ServerAccessors.get() is null");
      assertTrue(rtpFallbackLogs.get(0).contains("does_not_exist_logging.schem"));
    } finally {
      RTPAPI.serverAccessor = prevRtpAccessor;
      ServerAccessors.set(prevServerAccessor);
    }
  }

  private static LoadedSchematic load(AbstractFileSchematicPaster paster, SchematicSource source)
      throws InterruptedException, ExecutionException {
    return paster.load(source).get();
  }
}
