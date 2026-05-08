package io.github.dailystruggle.effectsapi;

import io.github.dailystruggle.effectsapi.bukkit.BukkitValueCoercer;
import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.EffectFactory;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.PotionEffect;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.SoundEffect;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.enums.PotionTypeNames;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.enums.SoundTypeNames;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies effects-api-ADR-002: type-driven adaptive reading order in
 * {@link Effect#applyByType}. The contract under test:
 *
 * <ol>
 *   <li>A token whose runtime type doesn't match the next declared key is
 *       skipped past that key — not assigned to it — and the cursor advances
 *       to the next key whose default-type accepts it.</li>
 *   <li>The cursor never rewinds: once a key is assigned, earlier-positioned
 *       keys are no longer eligible. This keeps {@link Effect#toPermission()}
 *       round-trips deterministic.</li>
 *   <li>Tokens that no remaining key accepts are reported via the injected
 *       {@link Consumer} (S-004) — never silently dropped.</li>
 *   <li>{@link Effect#applyByType} performs no chunk I/O / world load
 *       (S-005) — implicitly verified by running the test off-thread without
 *       an initialized Bukkit server.</li>
 * </ol>
 *
 * <p>Issue: <em>"effect permissions and next-piece continuation when we have
 * a type mismatch, so that permissions with some skipped inputs still work"</em>.
 */
class EffectsApiAdaptiveReadingOrderTest {

    private Consumer<String> savedDefaultWarn;
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void captureWarnings() {
        // Reflectively snapshot is overkill — Effect exposes a setter.
        savedDefaultWarn = msg -> {}; // we don't need the prior value, only restoration to a no-op-equivalent
        Effect.setDefaultWarn(warnings::add);
        // effects-api-ADR-004: Effect#canParse / #str2Obj / #fixData delegate
        // to the bound ValueCoercer. The platform initializer runs in a real
        // server; tests bind it explicitly.
        EffectFactory.setCoercer(new BukkitValueCoercer());
    }

    @AfterEach
    void restoreWarnSink() {
        // Restore to the library default so other tests aren't affected by
        // our captured list.
        Effect.setDefaultWarn(msg -> System.err.println("[effects-api] " + msg));
    }

    @Test
    @DisplayName("PotionEffect: type mismatch on TYPE skips to DURATION without losing the rest")
    void potionEffectSkipsKeyOnTypeMismatch() {
        // "5" cannot parse as a PotionEffectType, but it parses as Integer.
        // Under the unified Bukkit/Fabric KEY_ORDER
        // (TYPE, DURATION, AMPLIFIER, AMBIENT, PARTICLES, ICON), the first
        // Integer-typed key after TYPE is DURATION. Adaptive order must skip
        // TYPE, assign 5 to DURATION, and continue with the remaining tokens.
        PotionEffect effect = new PotionEffect();
        effect.setData("5", "true", "false", "true");

        // TYPE retains its constructor default — was not clobbered with "5".
        assertEquals(PotionEffectType.BLINDNESS, effect.getData().get(PotionTypeNames.TYPE),
                "TYPE must keep its default when no token parses as a PotionEffectType");
        assertEquals(5, ((Number) effect.getData().get(PotionTypeNames.DURATION)).intValue(),
                "DURATION must receive the integer token that TYPE rejected");
        assertEquals(Boolean.TRUE, effect.getData().get(PotionTypeNames.AMBIENT));
        assertEquals(Boolean.FALSE, effect.getData().get(PotionTypeNames.PARTICLES));
        assertEquals(Boolean.TRUE, effect.getData().get(PotionTypeNames.ICON));
        assertTrue(warnings.isEmpty(),
                "All tokens landed on a key — no diagnostics expected. Got: " + warnings);
    }

    @Test
    @DisplayName("PotionEffect: a token that no remaining key accepts is reported once via the warn sink")
    void potionEffectReportsUnparsedTokens() {
        PotionEffect effect = new PotionEffect();
        // "totally-not-a-thing" is not an enum, not a number, not a boolean.
        // It must be reported, not silently swallowed (S-004).
        effect.setData("BLINDNESS", "totally-not-a-thing", "true");

        assertEquals(PotionEffectType.BLINDNESS, effect.getData().get(PotionTypeNames.TYPE));
        assertEquals(Boolean.TRUE, effect.getData().get(PotionTypeNames.AMBIENT),
                "After skipping the unparsable token, the next boolean must land on AMBIENT");
        assertEquals(1, warnings.size(),
                "Exactly one diagnostic line should be emitted for the unparsable token. Got: " + warnings);
        assertTrue(warnings.get(0).contains("totally-not-a-thing"),
                "Diagnostic must name the offending token. Got: " + warnings.get(0));
    }

    @Test
    @DisplayName("SoundEffect: numeric tokens skip the Sound-typed first key and fill the int+float keys")
    void soundEffectSkipsTypeWhenOnlyNumbersGiven() {
        SoundEffect effect = new SoundEffect();
        // First key is TYPE (Sound) — none of these tokens parse as a Sound.
        // VOLUME/PITCH default to Integer (100/100); DX/DY/DZ default to Double.
        // Adaptive order must skip TYPE and feed:
        //   "50","200" -> VOLUME, PITCH (int-parsable),
        //   "2.0","3.0","4.0" -> DX, DY, DZ (float-parsable).
        effect.setData("50", "200", "2.0", "3.0", "4.0");

        assertNotNull(effect.getData().get(SoundTypeNames.TYPE), "TYPE default must survive skip");
        assertEquals(50, ((Number) effect.getData().get(SoundTypeNames.VOLUME)).intValue());
        assertEquals(200, ((Number) effect.getData().get(SoundTypeNames.PITCH)).intValue());
        // NB: legacy Effect.str2Obj coerces float/double tokens via /100. The
        // adaptive-order contract is: tokens land on the right *keys*; the
        // numeric coercion is unchanged. Asserting post-coercion values pins
        // both behaviors at once.
        assertEquals(2.0f / 100f, ((Number) effect.getData().get(SoundTypeNames.DX)).floatValue(), 1e-6);
        assertEquals(3.0f / 100f, ((Number) effect.getData().get(SoundTypeNames.DY)).floatValue(), 1e-6);
        assertEquals(4.0f / 100f, ((Number) effect.getData().get(SoundTypeNames.DZ)).floatValue(), 1e-6);
        assertTrue(warnings.isEmpty(),
                "All numerics landed on numeric slots — expected no diagnostics. Got: " + warnings);
    }

    @Test
    @DisplayName("SoundEffect: a leading float token leapfrogs Sound + Integer slots to land on the first Double key")
    void soundEffectFloatTokenSkipsToFloatKey() {
        SoundEffect effect = new SoundEffect();
        // "0.5" parses neither as Sound nor as Integer — it must skip TYPE,
        // VOLUME, PITCH and land on DX (the first Double-typed key).
        effect.setData("0.5");

        assertEquals(100, ((Number) effect.getData().get(SoundTypeNames.VOLUME)).intValue(),
                "VOLUME must keep its Integer default — 0.5 didn't parse as Integer");
        assertEquals(100, ((Number) effect.getData().get(SoundTypeNames.PITCH)).intValue(),
                "PITCH must keep its Integer default — 0.5 didn't parse as Integer");
        assertEquals(0.5d / 100d, ((Number) effect.getData().get(SoundTypeNames.DX)).doubleValue(), 1e-6,
                "DX is the first Double-typed key after the rejected slots (legacy /100 coercion preserved)");
        assertTrue(warnings.isEmpty(),
                "Token landed on a key — no diagnostics expected. Got: " + warnings);
    }

    @Test
    @DisplayName("Cursor never rewinds: once a later key is assigned, earlier keys stay at default")
    void cursorDoesNotRewind() {
        // Token sequence forces TYPE to be skipped (so DURATION takes the
        // first int token, AMPLIFIER takes the second); then a third int-shaped
        // token cannot rewind to TYPE and must be reported as unparsable
        // (no remaining numeric key after AMPLIFIER under the unified
        // KEY_ORDER {TYPE, DURATION, AMPLIFIER, AMBIENT, PARTICLES, ICON}).
        PotionEffect effect = new PotionEffect();
        effect.setData("3", "7", "11", "false");

        assertEquals(PotionEffectType.BLINDNESS, effect.getData().get(PotionTypeNames.TYPE),
                "TYPE must NOT have been retroactively assigned a later int — cursor doesn't rewind");
        assertEquals(3, ((Number) effect.getData().get(PotionTypeNames.DURATION)).intValue(),
                "First int landed on DURATION");
        assertEquals(7, ((Number) effect.getData().get(PotionTypeNames.AMPLIFIER)).intValue(),
                "Second int landed on AMPLIFIER");
        assertEquals(Boolean.FALSE, effect.getData().get(PotionTypeNames.AMBIENT),
                "Boolean token landed on AMBIENT (next boolean-typed key)");
        assertEquals(1, warnings.size(),
                "The third int token had nowhere to land and should be reported once. Got: " + warnings);
        assertTrue(warnings.get(0).contains("11"),
                "Diagnostic must mention the orphan token. Got: " + warnings.get(0));
    }
}
