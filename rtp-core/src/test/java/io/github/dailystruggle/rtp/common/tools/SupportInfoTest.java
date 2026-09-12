package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.api.DownloadInfo;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SupportInfo} (ENTERPRISE_READINESS item 19, {@code tools} package).
 *
 * <p>A JUnit test build carries no marketplace token substitution, so
 * {@link DownloadInfo#source()} is {@link DownloadInfo.Source#DEV} and
 * {@link SupportInfo#getSig()} resolves to {@code "Dev"}. The support-signature
 * string is composed from the {@link io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor}
 * metadata wired by {@link RTPTestSetup}.
 */
public class SupportInfoTest {

    @TempDir
    File pluginDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(pluginDir);
    }

    @Test
    void getSigReturnsDevForAnUnstampedBuild() {
        assertEquals("Dev", SupportInfo.getSig(),
                "an un-substituted JUnit build is a DEV source");
    }

    @Test
    void supportSignatureCombinesMockServerMetadata() {
        String sig = SupportInfo.getSupportSignature();
        assertEquals(
                "Plugin Version: TEST, Server Platform: mock, Server Version: 1.0.0-MOCK, "
                        + "Build Signature: Dev",
                sig);
    }

    @Test
    void supportSignatureContainsAllFieldLabels() {
        String sig = SupportInfo.getSupportSignature();
        assertTrue(sig.contains("Plugin Version:"), sig);
        assertTrue(sig.contains("Server Platform:"), sig);
        assertTrue(sig.contains("Server Version:"), sig);
        assertTrue(sig.contains("Build Signature:"), sig);
    }
}
