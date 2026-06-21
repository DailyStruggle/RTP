package io.github.dailystruggle.rtp.common.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.addon.RTPAddon;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Platform-agnostic addon lifecycle (ADR-057).
 *
 * <p>Verifies that {@link AddonRegistry} drives the {@link RTPAddon} lifecycle the same way on
 * every platform: register -> loadAll (onLoad once) -> unloadAll (onUnload), with isolation so one
 * failing addon cannot abort the load/unload of its peers, and with eager loading of late
 * registrations after {@code loadAll()} has already run.
 */
@DisplayName("AddonRegistry platform-agnostic addon lifecycle (ADR-057)")
class AddonRegistryTest {

  /**
   * Public, no-arg addon used as a {@link java.util.ServiceLoader} provider in the
   * folder-scan tests. Must be public with a public no-arg constructor.
   */
  public static final class FolderScanAddon implements RTPAddon {
    public FolderScanAddon() {}

    @Override
    public void onLoad() {}

    @Override
    public void onUnload() {}

    @Override
    public String name() {
      return "folder-scan-addon";
    }
  }

  private static final class RecordingAddon implements RTPAddon {
    final String id;
    final List<String> sink;

    RecordingAddon(String id, List<String> sink) {
      this.id = id;
      this.sink = sink;
    }

    @Override
    public void onLoad() {
      sink.add("load:" + id);
    }

    @Override
    public void onUnload() {
      sink.add("unload:" + id);
    }

    @Override
    public String name() {
      return id;
    }
  }

  @Test
  @DisplayName("loadAll invokes onLoad exactly once per addon")
  void loadAll_invokes_onLoad_once() {
    List<String> events = new ArrayList<>();
    AddonRegistry registry = new AddonRegistry();
    registry.register(new RecordingAddon("a", events));
    registry.register(new RecordingAddon("b", events));

    registry.loadAll();
    registry.loadAll(); // idempotent

    assertEquals(List.of("load:a", "load:b"), events);
  }

  @Test
  @DisplayName("register ignores null and duplicate instances")
  void register_ignores_null_and_duplicates() {
    List<String> events = new ArrayList<>();
    AddonRegistry registry = new AddonRegistry();
    RecordingAddon a = new RecordingAddon("a", events);

    registry.register(null);
    registry.register(a);
    registry.register(a); // duplicate

    assertEquals(1, registry.registered().size());
  }

  @Test
  @DisplayName("addon registered after loadAll is loaded eagerly")
  void late_registration_loads_eagerly() {
    List<String> events = new ArrayList<>();
    AddonRegistry registry = new AddonRegistry();
    registry.loadAll();

    registry.register(new RecordingAddon("late", events));

    assertEquals(List.of("load:late"), events);
  }

  @Test
  @DisplayName("a failing addon does not abort loading or unloading of peers")
  void failing_addon_is_isolated() {
    List<String> events = new ArrayList<>();
    AddonRegistry registry = new AddonRegistry();
    registry.register(
        new RTPAddon() {
          @Override
          public void onLoad() {
            throw new RuntimeException("boom-load");
          }

          @Override
          public void onUnload() {
            throw new RuntimeException("boom-unload");
          }

          @Override
          public String name() {
            return "bad";
          }
        });
    registry.register(new RecordingAddon("good", events));

    registry.loadAll();
    registry.unloadAll();

    assertTrue(events.contains("load:good"), "peer addon must still load");
    assertTrue(events.contains("unload:good"), "peer addon must still unload");
  }

  @Test
  @DisplayName("unloadAll clears the registry and resets the loaded flag")
  void unloadAll_clears_registry() {
    List<String> events = new ArrayList<>();
    AddonRegistry registry = new AddonRegistry();
    registry.register(new RecordingAddon("a", events));
    registry.loadAll();

    registry.unloadAll();

    assertTrue(events.contains("unload:a"));
    assertTrue(registry.registered().isEmpty());

    // After unload, the registry can be reused: a fresh register + loadAll loads again.
    registry.register(new RecordingAddon("b", events));
    registry.loadAll();
    assertTrue(events.contains("load:b"));
    assertFalse(events.contains("load:a") && events.lastIndexOf("load:a") > events.indexOf("unload:a"));
  }

  @Test
  @DisplayName("discoverFromDirectory tolerates null, missing, and empty folders as no-ops")
  void discoverFromDirectory_noop_on_missing_or_empty(@TempDir File tempDir) {
    AddonRegistry registry = new AddonRegistry();

    registry.discoverFromDirectory(null);
    registry.discoverFromDirectory(new File(tempDir, "does-not-exist"));
    registry.discoverFromDirectory(tempDir); // empty folder

    assertTrue(registry.registered().isEmpty(), "no addons should be discovered");
  }

