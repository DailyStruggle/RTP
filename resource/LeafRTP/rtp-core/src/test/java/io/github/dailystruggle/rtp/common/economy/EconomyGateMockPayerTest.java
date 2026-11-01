package io.github.dailystruggle.rtp.common.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dailystruggle.rtp.api.economy.RTPEconomy;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys;
import io.github.dailystruggle.rtp.common.mock.MockPlayerRegistry;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.mock.RuntimeMockRTPPlayer;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.io.File;
import java.util.EnumMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ADR-121 section 11: EconomyGate never touches the economy for a BIT mock payer")
class EconomyGateMockPayerTest {

  @TempDir File tempDir;

  private ConfigParser<EconomyKeys> eco;
  private EnumMap<EconomyKeys, Object> savedEco;
  private RTPEconomy savedEconomy;
  private final AtomicInteger balCalls = new AtomicInteger();
  private final AtomicInteger takeCalls = new AtomicInteger();
  private final AtomicInteger giveCalls = new AtomicInteger();

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    MockPlayerRegistry.clear();
    RTPTestSetup.install(tempDir);
    eco = (ConfigParser<EconomyKeys>) ActiveConfigs.get().getParser(EconomyKeys.class);
    savedEco = eco.getData();
    EnumMap<EconomyKeys, Object> mutated = new EnumMap<>(savedEco);
    mutated.put(EconomyKeys.price, 100.0);
    mutated.put(EconomyKeys.priceOther, 200.0);
    mutated.put(EconomyKeys.balanceFloor, 0.0);
    eco.setData(mutated);
    savedEconomy = RTPRuntime.economy();
    RTPRuntime.setEconomy(new RTPEconomy() {
      @Override public void give(UUID playerId, double money) { giveCalls.incrementAndGet(); }
      @Override public boolean take(UUID playerId, double money) {
        takeCalls.incrementAndGet();
        return true;
      }
      @Override public double bal(UUID playerId) {
        balCalls.incrementAndGet();
        return 1000.0;
      }
    });
  }

  @AfterEach
  void tearDown() {
    MockPlayerRegistry.clear();
    if (eco != null && savedEco != null) eco.setData(savedEco);
    RTPRuntime.setEconomy(savedEconomy);
  }

  @Test
  @DisplayName("ADR-121 section 11: mock payer is ALLOWED at cost 0.0 with no bal/take/give calls")
  void mockPayer_isAllowedFree_withoutEconomyCalls() {
    UUID id = UUID.randomUUID();
    RuntimeMockRTPPlayer mock =
        new RuntimeMockRTPPlayer(id, "BitMock", new RTPLocation(new MockRTPWorld("world"), 0, 64, 0));
    MockPlayerRegistry.register(mock);

    EconomyGate.Charge charge =
        EconomyGate.charge(id, null, null, EconomyGate.PriceKey.SELF, true, true);

    assertEquals(EconomyGate.Result.ALLOWED, charge.result());
    assertEquals(0.0, charge.cost(), 1e-9);
    assertEquals(0, balCalls.get(), "bal must not be queried for a mock payer");
    assertEquals(0, takeCalls.get(), "take must not be called for a mock payer");
    assertEquals(0, giveCalls.get(), "give must not be called for a mock payer");
  }

  @Test
  @DisplayName("ADR-121 section 11: a real payer is still charged the configured price")
  void realPayer_isStillCharged() {
    UUID id = UUID.randomUUID();
    MockRTPPlayer real =
        new MockRTPPlayer(id, "Real", new RTPLocation(new MockRTPWorld("world"), 0, 64, 0)) {
          @Override
          public boolean hasPermission(String permission) {
            return !"rtp.free".equals(permission);
          }
        };

    EconomyGate.Charge charge =
        EconomyGate.charge(id, real, null, EconomyGate.PriceKey.SELF, false, false);

    assertEquals(EconomyGate.Result.ALLOWED, charge.result());
    assertEquals(100.0, charge.cost(), 1e-9);
    assertEquals(1, balCalls.get());
    assertEquals(1, takeCalls.get());
  }
}
