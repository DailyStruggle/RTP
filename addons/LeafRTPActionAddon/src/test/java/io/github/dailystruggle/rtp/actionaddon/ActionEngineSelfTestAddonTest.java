package io.github.dailystruggle.rtp.actionaddon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.rtp.common.action.ActionEngineSelfTest;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ActionEngineSelfTestAddonTest {
  @AfterEach
  void unregisterSelfTest() {
    ActionEngineSelfTest.unregister();
  }

  @Test
  void bundledEngineCasesRegisterAsHealthySafePbit() {
    ActionEngineSelfTest.register(getClass().getClassLoader());

    BitComponent component = RtpBit.RUNNER.components().stream()
        .filter(candidate -> candidate.id().equals("action-engine-selftest"))
        .findFirst()
        .orElseThrow();
    var result = RtpBit.RUNNER.run(component, Runnable::run);

    assertEquals("action", component.subsystem());
    assertEquals(BitTier.PBIT, component.tier());
    assertEquals(BitSideEffect.SAFE, component.sideEffect());
    assertEquals(BitHealth.HEALTHY, result.report().health());
  }
}