  @Test
  @DisplayName("discoverFromDirectory registers an addon declared in a jar's service descriptor")
  void discoverFromDirectory_registers_addon_from_jar() throws Exception {
    // A self-managed temp dir (not @TempDir): discoverFromDirectory opens a URLClassLoader over
    // the jar and keeps it open by design (addons need their loader alive at runtime), so the
    // jar cannot be deleted on Windows until the loader is GC'd. Best-effort cleanup only.
    File addonsDir = Files.createTempDirectory("rtp-addons-scan").toFile();
    addonsDir.deleteOnExit();
    try {
      // Build a jar whose only content is a ServiceLoader descriptor pointing at FolderScanAddon.
      // The class itself resolves through the parent (test) classloader.
      File jar = new File(addonsDir, "my-addon.jar");
      jar.deleteOnExit();
      try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar))) {
        jos.putNextEntry(new JarEntry("META-INF/services/" + RTPAddon.class.getName()));
        jos.write((FolderScanAddon.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
        jos.closeEntry();
      }

      AddonRegistry registry = new AddonRegistry();
      registry.discoverFromDirectory(addonsDir);

      assertEquals(1, registry.registered().size(), "addon from jar should be registered");
      assertEquals("folder-scan-addon", registry.registered().get(0).name());
    } finally {
      // best-effort: the loader may still hold the jar open on Windows
      new File(addonsDir, "my-addon.jar").delete();
      addonsDir.delete();
    }
  }

  @Test
  @DisplayName("discover tolerates an addon whose implementation cannot be linked")
  void discover_isolates_unlinkable_addon() throws Exception {
    // Mirrors the rtp-lite crash (NoClassDefFoundError: effectsapi/common/Effect): a service
    // descriptor names a provider that cannot be loaded/linked. Discovery must not propagate
    // the LinkageError/ServiceConfigurationError and abort plugin startup.
    File addonsDir = Files.createTempDirectory("rtp-addons-bad").toFile();
    addonsDir.deleteOnExit();
    try {
      File jar = new File(addonsDir, "bad-addon.jar");
      jar.deleteOnExit();
      try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar))) {
        jos.putNextEntry(new JarEntry("META-INF/services/" + RTPAddon.class.getName()));
        jos.write(
            "io.github.dailystruggle.rtp.common.addon.DoesNotExistAddon\n"
                .getBytes(StandardCharsets.UTF_8));
        jos.closeEntry();
      }

      AddonRegistry registry = new AddonRegistry();
      registry.discoverFromDirectory(addonsDir); // must not throw
      assertTrue(registry.registered().isEmpty(), "an unlinkable addon registers nothing");
    } finally {
      new File(addonsDir, "bad-addon.jar").delete();
      addonsDir.delete();
    }
  }

  @Test
  @DisplayName("extractBundledAddons copies indexed jars from the classpath and never overwrites")
  void extractBundledAddons_copies_indexed_jars(@TempDir File tempDir) throws Exception {
    // Stage a fake "RTP jar" classpath root holding the bundled-addons index + payload.
    File classpathRoot = new File(tempDir, "cp");
    File bundleDir = new File(classpathRoot, "bundled-addons");
    assertTrue(bundleDir.mkdirs());
    Files.writeString(
        new File(bundleDir, "index").toPath(),
        "# comment line\n\nDemoAddon.jar\nMissing.jar\n",
        StandardCharsets.UTF_8);
    byte[] payload = "demo-jar-bytes".getBytes(StandardCharsets.UTF_8);
    Files.write(new File(bundleDir, "DemoAddon.jar").toPath(), payload);

    File addonsDir = new File(tempDir, "addons");
    try (java.net.URLClassLoader loader =
        new java.net.URLClassLoader(new java.net.URL[] {classpathRoot.toURI().toURL()}, null)) {
      AddonRegistry registry = new AddonRegistry();
      registry.extractBundledAddons(addonsDir, loader);

      File extracted = new File(addonsDir, "DemoAddon.jar");
      assertTrue(extracted.isFile(), "indexed jar present on the classpath must be extracted");
      assertEquals(
          new String(payload, StandardCharsets.UTF_8),
          Files.readString(extracted.toPath()),
          "extracted bytes must match the bundled resource");
      assertFalse(
          new File(addonsDir, "Missing.jar").exists(),
          "an indexed-but-absent resource is skipped, not created");

      // Re-extraction never clobbers an operator's edited copy.
      Files.writeString(extracted.toPath(), "edited-by-operator", StandardCharsets.UTF_8);
      registry.extractBundledAddons(addonsDir, loader);
      assertEquals(
          "edited-by-operator",
          Files.readString(extracted.toPath()),
          "an already-present jar must be left untouched on a second pass");
    }
  }

  @Test
  @DisplayName("extractBundledAddons is a no-op when no bundle index is on the classpath")
  void extractBundledAddons_noop_without_index(@TempDir File tempDir) throws Exception {
    File addonsDir = new File(tempDir, "addons");
    try (java.net.URLClassLoader empty =
        new java.net.URLClassLoader(new java.net.URL[0], null)) {
      new AddonRegistry().extractBundledAddons(addonsDir, empty);
    }
    assertFalse(addonsDir.exists(), "no index => nothing created");
  }
}
