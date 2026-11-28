package io.github.dailystruggle.helpers.flightspeed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.papermc.paper.event.player.PlayerFailMoveEvent;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class FlightSpeedHelperPluginTest {

    static final class FakeAbilities {
        float flyingSpeed = 0.05f;

        void setFlyingSpeed(float speed) {
            this.flyingSpeed = speed;
        }
    }

    static final class FakeServerPlayer {
        final FakeAbilities abilities = new FakeAbilities();
        int updateCalls = 0;

        FakeAbilities getAbilities() {
            return abilities;
        }

        void onUpdateAbilities() {
            updateCalls++;
        }
    }

    static final class FakeCraftPlayer {
        final FakeServerPlayer handle = new FakeServerPlayer();

        FakeServerPlayer getHandle() {
            return handle;
        }
    }

    @Test
    void parseMultiplierAcceptsBoundsAndTrailingX() {
        assertEquals(1.0f, FlightSpeedHelperPlugin.parseMultiplier("1", 100.0f));
        assertEquals(25.0f, FlightSpeedHelperPlugin.parseMultiplier("25x", 100.0f));
        assertEquals(0.5f, FlightSpeedHelperPlugin.parseMultiplier("0.5X", 100.0f));
        assertNull(FlightSpeedHelperPlugin.parseMultiplier("0", 100.0f));
        assertNull(FlightSpeedHelperPlugin.parseMultiplier("-2", 100.0f));
        assertNull(FlightSpeedHelperPlugin.parseMultiplier("101", 100.0f));
        assertNull(FlightSpeedHelperPlugin.parseMultiplier("NaN", 100.0f));
        assertNull(FlightSpeedHelperPlugin.parseMultiplier("abc", 100.0f));
    }

    @Test
    void applyFlightMultiplierUsesBukkitApiWithinTenXAndTracksBoostedSet() {
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getAllowFlight()).thenReturn(false);

        Set<UUID> boosted = new HashSet<>();
        Logger logger = Logger.getLogger("FlightSpeedHelperTest");

        assertTrue(FlightSpeedHelperPlugin.applyFlightMultiplier(player, 5.0f, boosted, logger));
        verify(player).setAllowFlight(true);
        verify(player).setFlySpeed(0.5f);
        assertTrue(boosted.contains(uuid));

        when(player.getAllowFlight()).thenReturn(true);
        assertTrue(FlightSpeedHelperPlugin.applyFlightMultiplier(player, 1.0f, boosted, logger));
        verify(player).setFlySpeed(0.1f);
        assertFalse(boosted.contains(uuid));
    }

    @Test
    void applyUnclampedNmsAbilitiesSpeedWritesBeyondBukkitCapAndSyncsPacket() {
        FakeCraftPlayer fakeCraftPlayer = new FakeCraftPlayer();
        Logger logger = Logger.getLogger("FlightSpeedHelperTest");

        assertTrue(
                FlightSpeedHelperPlugin.applyUnclampedNmsAbilitiesSpeed(
                        fakeCraftPlayer, 1.25f, logger));
        assertEquals(1.25f, fakeCraftPlayer.handle.abilities.flyingSpeed, 1e-6f);
        assertEquals(1, fakeCraftPlayer.handle.updateCalls);
    }

    @Test
    void applyFlightMultiplierFallsBackToBukkitCapWhenHandleUnavailable() {
        Player plainMockPlayer = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(plainMockPlayer.getUniqueId()).thenReturn(uuid);
        when(plainMockPlayer.getAllowFlight()).thenReturn(true);

        Set<UUID> boosted = new HashSet<>();
        Logger logger = Logger.getLogger("FlightSpeedHelperTest");

        assertFalse(
                FlightSpeedHelperPlugin.applyFlightMultiplier(
                        plainMockPlayer, 25.0f, boosted, logger));
        verify(plainMockPlayer).setFlySpeed(1.0f);
        assertTrue(boosted.contains(uuid));
    }

    @Test
    void resolveMaxMultiplierFallsBackOnInvalidConfig() {
        YamlConfiguration cfg = new YamlConfiguration();
        assertEquals(100.0f, FlightSpeedHelperPlugin.resolveMaxMultiplier(cfg));
        cfg.set("max-multiplier", 50.0d);
        assertEquals(50.0f, FlightSpeedHelperPlugin.resolveMaxMultiplier(cfg));
        cfg.set("max-multiplier", -5.0d);
        assertEquals(100.0f, FlightSpeedHelperPlugin.resolveMaxMultiplier(cfg));
    }

    @Test
    void failMoveCheckMatchesBoostedFlyingPlayer() {
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.isFlying()).thenReturn(true);

        Location from = new Location(null, 0, 100, 0);
        Location to = new Location(null, 50, 100, 0);
        PlayerFailMoveEvent movedQuickly =
                new PlayerFailMoveEvent(
                        player,
                        PlayerFailMoveEvent.FailReason.MOVED_TOO_QUICKLY,
                        false,
                        true,
                        from,
                        to);
        assertNotNull(movedQuickly);
        assertEquals(
                PlayerFailMoveEvent.FailReason.MOVED_TOO_QUICKLY, movedQuickly.getFailReason());
        verify(player, never()).setFlying(false);
    }
}
