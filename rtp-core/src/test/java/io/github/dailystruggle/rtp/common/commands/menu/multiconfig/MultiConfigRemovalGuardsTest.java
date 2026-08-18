package io.github.dailystruggle.rtp.common.commands.menu.multiconfig;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral coverage for {@link MultiConfigRemovalGuard} +
 * {@link MultiConfigRemovalGuards}, plus the two default guards
 * (the regions "default" lock and the worlds "server has world loaded" lock).
 */
class MultiConfigRemovalGuardsTest {

    private File pluginDir;
    private MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() throws Exception {
        pluginDir = Files.createTempDirectory("rtp-multiconfig-guards-test").toFile();
        accessor = RTPTestSetup.install(pluginDir);
        MultiConfigRemovalGuards.clear();
    }

    @AfterEach
    void tearDown() {
        MultiConfigRemovalGuards.clear();
        RTP.serverAccessor = null;
    }

    // -----------------------------------------------------------------------
    // Registry behavior
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Registry")
    class Registry {

        @Test
        @DisplayName("unknown kind returns ALLOW_ALL (never null)")
        void unknownKindAllowsAll() {
            MultiConfigRemovalGuard g = MultiConfigRemovalGuards.get("nope");
            assertNotNull(g);
            assertSame(MultiConfigRemovalGuard.ALLOW_ALL, g);
            assertFalse(g.isLocked("anything"));
            assertEquals("", g.reason("anything"));
        }

        @Test
        @DisplayName("null kind returns ALLOW_ALL")
        void nullKindAllowsAll() {
            assertSame(MultiConfigRemovalGuard.ALLOW_ALL,
                    MultiConfigRemovalGuards.get(null));
        }

        @Test
        @DisplayName("register + get round-trip is case-insensitive on the key")
        void registerCaseInsensitive() {
            MultiConfigRemovalGuard g = lockAll("locked");
            MultiConfigRemovalGuards.register("Regions", g);
            assertSame(g, MultiConfigRemovalGuards.get("regions"));
            assertSame(g, MultiConfigRemovalGuards.get("REGIONS"));
            assertSame(g, MultiConfigRemovalGuards.get("rEgIoNs"));
        }

        @Test
        @DisplayName("register replaces a prior registration (last write wins)")
        void registerReplaces() {
            MultiConfigRemovalGuard first = lockAll("first");
            MultiConfigRemovalGuard second = lockAll("second");
            MultiConfigRemovalGuards.register("regions", first);
            MultiConfigRemovalGuards.register("regions", second);
            assertSame(second, MultiConfigRemovalGuards.get("regions"));
        }

        @Test
        @DisplayName("unregister removes a single kind only")
        void unregisterScoped() {
            MultiConfigRemovalGuards.register("regions", lockAll("r"));
            MultiConfigRemovalGuards.register("worlds", lockAll("w"));
            MultiConfigRemovalGuards.unregister("regions");
            assertSame(MultiConfigRemovalGuard.ALLOW_ALL,
                    MultiConfigRemovalGuards.get("regions"));
            assertFalse(MultiConfigRemovalGuards.get("worlds")
                    == MultiConfigRemovalGuard.ALLOW_ALL);
        }

        @Test
        @DisplayName("clear drops every registration")
        void clearDropsAll() {
            MultiConfigRemovalGuards.register("regions", lockAll("r"));
            MultiConfigRemovalGuards.register("worlds", lockAll("w"));
            MultiConfigRemovalGuards.clear();
            assertSame(MultiConfigRemovalGuard.ALLOW_ALL,
                    MultiConfigRemovalGuards.get("regions"));
            assertSame(MultiConfigRemovalGuard.ALLOW_ALL,
                    MultiConfigRemovalGuards.get("worlds"));
        }

        @Test
        @DisplayName("register rejects null kind, null guard, or empty kind")
        void registerRejectsBadArgs() {
            assertThrows(NullPointerException.class,
                    () -> MultiConfigRemovalGuards.register(null, lockAll("x")));
            assertThrows(NullPointerException.class,
                    () -> MultiConfigRemovalGuards.register("regions", null));
            assertThrows(IllegalArgumentException.class,
                    () -> MultiConfigRemovalGuards.register("", lockAll("x")));
        }
    }

    // -----------------------------------------------------------------------
    // Default-region guard: "default" entry is locked.
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Default-region guard")
    class DefaultRegionGuard {

        private final MultiConfigRemovalGuard regionsGuard = new MultiConfigRemovalGuard() {
            @Override
            public boolean isLocked(String entryName) {
                return entryName != null
                        && entryName.equalsIgnoreCase("default");
            }

            @Override
            public String reason(String entryName) {
                return "default region cannot be removed";
            }
        };

