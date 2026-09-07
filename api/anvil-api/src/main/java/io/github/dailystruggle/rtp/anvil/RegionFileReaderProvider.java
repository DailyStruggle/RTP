package io.github.dailystruggle.rtp.anvil;

/**
 * Service provider interface for pluggable region-file format readers (ADR-077).
 *
 * <p>Implementations can be discovered via {@link java.util.ServiceLoader} or registered
 * directly with {@link RegionFormatRegistry}.</p>
 */
public interface RegionFileReaderProvider {

    /**
     * The file extension associated with this format, including the leading dot
     * (e.g. {@code ".linear"}).
     *
     * @return lowercase file extension with leading dot
     */
    String extension();

    /**
     * Returns the {@link RegionFileReader} responsible for decoding this region format.
     *
     * @return the region file reader instance
     */
    RegionFileReader reader();
}
