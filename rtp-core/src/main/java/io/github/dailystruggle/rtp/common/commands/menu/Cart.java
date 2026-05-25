package io.github.dailystruggle.rtp.common.commands.menu;

import java.util.LinkedHashMap;

/**
 * In-memory record of one player's pending config edits for a single file.
 * Mutable but always replaced wholesale via the owning cart map; never leaked.
 *
 * <p>Extracted from {@code MenuRedeemSubcommand} as the first step of the
 * ADR-050 menu-package split (see {@code CHECKLIST-concrete-menu-commands.md}).
 * Package-private: only {@code MenuRedeemSubcommand} and the menu leaf
 * classes in this package may hold a reference.
 */
final class Cart {
    final String fileName;
    final LinkedHashMap<String, String> entries = new LinkedHashMap<>();

    Cart(String fileName) {
        this.fileName = fileName;
    }
}
