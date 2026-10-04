package io.github.dailystruggle.rtp.common.commands.docs;

import io.github.dailystruggle.commandsapi.common.CommandParameter;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * Parameter providing dynamic tab-completion values of loaded documentation topics.
 */
public class DocsTopicParameter extends CommandParameter {

    public DocsTopicParameter(String permission, String description) {
        super(permission, description, (uuid, s) -> s != null && !s.isBlank());
    }

    @Override
    public Set<String> values() {
        try {
            Set<String> set = new TreeSet<>();
            for (String key : DocsRegistry.getInstance().getAll().keySet()) {
                set.add(key);
                if (key.endsWith(".md")) {
                    set.add(key.substring(0, key.length() - 3));
                }
            }
            return set;
        } catch (IllegalStateException e) {
            // Pre-initialization fail-closed guard (S-006)
            return Collections.emptySet();
        }
    }
}
