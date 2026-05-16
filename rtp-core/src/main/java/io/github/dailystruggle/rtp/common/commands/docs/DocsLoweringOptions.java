package io.github.dailystruggle.rtp.common.commands.docs;

/**
 * Tunables for {@code MarkdownToMenuModel}. Immutable.
 *
 * <p>Defaults match ADR-045 §"Configuration surface". The lowerer is a pure
 * function of {@code (relpath, source, options)}; no platform types, no I/O.
 *
 * @param maxLineWidth         soft wrap cap for paragraph and list lines, in
 *                             characters. Lines longer than this are word-wrapped
 *                             into multiple {@link io.github.dailystruggle.rtp.api.menu.MenuLine}s.
 *                             Default 56 — empirically the widest a book page
 *                             renders cleanly at default GUI scale.
 * @param maxCodeLineWidth     hard truncate cap for code-fence lines. Lines
 *                             exceeding this are truncated with a trailing
 *                             {@code …} and the full original line is exposed
 *                             via {@code hover} (ADR-045 §"Markdown lowering").
 *                             Default 48.
 * @param exposeDeveloperDocs  whether {@code docs/dev/}, {@code docs/adr/} and
 *                             {@code docs/architecture/} subtrees are included
 *                             in the lowered cache. Default {@code false} —
 *                             admin/operational audience per ADR-045
 *                             §"Permission and visibility".
 * @param maxFileBytes         hard upper bound per {@code *.md} file in bytes.
 *                             Files over this cap are lowered to a single-page
 *                             {@code docs.tooLarge} chrome page instead of their
 *                             content. Default 262144 (256 KiB).
 */
public record DocsLoweringOptions(
        int maxLineWidth,
        int maxCodeLineWidth,
        boolean exposeDeveloperDocs,
        long maxFileBytes) {

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

    /** Defaults per ADR-045 §"Configuration surface". */
    public static DocsLoweringOptions defaults() {
        return new DocsLoweringOptions(56, 48, false, 262144L);
    }
}
