package io.github.dailystruggle.bit;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Thread-safe command sink for an initiated test simulation. */
public final class RecordingDispatcher {
    private final CopyOnWriteArrayList<String> commands = new CopyOnWriteArrayList<>();

    public void dispatch(String command) {
        if (command == null) throw new NullPointerException("command must not be null");
        commands.add(command);
    }

    public List<String> commands() {
        return List.copyOf(commands);
    }

    public void clear() {
        commands.clear();
    }
}