        @BeforeEach
        void registerGuard() {
            MultiConfigRemovalGuards.register("regions", regionsGuard);
        }

        @Test
        @DisplayName("'default' is locked (case-insensitive)")
        void defaultLocked() {
            assertTrue(regionsGuard.isLocked("default"));
            assertTrue(regionsGuard.isLocked("DEFAULT"));
            assertTrue(regionsGuard.isLocked("Default"));
        }

        @Test
        @DisplayName("non-default region names are not locked")
        void otherRegionsFree() {
            assertFalse(regionsGuard.isLocked("rtp_world"));
            assertFalse(regionsGuard.isLocked("nether_arena"));
            assertFalse(regionsGuard.isLocked("default1"));
            assertFalse(regionsGuard.isLocked("default_nether"));
        }

        @Test
        @DisplayName("null entry name is not locked (no NPE)")
        void nullEntryNotLocked() {
            assertFalse(regionsGuard.isLocked(null));
        }

        @Test
        @DisplayName("registered under 'regions' kind via the registry")
        void wiredThroughRegistry() {
            assertSame(regionsGuard, MultiConfigRemovalGuards.get("regions"));
        }
    }

    // -----------------------------------------------------------------------
    // Default-world guard: worlds the server has loaded
    // (RTPServerAccessor.getRTPWorld(name) != null) are locked; orphan
    // YAML entries for worlds the server does NOT have are removable.
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Default-world guard")
    class DefaultWorldGuard {

        private final MultiConfigRemovalGuard worldsGuard = new MultiConfigRemovalGuard() {
            @Override
            public boolean isLocked(String entryName) {
                if (entryName == null) return false;
                if (RTP.serverAccessor == null) return false;
                try {
                    return RTP.serverAccessor.getRTPWorld(entryName) != null;
                } catch (RuntimeException ignored) {
                    return false;
                }
            }

            @Override
            public String reason(String entryName) {
                return "world is loaded on the server";
            }
        };

        @BeforeEach
        void registerGuard() {
            MultiConfigRemovalGuards.register("worlds", worldsGuard);
        }

        @Test
        @DisplayName("server-loaded world is locked")
        void serverLoadedLocked() {
            // MockRTPServerAccessor registers "world" by default.
            assertTrue(worldsGuard.isLocked("world"));
        }

        @Test
        @DisplayName("orphan world (server returns null) is removable")
        void orphanRemovable() {
            assertFalse(worldsGuard.isLocked("ghost_world_no_longer_loaded"));
        }

        @Test
        @DisplayName("newly added world becomes locked")
        void addedWorldBecomesLocked() {
            assertFalse(worldsGuard.isLocked("arena"));
            accessor.addWorld(new MockRTPWorld("arena"));
            assertTrue(worldsGuard.isLocked("arena"));
        }

        @Test
        @DisplayName("guard tolerates a missing serverAccessor (null check)")
        void tolerateMissingAccessor() {
            RTP.serverAccessor = null;
            assertFalse(worldsGuard.isLocked("world"));
        }

        @Test
        @DisplayName("null entry name is not locked (no NPE)")
        void nullEntryNotLocked() {
            assertFalse(worldsGuard.isLocked(null));
        }

        @Test
        @DisplayName("registered under 'worlds' kind via the registry")
        void wiredThroughRegistry() {
            assertSame(worldsGuard, MultiConfigRemovalGuards.get("worlds"));
        }
    }

    // -----------------------------------------------------------------------
    // ALLOW_ALL singleton sanity
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("ALLOW_ALL singleton")
    class AllowAllSingleton {

        @Test
        @DisplayName("locks nothing and returns an empty reason")
        void locksNothing() {
            MultiConfigRemovalGuard g = MultiConfigRemovalGuard.ALLOW_ALL;
            assertFalse(g.isLocked("default"));
            assertFalse(g.isLocked("anything"));
            assertFalse(g.isLocked(null));
            assertEquals("", g.reason("default"));
            assertEquals("", g.reason(null));
        }

        @Test
        @DisplayName("returned for any unregistered kind, including the empty-string lookup")
        void returnedForUnregisteredKinds() {
            assertSame(MultiConfigRemovalGuard.ALLOW_ALL,
                    MultiConfigRemovalGuards.get(""));
            assertSame(MultiConfigRemovalGuard.ALLOW_ALL,
                    MultiConfigRemovalGuards.get("effects"));
            assertSame(MultiConfigRemovalGuard.ALLOW_ALL,
                    MultiConfigRemovalGuards.get("unknown-kind-" + Locale.ROOT));
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static MultiConfigRemovalGuard lockAll(String reason) {
        return new MultiConfigRemovalGuard() {
            @Override public boolean isLocked(String entryName) { return true; }
            @Override public String reason(String entryName) { return reason; }
        };
    }
}
