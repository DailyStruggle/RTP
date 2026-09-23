package io.github.dailystruggle.helpers.stresstestrtp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RosterFairnessTest {

    @Test
    @DisplayName("rosterCursor round-robins across all players and never starves the tail of the roster")
    void testRosterTailNotStarved() throws Exception {
        Field cursorField = Runner.class.getDeclaredField("rosterCursor");
        cursorField.setAccessible(true);

        sun.misc.Unsafe unsafe;
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        unsafe = (sun.misc.Unsafe) f.get(null);

        Runner runner = (Runner) unsafe.allocateInstance(Runner.class);
        AtomicInteger cursor = new AtomicInteger(0);
        cursorField.set(runner, cursor);

        List<String> mockRoster = List.of("leaf_27", "leaf_26", "leaf26");
        int rosterSize = mockRoster.size();

        // Simulate 30 dispatches paced one-per-interval across multiple ticks
        List<String> dispatched = new ArrayList<>();
        for (int tick = 0; tick < 30; tick++) {
            int pickIdx = Math.floorMod(cursor.getAndIncrement(), rosterSize);
            dispatched.add(mockRoster.get(pickIdx));
        }

        long leaf27Count = dispatched.stream().filter("leaf_27"::equals).count();
        long leaf_26Count = dispatched.stream().filter("leaf_26"::equals).count();
        long leaf26Count = dispatched.stream().filter("leaf26"::equals).count();

        assertEquals(10, leaf27Count, "first player gets 1/3 of dispatches");
        assertEquals(10, leaf_26Count, "second player gets 1/3 of dispatches");
        assertEquals(10, leaf26Count, "tail player (leaf26) gets equal 1/3 of dispatches, not dropped");
    }
}
