package io.github.dailystruggle.rtp.common.commands.menu.multiconfig;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Process-wide registry mapping {@code MultiConfigParser} kinds to {@link MultiConfigRemovalGuard}.
 * Unregistered kinds fall back to {@link MultiConfigRemovalGuard#ALLOW_ALL}.
 */
public final class MultiConfigRemovalGuards {

    private static final ConcurrentMap<String, MultiConfigRemovalGuard> GUARDS =
            new ConcurrentHashMap<>();

    private MultiConfigRemovalGuards() {
        // registry class
    }

    /**
     * Register (or replace) the guard for {@code parserKind}. Last
     * write wins; intentional, so that a plugin reload can re-install
     * the default guards cleanly.
     */
    public static void register(String parserKind, MultiConfigRemovalGuard guard) {
        Objects.requireNonNull(parserKind, "parserKind");
        Objects.requireNonNull(guard, "guard");
        String key = parserKind.toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("parserKind must not be empty");
        }
        GUARDS.put(key, guard);
    }

    /**
     * Look up the guard for {@code parserKind}. Returns
     * {@link MultiConfigRemovalGuard#ALLOW_ALL} when no guard has been
     * registered for that kind (deny-nothing fallback). Never returns
     * {@code null}.
     */
    public static MultiConfigRemovalGuard get(String parserKind) {
        if (parserKind == null) return MultiConfigRemovalGuard.ALLOW_ALL;
        MultiConfigRemovalGuard g = GUARDS.get(parserKind.toLowerCase(Locale.ROOT));
        return g != null ? g : MultiConfigRemovalGuard.ALLOW_ALL;
    }

    /**
     * Remove a single registration. Intended for tests and plugin
     * shutdown.
     */
    public static void unregister(String parserKind) {
        if (parserKind == null) return;
        GUARDS.remove(parserKind.toLowerCase(Locale.ROOT));
    }

    /**
     * Drop every registration. Intended for tests and plugin
     * shutdown. Production code must not call this between
     * {@code register} and the first menu use.
     */
    public static void clear() {
        GUARDS.clear();
    }
}
