package io.github.dailystruggle.rtp.common.predict;

import java.util.function.Function;

/**
 * Domain-agnostic behavioral policy actuator evaluating comparative branch costs and threshold conditions.
 *
 * <p>Separates pure mathematical cost estimation from runtime behavioral policy (REQ-PRED-F-004).
 *
 * @param <T> decision outcome type (typically an enum representing execution branches)
 */
public final class ComparativeCostGovernor<T extends Enum<T>> {

    private final Function<CostContext, T> policy;

    private ComparativeCostGovernor(Function<CostContext, T> policy) {
        if (policy == null) {
            throw new IllegalArgumentException("Decision policy function cannot be null");
        }
        this.policy = policy;
    }

    /**
     * Constructs a comparative governor governed by the given evaluation policy.
     *
     * @param policy evaluation function mapping context to branch decision
     * @param <T>    enum decision type
     * @return configured governor
     */
    public static <T extends Enum<T>> ComparativeCostGovernor<T> of(Function<CostContext, T> policy) {
        return new ComparativeCostGovernor<>(policy);
    }

    /**
     * Evaluates the decision policy against the given cost context.
     *
     * @param context runtime cost and performance metrics
     * @return selected execution branch
     */
    public T evaluate(CostContext context) {
        if (context == null) throw new IllegalArgumentException("CostContext cannot be null");
        return policy.apply(context);
    }

    /**
     * Returns the underlying decision policy function.
     *
     * @return policy function
     */
    public Function<CostContext, T> policy() {
        return policy;
    }
}
