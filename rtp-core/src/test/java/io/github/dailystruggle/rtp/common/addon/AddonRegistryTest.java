package io.github.dailystruggle.rtp.common.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.addon.RTPAddon;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
