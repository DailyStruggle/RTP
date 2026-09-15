package io.github.dailystruggle.rtp.neoforge;

import io.github.dailystruggle.rtp.common.commands.test.TestUmbrellaContext;
import net.neoforged.bus.api.IEventBus;
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

  public void onDedicatedServerSetup(FMLDedicatedServerSetupEvent event) {
    wireTestUmbrellaContext();
  }

  public void onServerStarting(ServerStartingEvent event) {
    wireTestUmbrellaContext();
  }

  public static void wireTestUmbrellaContext() {
    RTPNeoForgeMod.wireTestUmbrellaContext();
  }
}
