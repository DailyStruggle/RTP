package io.github.dailystruggle.rtp.common.commands.menu;

import java.util.LinkedHashMap;

/**
 * In-memory record of one player's pending config edits for a single file (ADR-050).
 */
final class Cart {
    final String fileName;
    final LinkedHashMap<String, String> entries = new LinkedHashMap<>();

    Cart(String fileName) {
        this.fileName = fileName;
    }
}
