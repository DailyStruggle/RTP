package io.github.dailystruggle.rtp.common.effects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Shared failure handling for platform effect dispatch.
 *
 * @governedBy ADR-115 (Package DAG Disentanglement and Finite Decision-Preservation Hypergraph)
 * @trace REQ-CORE-ARCH-003
 */
@SuppressWarnings("JavadocDeclaration") // ADR traceability tags
public final class EffectDispatch {
    private EffectDispatch() {}

    /**
     * Runs each effect action independently, logging runtime failures and continuing the group.
     *
     * @param prefix lifecycle prefix identifying the effect group
     * @param effects effects to dispatch; must not be {@code null}
     * @param action per-effect operation, including target assignment and scheduling; must not be {@code null}
     * @throws NullPointerException if {@code prefix}, {@code effects}, or {@code action} is {@code null}
     */
    public static void forEachIsolated(
            String prefix, List<? extends Effect<?>> effects, Consumer<? super Effect<?>> action) {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(effects, "effects");
        Objects.requireNonNull(action, "action");
        for (Effect<?> effect : effects) {
            String effectType = effect == null ? "<null>" : effect.getClass().getSimpleName();
            if (effectType.isEmpty()) effectType = effect.getClass().getName();
            try {
                action.accept(effect);
            } catch (RuntimeException failure) {
                RTP.log(
                        Level.WARNING,
                        "[RTP][effects] " + prefix + " dispatch failed for " + effectType
                                + ": " + failure.getMessage(),
                        failure);
            }
        }
    }

    /** Installs the RTP logger as the warning sink used by {@link Effect}. */
    public static void installWarnSink() {
        Effect.setDefaultWarn(message -> RTP.log(Level.WARNING, "[RTP][effects-api] " + message));
    }
}
