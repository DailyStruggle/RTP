package io.github.dailystruggle.rtp.common.commands.docs;

/**
 * Options for markdown-to-menu-model conversion (ADR-045).
 *
 * @param maxLineWidth        soft wrap cap for paragraphs in characters (default 56)
 * @param maxCodeLineWidth    hard truncate cap for code lines in characters (default 48)
 * @param exposeDeveloperDocs whether developer/internal docs are exposed (default false)
 * @param maxFileBytes        maximum file size in bytes before tooLarge page (default 256 KiB)
 */
public record DocsLoweringOptions(
        int maxLineWidth,
        int maxCodeLineWidth,
        boolean exposeDeveloperDocs,
        long maxFileBytes) {

    /** Validates the record components. */
    public DocsLoweringOptions {
        if (maxLineWidth < 16) {
            throw new IllegalArgumentException("maxLineWidth must be >= 16, got " + maxLineWidth);
        }
        if (maxCodeLineWidth < 16) {
            throw new IllegalArgumentException("maxCodeLineWidth must be >= 16, got " + maxCodeLineWidth);
        }
        if (maxFileBytes < 1024L) {
            throw new IllegalArgumentException("maxFileBytes must be >= 1024, got " + maxFileBytes);
        }
    }

    /**
     * Returns the default options per ADR-045.
     *
     * @return a {@link DocsLoweringOptions} with default values
     */
    public static DocsLoweringOptions defaults() {
        return new DocsLoweringOptions(56, 48, false, 262144L);
    }
}
