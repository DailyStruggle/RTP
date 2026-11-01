package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.predict.BudgetPacer;

/**
 * A pipe task whose cost is its wall time to completion, which can end after {@code run()}
 * returns (async chunk loads). {@link TimeBoundTaskPipe} hands it the pacer instead of timing
 * {@code run()}; the task records one sample per terminal outcome, so slow or failing work
 * raises the predicted cost and the pipe starts fewer tasks per pulse.
 */
public interface CompletionPaced {
    /** Called on the pipe's thread before {@code run()}. */
    void bindPacer(BudgetPacer pacer);
}
