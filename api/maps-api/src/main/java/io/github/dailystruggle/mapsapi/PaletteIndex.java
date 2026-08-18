package io.github.dailystruggle.mapsapi;

/**
 * Logical 32-symbol palette contract referenced from {@link MapCanvas} and
 * realised by every concrete {@link MapBinding}. Renderers shall only ever
 * write bytes drawn from this contract; the active binding translates each
 * logical byte to a concrete vanilla map-colour byte (or the equivalent for
 * non-Bukkit platforms) at commit time.
 *
 * <p>Layout (REQ-RTP-MAP-001, {@code maps-api-ADR-001} section Palette policy):
 * <ul>
 *   <li>{@link #TRANSPARENT} (logical 0): unfilled canvas pixel. Renders as
 *       the host's map-transparent byte ({@code MapPalette.TRANSPARENT} on
 *       Bukkit). Used by {@link MapCanvas#clear()}.</li>
 *   <li>Heat ramp (logical {@value #RAMP_MIN}..{@value #RAMP_MAX}): a black
 *       to red to yellow to white gradient consumed by
 *       {@code HeatmapRenderer}. Index {@link #RAMP_MIN} is the cool end
 *       (still effectively "near-zero" but distinct from
 *       {@link #TRANSPARENT}); index {@link #RAMP_MAX} is the hot end.</li>
 *   <li>Named non-ramp slots ({@value #NAMED_MIN}..{@value #NAMED_MAX}):
 *       categorical colors that do not fit on the heat ramp. Used by
 *       categorical renderers (region shape / bad-locations,
 *       region-biome-overlay, category pies, etc.). Names are stable; the
 *       indices are an implementation detail but exposed as
 *       {@code public static final byte} constants so renderers reference
 *       them by name.</li>
 * </ul>
 *
 * <p>The total contract size is 32 bytes (logical {@code 0..31}), matching
 * the {@code PALETTE} table in every {@link MapBinding} implementation. Any
 * change to slot count or layout shall be accompanied by an amendment to
 * {@code maps-api-ADR-001} section Palette policy and a synchronised update to
 * every binding's {@code buildPalette} method (Bukkit, Folia, Fabric,
 * Noop, InMemory).
 *
 * <p>This class is intentionally non-instantiable; it exists solely as a
 * namespace for the named logical constants.
 */
public final class PaletteIndex {

    /** Transparent / unset pixel. Logical 0. */
    public static final byte TRANSPARENT = 0;

    /** Lowest logical index on the heat ramp (cool end). */
    public static final byte RAMP_MIN = 1;

    /** Highest logical index on the heat ramp (hot end). */
    public static final byte RAMP_MAX = 27;

    /** First logical index reserved for named non-ramp colors. */
    public static final byte NAMED_MIN = 28;

    /** Last logical index reserved for named non-ramp colors. */
    public static final byte NAMED_MAX = 31;

    /** Named slot: solid black. Used by categorical renderers for "outside region" / unexplored backdrop. */
    public static final byte BLACK = 28;

    /** Named slot: solid red. Used by categorical renderers for "bad" / failure / hazard. */
    public static final byte RED = 29;

    /** Named slot: solid green. Used by categorical renderers for "good" / success / safe interior. */
    public static final byte GREEN = 30;

    /** Named slot: solid white. Reserved for high-contrast emphasis / chart frames. */
    public static final byte WHITE = 31;

    private PaletteIndex() {
        throw new AssertionError("PaletteIndex is a constants namespace; not instantiable.");
    }
}
