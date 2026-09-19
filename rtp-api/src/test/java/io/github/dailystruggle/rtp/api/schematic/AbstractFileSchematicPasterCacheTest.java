package io.github.dailystruggle.rtp.api.schematic;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

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

  private static LoadedSchematic load(AbstractFileSchematicPaster paster, SchematicSource source)
      throws InterruptedException, ExecutionException {
    return paster.load(source).get();
  }
}
