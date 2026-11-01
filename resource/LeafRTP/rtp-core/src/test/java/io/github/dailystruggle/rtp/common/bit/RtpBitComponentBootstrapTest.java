package io.github.dailystruggle.rtp.common.bit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("REQ-API-F-009 BIT component owner bootstrap")
class RtpBitComponentBootstrapTest {
    @TempDir
    Path tempDir;

    @Test
    void publicComponentIsDiscoveredAndRegisteredOnlyByOwnerBootstrap() throws Exception {
        String componentId = "bootstrap-owner-test";
        RtpBit.RUNNER.unregister(componentId);
        Class<?> owner = BootstrapOnlyOwner.class;

        Class.forName(owner.getName(), true, owner.getClassLoader());
        assertFalse(isRegistered(componentId), "class initialization must not mutate the runner");

        Path classFile = tempDir.resolve(owner.getName().replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParent());
        try (InputStream bytecode = owner.getResourceAsStream("/" + owner.getName().replace('.', '/') + ".class")) {
            assertTrue(bytecode != null, "test owner bytecode must be available");
            Files.copy(bytecode, classFile);
        }
        assertTrue(RtpBitCodeCatalog.declaredOwners(tempDir, owner.getClassLoader()).contains(owner),
                "public static final component-only owner must be discovered");

        try {
            RtpBitCodes.initialiseOwners(List.of(owner));
            assertTrue(isRegistered(componentId), "owner bootstrap registers the declared component");
        } finally {
            RtpBit.RUNNER.unregister(componentId);
        }
    }

    private static boolean isRegistered(String id) {
        return RtpBit.RUNNER.components().stream().anyMatch(component -> component.id().equals(id));
    }

    public static final class BootstrapOnlyOwner {
        public static final BitComponent COMPONENT = new BitComponent("bootstrap-owner-test", "test.bootstrap",
                BitTier.IBIT, BitSideEffect.SAFE, 1_000, () -> BitReport.of(List.of()));
    }
}
