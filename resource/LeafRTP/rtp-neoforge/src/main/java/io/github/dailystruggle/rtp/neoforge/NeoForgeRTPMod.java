package io.github.dailystruggle.rtp.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/**
 * NeoForge mod entry point alias and test umbrella helper.
 *
 * <p>Wires {@link io.github.dailystruggle.rtp.common.RTP#testUmbrellaContext} during server setup
 * ({@link FMLDedicatedServerSetupEvent} or {@link ServerStartingEvent}) and delegates to
 * {@link RTPNeoForgeMod#wireTestUmbrellaContext()}.
 */
public class NeoForgeRTPMod {

  public NeoForgeRTPMod() {
  }

  public NeoForgeRTPMod(IEventBus modBus) {
    if (modBus != null) {
      modBus.addListener(this::onDedicatedServerSetup);
    }
  }

  @SuppressWarnings("java:S1172")
  public void onDedicatedServerSetup(FMLDedicatedServerSetupEvent event) {
    wireTestUmbrellaContext();
  }

  @SubscribeEvent
  @SuppressWarnings("java:S1172")
  public void onServerStarting(ServerStartingEvent event) {
    wireTestUmbrellaContext();
  }

  public static void wireTestUmbrellaContext() {
    RTPNeoForgeMod.wireTestUmbrellaContext();
  }
}
