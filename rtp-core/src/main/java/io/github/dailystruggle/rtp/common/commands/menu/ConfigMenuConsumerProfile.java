package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.menu.MenuConsumerProfile;
import io.github.dailystruggle.rtp.api.menu.YamlCommentLookup;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;

import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@link MenuConsumerProfile} for the {@code /rtp config} menu consumer (ADR-044).
 * Generates {@code /rtp config <file> <key>=} suggestion prefixes and resolves YAML comments.
 */
public final class ConfigMenuConsumerProfile implements MenuConsumerProfile {

    private final Function<String, RtpYamlSection> sectionResolver;
    private final YamlCommentLookup commentLookup;

    /**
     * Constructs profile with config section resolver.
     *
     * @param sectionResolver maps file basename without {@code .yml} to loaded {@link RtpYamlSection}
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
        // Formats command path + parameter into "/rtp config [file] <param>="
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
        // &-to-section color translation, which would otherwise paint the description
        // or swallow the example codes. Escape them so they render literally.
        return io.github.dailystruggle.rtp.common.text.LegacyColorStrip.escape(sb.toString());
    }
}
