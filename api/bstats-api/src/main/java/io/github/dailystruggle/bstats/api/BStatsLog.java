package io.github.dailystruggle.bstats.api;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Log sink for the client; the host decides prefixing and routing. Must not throw. */
@FunctionalInterface
public interface BStatsLog {

    /** Discards everything. */
    BStatsLog NONE = (level, message) -> { };

    void log(Level level, String message);

    static BStatsLog of(Logger logger) {
        return logger::log;
    }
}
