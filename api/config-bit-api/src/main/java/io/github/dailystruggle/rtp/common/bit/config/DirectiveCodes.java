package io.github.dailystruggle.rtp.common.bit.config;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitCodeNamespace;
import io.github.dailystruggle.bit.BitCodeRegistry;
import io.github.dailystruggle.bit.BitTier;
import java.lang.System.Logger.Level;
import java.util.Objects;

/**
 * Code mapping for {@link DirectiveValidator} (ADR-064, ADR-121 section 6).
 *
 * <p>Standalone callers may use {@link #DEFAULT}, which registers codes under the {@link #CFG}
 * namespace; host applications (such as {@code rtp-core}) pass their own namespace's codes.
 */
public record DirectiveCodes(
        BitCode typeMismatch,
        BitCode optionUnknown,
        BitCode outOfRange,
        BitCode patternMismatch,
        BitCode unknownKey,
        BitCode defaultInvalid,
        BitCode malformed) {

    public static final String SUBSYSTEM = "config";
    public static final BitCodeNamespace CFG = new BitCodeRegistry().reserve("CFG");

    public static final BitCode CFG_TYPE_MISMATCH = CFG.register(1100,
            "CFG_DIRECTIVE_TYPE_MISMATCH", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "Write a value of the type named by the key's @type directive in the bundled default file.");
    public static final BitCode CFG_OPTION_UNKNOWN = CFG.register(1101,
            "CFG_DIRECTIVE_OPTION_UNKNOWN", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "Use one of the values listed by the key's @options directive (case does not matter).");
    public static final BitCode CFG_OUT_OF_RANGE = CFG.register(1102,
            "CFG_DIRECTIVE_OUT_OF_RANGE", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "Keep the value inside the key's @range [min, max] (inclusive; null is open-ended).");
    public static final BitCode CFG_UNKNOWN_KEY = CFG.register(1103,
            "CFG_DIRECTIVE_UNKNOWN_KEY", Level.WARNING, BitTier.PBIT, SUBSYSTEM, "",
            "Remove or rename the key; it is not read. Compare with the bundled default file.");
    public static final BitCode CFG_DEFAULT_INVALID = CFG.register(1104,
            "CFG_DIRECTIVE_DEFAULT_INVALID", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "Shipping bug: the bundled default value or @default violates its own directives; fix the resource.");
    public static final BitCode CFG_MALFORMED = CFG.register(1105,
            "CFG_DIRECTIVE_MALFORMED", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "Shipping bug: the bundled default does not parse or has an unreadable directive; fix the resource.");
    public static final BitCode CFG_PATTERN_MISMATCH = CFG.register(1106,
            "CFG_DIRECTIVE_PATTERN_MISMATCH", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "Write a value matching the regular expression in the key's @pattern directive.");

    public static final DirectiveCodes DEFAULT = new DirectiveCodes(
            CFG_TYPE_MISMATCH,
            CFG_OPTION_UNKNOWN,
            CFG_OUT_OF_RANGE,
            CFG_PATTERN_MISMATCH,
            CFG_UNKNOWN_KEY,
            CFG_DEFAULT_INVALID,
            CFG_MALFORMED);

    public DirectiveCodes {
        Objects.requireNonNull(typeMismatch, "typeMismatch");
        Objects.requireNonNull(optionUnknown, "optionUnknown");
        Objects.requireNonNull(outOfRange, "outOfRange");
        Objects.requireNonNull(patternMismatch, "patternMismatch");
        Objects.requireNonNull(unknownKey, "unknownKey");
        Objects.requireNonNull(defaultInvalid, "defaultInvalid");
        Objects.requireNonNull(malformed, "malformed");
    }

    /** Convenience constructor when {@code patternMismatch} shares {@code typeMismatch}. */
    public DirectiveCodes(
            BitCode typeMismatch,
            BitCode optionUnknown,
            BitCode outOfRange,
            BitCode unknownKey,
            BitCode defaultInvalid,
            BitCode malformed) {
        this(typeMismatch, optionUnknown, outOfRange, typeMismatch, unknownKey, defaultInvalid, malformed);
    }
}
