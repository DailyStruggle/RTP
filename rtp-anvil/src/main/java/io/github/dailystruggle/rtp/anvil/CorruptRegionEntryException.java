package io.github.dailystruggle.rtp.anvil;

import java.io.IOException;

/**
 * Signals a known, benign-to-the-pipeline form of region-file corruption detected
 * while decoding a single chunk entry — e.g. a location-table pointer that spans
 * past the end of the {@code .mca} file, an implausible declared chunk length, or
 * a region buffer that is shorter than the 8 KiB header.
 *
 * <p>AnvilPrefilter catches this as a known failure type and falls through to the
 * live chunk load path without a stacktrace. Unknown decode failures continue to
 * surface as a plain {@link IOException} / {@link RuntimeException} at WARNING with
 * a full stacktrace, so operators can still tell real bugs apart from the routine
 * "one corrupt sector entry among thousands" noise.
 */
public class CorruptRegionEntryException extends IOException {
    private static final long serialVersionUID = 1L;

    public CorruptRegionEntryException(String message) {
        super(message);
    }
}
