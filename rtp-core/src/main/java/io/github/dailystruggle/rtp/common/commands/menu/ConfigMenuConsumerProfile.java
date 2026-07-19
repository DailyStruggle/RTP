package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.menu.MenuConsumerProfile;
import io.github.dailystruggle.rtp.api.menu.YamlCommentLookup;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;

import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@link MenuConsumerProfile} for the {@code /rtp config} menu consumer
 * (CHECKLIST-generalized-menu.md item 2.4).
 *
 * <p>Two concerns:
 *
 * <ul>
 *   <li><b>Suggest prefix.</b> Returns {@code "/rtp config <file> <key>:"}
 *       matching {@code CONFIG_COMMAND_SPEC §2.4}. The path supplied to
 *       {@link #suggestPrefix} starts at the {@code rtp} root; the {@code config}
 *       segment is asserted (the profile is meaningless without it). The file
 *       name is the second path segment when present; the parameter name from
 *       the reflector is the {@code <key>} payload.</li>
 *   <li><b>Comment lookup.</b> Resolves the YAML block comment via the in-house
 *       substrate's {@link RtpYamlSection#getComment(String)}, surfaced through
 *       a caller-supplied resolver that maps a file basename (e.g.
 *       {@code "performance"}) to its loaded {@link RtpYamlSection}. The
 *       resolver indirection keeps {@code rtp-core} free of a direct
 *       {@code Configs}-singleton coupling and makes the profile trivially
 *       testable with a fake.</li>
 * </ul>
 *
 * <p>The block-comment preservation guarantee comes from ADR-042; ADR-044 §4
 * defines this profile's contract.
 */
public final class ConfigMenuConsumerProfile implements MenuConsumerProfile {

    private final Function<String, RtpYamlSection> sectionResolver;
    private final YamlCommentLookup commentLookup;

    /**
     * Constructs a profile backed by the given section resolver.
     *
     * @param sectionResolver maps a config-file basename without {@code .yml}
     *                        (e.g. {@code "performance"}, {@code "config"}) to the
     *                        loaded {@link RtpYamlSection} root, or returns
     *                        {@code null} when no such file is registered.
     *                        Never {@code null}; the resolver itself may map
     *                        to {@code null}.
     */
    public ConfigMenuConsumerProfile(Function<String, RtpYamlSection> sectionResolver) {
        this.sectionResolver = Objects.requireNonNull(sectionResolver, "sectionResolver");
        this.commentLookup = (file, key) -> {
            if (file == null || file.isEmpty() || key == null || key.isEmpty()) {
                return Optional.empty();
            }
            RtpYamlSection root;
            try {
                root = sectionResolver.apply(file);
            } catch (RuntimeException ignored) {
                return Optional.empty();
            }
            if (root == null) return Optional.empty();
            String raw;
            try {
                raw = root.getComment(key);
            } catch (RuntimeException ignored) {
                return Optional.empty();
            }
            if (raw == null || raw.isEmpty()) return Optional.empty();
            return Optional.of(stripCommentMarkers(raw));
        };
    }

    @Override
    public String suggestPrefix(Deque<String> commandPath, String parameterName) {
        Objects.requireNonNull(commandPath, "commandPath");
        Objects.requireNonNull(parameterName, "parameterName");
        // Path layout from the reflector for /rtp config:
        //   [ "rtp" ] (root)
        //   [ "rtp", "config" ]
        //   [ "rtp", "config", "<file>" ]
        // For a parameter under /rtp config <file>, we want:
        //   "/rtp config <file> <key>="
        // For a parameter directly under /rtp config (file selector itself),
        // we degrade to:
        //   "/rtp config <key>="
        // Separator is '=' to match commands-api parameter parsing (which
        // accepts only '=' for <param>=<value> pairs). The earlier ':' form
        // was a stale carry-over from a pre-= commands-api revision.
        StringBuilder sb = new StringBuilder("/");
        boolean first = true;
        for (String segment : commandPath) {
            if (segment == null || segment.isEmpty()) continue;
            if (!first) sb.append(' ');
            sb.append(segment);
            first = false;
        }
        if (!first) sb.append(' ');
        sb.append(parameterName).append('=');
        return sb.toString();
    }

    @Override
    public YamlCommentLookup commentLookup() {
        return commentLookup;
    }

    /**
     * Visible for tests: expose the caller-supplied resolver. Production
     * callers should not need this.
     *
     * @return the section resolver supplied at construction time
     */
    public Function<String, RtpYamlSection> sectionResolver() {
        return sectionResolver;
    }

    /**
     * Trim the leading {@code #} (and one optional space) from each line of a
     * raw block comment, joining with {@code \n}. The substrate already joins
     * multi-line comments with {@code \n} per {@code RtpYamlSection#getComment}.
     */
    private static String stripCommentMarkers(String raw) {
        String[] lines = raw.split("\\R", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("#")) {
                trimmed = trimmed.substring(1);
                if (trimmed.startsWith(" ")) trimmed = trimmed.substring(1);
            }
            if (i > 0) sb.append('\n');
            sb.append(trimmed);
        }
        // Comments routinely document color codes (e.g. "use &e/&6") as
        // examples. The menu renderers run hover text through the standard
        // &-to-§ color translation, which would otherwise paint the description
        // or swallow the example codes. Escape them so they render literally.
        return io.github.dailystruggle.rtp.common.text.LegacyColorStrip.escape(sb.toString());
    }
}
