package io.github.dailystruggle.effectsapi;

import io.github.dailystruggle.effectsapi.bukkit.BukkitValueCoercer;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.FireworkEffect;
import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.EffectFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FireworkEffectParseTest {
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void setUp() {
        EffectFactory.setCoercer(new BukkitValueCoercer());
        Effect.setDefaultWarn(warnings::add);
    }

    @Test
    void parsesCanonicalFireworkTokenSequence() {
        FireworkEffect effect = new FireworkEffect();
        // Mirrors the user-facing example in effects/default.yml:
        // FIREWORK.BALL.1.1.RED.YELLOW.true.true.true.0.0.0
        effect.setData("BALL", "1", "1", "RED", "YELLOW",
                "true", "true", "true", "0", "0", "0");
        assertTrue(warnings.isEmpty(),
                "Expected no unparsed tokens; got: " + warnings);
    }

    @Test
    void singleDigitDoesNotGreedilyConsumeColorSlot() {
        // Regression: previously canParse(COLOR, "0") accepted any
        // hex-parsable string, so a stray "0" was assigned to COLOR/FADE
        // and pushed downstream tokens (BLUE, WHITE, true, true, true) off
        // the end of KEY_ORDER, surfacing as the recurring
        // "[FireworkEffect] ignored 5 token(s)" diagnostic. After the fix,
        // "0" is no longer a color and the named colors land on their
        // intended slots even when DX/DY/DZ-shaped zeros appear first.
        FireworkEffect effect = new FireworkEffect();
        effect.setData("BALL", "1", "1", "BLUE", "WHITE",
                "true", "true", "true", "0", "0", "0");
        assertTrue(warnings.isEmpty(),
                "Expected no unparsed tokens; got: " + warnings);
    }

    @Test
    void sixDigitHexStillParsesAsColor() {
        FireworkEffect effect = new FireworkEffect();
        effect.setData("BALL", "1", "1", "FF8800", "00AAFF",
                "true", "true", "true", "0", "0", "0");
        assertTrue(warnings.isEmpty(),
                "6-digit hex must still parse as Color; got: " + warnings);
    }
}
