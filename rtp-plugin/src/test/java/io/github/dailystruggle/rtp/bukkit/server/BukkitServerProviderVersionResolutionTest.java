package io.github.dailystruggle.rtp.bukkit.server;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class BukkitServerProviderVersionResolutionTest {

    @Test
    @DisplayName("REQ-RTP-SYS-001: 26.1, 26.2, and 26.3 versions resolve to modern 26.x server adapters")
    public void testVersionResolution26() {
        String[] versions = new String[] {
            "26.1-R0.1-SNAPSHOT",
            "git-Paper-10 (MC: 26.2)",
            "git-Paper-25 (MC: 26.3)",
            "26.3-SNAPSHOT"
        };

        for (String v : versions) {
            BukkitServerProvider.ServerModel model = BukkitServerProvider.resolveServerModel(v);
            assertTrue(model.accessorClassName.contains("v26_1_R1"),
                    "Expected v26_1_R1 adapter for version " + v + ", got " + model.accessorClassName);
        }
    }

    @Test
    @DisplayName("REQ-RTP-SYS-001: 1.21 versions resolve to v1_21_R1 adapters")
    public void testVersionResolution121() {
        BukkitServerProvider.ServerModel model = BukkitServerProvider.resolveServerModel("git-Paper-130 (MC: 1.21.1)");
        assertTrue(model.accessorClassName.contains("v1_21_R1"),
                "Expected v1_21_R1 adapter for version 1.21.1, got " + model.accessorClassName);
    }

    @Test
    @DisplayName("REQ-RTP-SYS-001: 1.20 versions resolve to v1_20_R1 adapters")
    public void testVersionResolution120() {
        BukkitServerProvider.ServerModel model = BukkitServerProvider.resolveServerModel("git-Paper-400 (MC: 1.20.4)");
        assertTrue(model.accessorClassName.contains("v1_20_R1"),
                "Expected v1_20_R1 adapter for version 1.20.4, got " + model.accessorClassName);
    }
}
