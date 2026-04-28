package io.github.dailystruggle.rtp.api;

/**
 * DRM / audit metadata captured from marketplace download pipelines.
 *
 * <p>Each marketplace (SpigotMC, BuiltByBit, Polymart) performs a byte-level
 * string replacement inside the published JAR before serving it to the buyer,
 * substituting marketplace-specific placeholder tokens. SpigotMC and BuiltByBit
 * share the same {@code %%__USER__%%} / {@code %%__NONCE__%%} literals, while
 * BuiltByBit additionally substitutes {@code %%__RESOURCE__%%} and
 * {@code %%__TIMESTAMP__%%}. Polymart uses its own distinct tokens.
 *
 * <p>Whichever marketplace served the JAR is the only one whose tokens get
 * replaced in that copy; the other marketplaces' tokens remain as literals.
 * {@link #source()} keys off this asymmetry to identify the originating
 * marketplace.
 *
 * <p>These values are <b>opaque audit strings</b>, suitable for support
 * correlation and DRM nudge messages — not for authenticating callers or
 * gating functionality.
 */
public final class DownloadInfo {

    /** The marketplace that produced this JAR, as inferred from substituted placeholders. */
    public enum Source {
        /** SpigotMC. Only {@code %%__USER__%%} / {@code %%__NONCE__%%} substituted. */
        SPIGOT,
        /** BuiltByBit. Substitutes USER/NONCE plus RESOURCE/TIMESTAMP. */
        BUILTBYBIT,
        /** Polymart. Substitutes its own POLYMART/SIGNATURE tokens. */
        POLYMART,
        /** Local development build — no marketplace tokens substituted. */
        DEV
    }

    // SpigotMC / BuiltByBit share these literals.
    public static final String SPIGOT_BBB_USER  = "%%__USER__%%";
    public static final String SPIGOT_BBB_NONCE = "%%__NONCE__%%";

    // BuiltByBit-only extras.
    public static final String BBB_RESOURCE  = "%%__RESOURCE__%%";
    public static final String BBB_TIMESTAMP = "%%__TIMESTAMP__%%";

    // Polymart-only.
    public static final String POLYMART_USER      = "%%__POLYMART__%%";
    public static final String POLYMART_SIGNATURE = "%%__SIGNATURE__%%";

    private DownloadInfo() {}

    /**
     * Identifies the marketplace that produced this JAR.
     *
     * <p>Comparison literals are split (e.g. {@code "%%__USER" + "__%%"}) so that
     * the marketplace replacer cannot also rewrite the comparison string and
     * fool the detector into always reporting a substitution.
     */
    public static Source source() {
        if (!POLYMART_USER.equals("%%__POLYMART" + "__%%")) return Source.POLYMART;
        if (!BBB_RESOURCE.equals("%%__RESOURCE" + "__%%")) return Source.BUILTBYBIT;
        if (!SPIGOT_BBB_USER.equals("%%__USER" + "__%%")) return Source.SPIGOT;
        return Source.DEV;
    }

    /** Buyer's marketplace user ID, or the unsubstituted placeholder for DEV builds. */
    public static String userId() {
        if (source() == Source.POLYMART) return POLYMART_USER;
        return SPIGOT_BBB_USER;
    }

    /** One-time download nonce, or the unsubstituted placeholder for DEV builds. */
    public static String nonce() {
        if (source() == Source.POLYMART) return POLYMART_SIGNATURE;
        return SPIGOT_BBB_NONCE;
    }

    /** BuiltByBit resource id, empty string when the source is not BuiltByBit. */
    public static String resourceId() {
        return source() == Source.BUILTBYBIT ? BBB_RESOURCE : "";
    }

    /** BuiltByBit download timestamp, empty string when the source is not BuiltByBit. */
    public static String timestamp() {
        return source() == Source.BUILTBYBIT ? BBB_TIMESTAMP : "";
    }
}
