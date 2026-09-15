package io.github.dailystruggle.rtp.fabric;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.test.TestUmbrellaContext;
import io.github.dailystruggle.rtp.fabric.commands.test.FabricTestUmbrellaScheduler;
import io.github.dailystruggle.rtp.fabric.commands.test.FabricTestUmbrellaSender;

import java.util.logging.Level;

/**
 * Fabric common entrypoint helper and lifecycle listener.
 *
 * <p>Wires {@link io.github.dailystruggle.rtp.common.RTP#testUmbrellaContext}
 * during {@code ServerLifecycleEvents.SERVER_STARTED}.
 */
public class FabricRTPCommonEntry {

    /**
     * Registers the {@code ServerLifecycleEvents.SERVER_STARTED} listener to populate
     * {@link RTP#testUmbrellaContext}.
     */
    public static void init() {
        try {
            Class<?> lifecycleEventsCls = Class.forName(
                    "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents");
            Class<?> eventCls = Class.forName("net.fabricmc.fabric.api.event.Event");
            Object serverStartedEvent = lifecycleEventsCls.getField("SERVER_STARTED").get(null);
            Class<?> serverStartedCallback = Class.forName(
                    "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents$ServerStarted");

            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    serverStartedCallback.getClassLoader(),
                    new Class<?>[]{serverStartedCallback},
                    (p, method, args) -> {
                        populateContext();
                        return null;
                    });

            java.lang.reflect.Method register = eventCls.getMethod("register", Object.class);
            register.invoke(serverStartedEvent, proxy);
        } catch (Throwable t) {
            // Fabric API lifecycle events may not be present in trimmed runtimes / test environments
            RTP.log(Level.FINE, "[FabricRTPCommonEntry] ServerLifecycleEvents.SERVER_STARTED registration skipped or failed: "
                    + t.getMessage());
        }
    }

    /**
     * Installs the Fabric test umbrella context onto {@link RTP#testUmbrellaContext}
     * if not already installed.
     */
    public static void populateContext() {
        if (RTP.testUmbrellaContext == null) {
            RTP.testUmbrellaContext = new TestUmbrellaContext(
                    new FabricTestUmbrellaSender(),
                    new FabricTestUmbrellaScheduler(),
                    null
            );
        }
    }
}
