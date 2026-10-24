package io.github.dailystruggle.rtp.claimaddon;

import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.mock.BitCatalogFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REQ-API-F-009 Claim addon BIT catalog and lock")
class ClaimSetupBitCatalogTest {
    @Test
    void committedCatalogFilesMatchTheClaimNamespace() throws Exception {
        Path root = repositoryRoot().resolve("addons/LeafRTPClaimAddon");
        if (!Files.isDirectory(root)) throw new AssertionError("missing claim addon directory: " + root);
        ClaimSetupBit.COMPONENT.id();

        BitCatalogFiles.check(root, RtpBitCodes.REGISTRY, "CLAIM");
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("could not locate repository root");
        return current;
    }
}
