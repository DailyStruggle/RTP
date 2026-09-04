package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * A centralized registry for custom location verifiers that apply to all regions.
 * This allows other plugins to hook into the location selection process to add
 * their own safety checks, such as claim protection or custom area restrictions.
 */
public class GlobalRegionVerifiers {
    public record RegisteredSyncVerifier(Class<?> source, Predicate<RTPCoords> check) {}
    public record RegisteredAsyncVerifier(Class<?> source, Function<RTPCoords, CompletableFuture<Boolean>> check) {}

    public record VerifierCheckResult(boolean passed, Class<?> failedVerifierClass) {
        public static final VerifierCheckResult PASS = new VerifierCheckResult(true, null);
    }

    private static final List<RegisteredSyncVerifier> regionVerifiers = new CopyOnWriteArrayList<>();
    private static final List<RegisteredAsyncVerifier> asyncRegionVerifiers = new CopyOnWriteArrayList<>();

    /**
     * Adds a synchronous global region verifier with source class attribution (ADR-079).
     *
     * @param source        The class registering the verifier.
     * @param locationCheck A predicate that returns {@code true} for a valid location,
     *                      or {@code false} for an invalid one.
     */
    public static void addGlobalRegionVerifier(Class<?> source, Predicate<RTPCoords> locationCheck) {
        regionVerifiers.add(new RegisteredSyncVerifier(source != null ? source : locationCheck.getClass(), locationCheck));
    }

    /**
     * Adds a synchronous global region verifier. This verifier will be executed
     * for every potential teleport location.
     *
     * @param locationCheck A predicate that returns {@code true} for a valid location,
     *                      or {@code false} for an invalid one.
     */
    public static void addGlobalRegionVerifier(Predicate<RTPCoords> locationCheck) {
        addGlobalRegionVerifier(locationCheck.getClass(), locationCheck);
    }

    /**
     * Adds an asynchronous global region verifier with source class attribution (ADR-079).
     *
     * @param source        The class registering the verifier.
     * @param locationCheck A function that returns a {@link CompletableFuture<Boolean>}
     *                      which completes with {@code true} for a valid location,
     *                      or {@code false} for an invalid one.
     */
    public static void addGlobalRegionVerifierAsync(Class<?> source, Function<RTPCoords, CompletableFuture<Boolean>> locationCheck) {
        asyncRegionVerifiers.add(new RegisteredAsyncVerifier(source != null ? source : locationCheck.getClass(), locationCheck));
    }

    /**
     * Adds an asynchronous global region verifier. This is useful for checks that
     * may involve I/O or other long-running operations.
     *
     * @param locationCheck A function that returns a {@link CompletableFuture<Boolean>}
     *                      which completes with {@code true} for a valid location,
     *                      or {@code false} for an invalid one.
     */
    public static void addGlobalRegionVerifierAsync(Function<RTPCoords, CompletableFuture<Boolean>> locationCheck) {
        addGlobalRegionVerifierAsync(locationCheck.getClass(), locationCheck);
    }

    /**
     * Returns the total number of currently registered verifiers (sync + async).
     * Used by the {@code RegionVerifierRegistry#size()} facade (ADR-026).
     *
     * @return the total registered verifier count
     */
    public static int registeredCount() {
        return regionVerifiers.size() + asyncRegionVerifiers.size();
    }

    /**
     * Removes all registered global region verifiers.
     */
    public static void clearGlobalRegionVerifiers() {
        regionVerifiers.clear();
        asyncRegionVerifiers.clear();
    }

    /**
     * Checks a given location against all registered synchronous and asynchronous
     * global verifiers, returning detailed verification outcome and the vetoing verifier class.
     *
     * @param location The location to check.
     * @return A {@link CompletableFuture<VerifierCheckResult>} completing with verification details.
     */
    public static CompletableFuture<VerifierCheckResult> checkGlobalRegionVerifiersDetailed(RTPCoords location) {
        for (RegisteredSyncVerifier verifier : regionVerifiers) {
            try {
                if (!verifier.check().test(location)) {
                    return CompletableFuture.completedFuture(new VerifierCheckResult(false, verifier.source()));
                }
            } catch (Throwable throwable) {
                // Fail safe: a throwing verifier is logged at WARNING and the location is
                // rejected (treated as false), never silently accepted (REQ-RTP-S-004).
                RTP.log(Level.WARNING, "Global region verifier threw an exception", throwable);
                return CompletableFuture.completedFuture(new VerifierCheckResult(false, verifier.source()));
            }
        }

        if (asyncRegionVerifiers.isEmpty()) {
            return CompletableFuture.completedFuture(VerifierCheckResult.PASS);
        }

        CompletableFuture<VerifierCheckResult> result = CompletableFuture.completedFuture(VerifierCheckResult.PASS);
        for (RegisteredAsyncVerifier verifier : asyncRegionVerifiers) {
            result = result.thenCompose(res -> {
                if (!res.passed()) return CompletableFuture.completedFuture(res);
                try {
                    return verifier.check().apply(location).handle((pass, ex) -> {
                        if (ex != null) {
                            RTP.log(Level.WARNING, "Async global region verifier threw an exception", ex);
                            return new VerifierCheckResult(false, verifier.source());
                        }
                        if (!Boolean.TRUE.equals(pass)) {
                            return new VerifierCheckResult(false, verifier.source());
                        }
                        return VerifierCheckResult.PASS;
                    });
                } catch (Throwable throwable) {
                    RTP.log(Level.WARNING, "Async global region verifier threw an exception", throwable);
                    return CompletableFuture.completedFuture(new VerifierCheckResult(false, verifier.source()));
                }
            });
        }
        return result;
    }

    /**
     * Checks a given location against all registered synchronous and asynchronous
     * global verifiers. Retained for backwards compatibility across call sites.
     *
     * @param location The location to check.
     * @return A {@link CompletableFuture<Boolean>} that completes with {@code true}
     *         if the location is valid according to all verifiers, otherwise {@code false}.
     */
    public static CompletableFuture<Boolean> checkGlobalRegionVerifiers(RTPCoords location) {
        return checkGlobalRegionVerifiersDetailed(location).thenApply(VerifierCheckResult::passed);
    }

    /**
     * A convenience method to check a {@link MutableRTPCoords} location against all
     * registered verifiers by converting it to an immutable {@link RTPCoords}.
     *
     * @param location The mutable location to check.
     * @return A {@link CompletableFuture<Boolean>} that completes with the result of the check.
     */
    public static CompletableFuture<Boolean> checkGlobalRegionVerifiers(MutableRTPCoords location) {
        return checkGlobalRegionVerifiers(location.toImmutable());
    }
}
