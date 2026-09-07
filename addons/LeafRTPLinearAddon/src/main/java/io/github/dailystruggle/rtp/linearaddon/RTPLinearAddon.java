package io.github.dailystruggle.rtp.linearaddon;

import io.github.dailystruggle.rtp.anvil.RegionFormatRegistry;
import io.github.dailystruggle.rtp.api.addon.RTPAddon;

import java.util.logging.Logger;

/**
 * Addon providing Linear ({@code .linear}) region file decompression support for RTP (ADR-077).
 *
 * <p>Registers {@link LinearRegionReader} with {@link RegionFormatRegistry} so that worlds
 * utilizing Linear continuous ZSTD region files (e.g. on Leaves or Gale) can be pre-filtered
 * and scanned off-tick.</p>
 */
public final class RTPLinearAddon implements RTPAddon {

    private static final Logger LOG = Logger.getLogger(RTPLinearAddon.class.getName());

    @Override
    public void onLoad() {
        RegionFormatRegistry.register(".linear", LinearRegionReader.INSTANCE);
        LOG.info("[LeafRTPLinearAddon] Registered .linear format support (zstd-jni available: "
                + LinearRegionReader.isZstdAvailable() + ")");
    }

    @Override
    public void onUnload() {
        RegionFormatRegistry.unregister(".linear");
        LOG.info("[LeafRTPLinearAddon] Unregistered .linear format support.");
    }
}
