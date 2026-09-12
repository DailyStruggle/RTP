package io.github.dailystruggle.rtp.linearaddon;

import io.github.dailystruggle.rtp.anvil.RegionFileReader;
import io.github.dailystruggle.rtp.anvil.RegionFileReaderProvider;

/**
 * ServiceLoader provider for {@code .linear} region file reader (ADR-077).
 */
public final class LinearRegionReaderProvider implements RegionFileReaderProvider {

    @Override
    public String extension() {
        return ".linear";
    }

    @Override
    public RegionFileReader reader() {
        return LinearRegionReader.INSTANCE;
    }
}
