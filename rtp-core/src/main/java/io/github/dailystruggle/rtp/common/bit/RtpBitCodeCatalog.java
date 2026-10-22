package io.github.dailystruggle.rtp.common.bit;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitCodeNamespace;
import io.github.dailystruggle.bit.BitCodeRegistry;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Deterministic documentation renderers and class-file discovery for the RTP BIT catalog. */
public final class RtpBitCodeCatalog {
    private static final String BIT_CODE_DESCRIPTOR = "Lio/github/dailystruggle/bit/BitCode;";

    private RtpBitCodeCatalog() {}

    /** Pretty-prints the registry's versioned JSON export without changing its data model. */
    public static String json(BitCodeRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");
        return prettyJson(registry.toCatalogJson());
    }

    /** Renders a Markdown reference from the same ordered namespace and code records as JSON. */
    public static String markdown(BitCodeRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");
        StringBuilder markdown = new StringBuilder("# BIT Code Catalog\n\n")
                .append("Generated from the registered BIT catalog. IDs and keys are stable; retired codes are not reused.\n\n")
                .append("## Namespaces\n\n")
                .append("| Prefix | ID range |\n|---|---:|\n");
        for (BitCodeNamespace namespace : registry.namespaces()) {
            markdown.append("| `").append(markdownCell(namespace.prefix())).append("` | ")
                    .append(namespace.firstId()).append("-").append(namespace.lastId()).append(" |\n");
        }
        markdown.append("\n## Codes\n\n")
                .append("| ID | Code | Severity | Tier | Subsystem | Message key | Remediation |\n")
                .append("|---:|---|---|---|---|---|---|\n");
        for (BitCode code : registry.codes()) {
            markdown.append("| ").append(code.id())
                    .append(" | `").append(markdownCell(code.key())).append('`')
                    .append(" | ").append(code.defaultSeverity().name())
                    .append(" | ").append(code.tier().name())
                    .append(" | ").append(markdownCell(code.subsystem()))
                    .append(" | ").append(code.messageKey().isBlank() ? "-" : markdownCell(code.messageKey()))
                    .append(" | ").append(markdownCell(code.remediation())).append(" |\n");
        }
        return markdown.toString();
    }

    /** Finds compiled classes declaring at least one static field whose type is {@link BitCode}. */
    public static List<Class<?>> declaredOwners(Path classesRoot, ClassLoader loader) throws IOException {
        Objects.requireNonNull(classesRoot, "classesRoot must not be null");
        Objects.requireNonNull(loader, "loader must not be null");
        if (!Files.isDirectory(classesRoot)) {
            throw new IllegalArgumentException("class output directory does not exist: " + classesRoot);
        }
        List<Class<?>> owners = new ArrayList<>();
        try (var files = Files.walk(classesRoot)) {
            for (Path classFile : files.filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                String fileName = classFile.getFileName().toString();
                if (fileName.equals("module-info.class") || fileName.equals("package-info.class")) continue;
                byte[] bytes = Files.readAllBytes(classFile);
                if (!new String(bytes, StandardCharsets.ISO_8859_1).contains(BIT_CODE_DESCRIPTOR)) continue;
                String className = classesRoot.relativize(classFile).toString()
                        .replace(classFile.getFileSystem().getSeparator(), ".")
                        .replaceFirst("\\.class$", "");
                Class<?> candidate;
                try {
                    candidate = Class.forName(className, false, loader);
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("BIT owner class could not be loaded: " + className, e);
                }
                if (declaresStaticBitCode(candidate)) owners.add(candidate);
            }
        }
        owners.sort(Comparator.comparing(Class::getName));
        return List.copyOf(owners);
    }

    private static boolean declaresStaticBitCode(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (field.getType() == BitCode.class && Modifier.isStatic(field.getModifiers())) return true;
        }
        return false;
    }

    private static String prettyJson(String compact) {
        StringBuilder pretty = new StringBuilder(compact.length() + 64);
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < compact.length(); index++) {
            char character = compact.charAt(index);
            if (inString) {
                pretty.append(character);
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') inString = false;
                continue;
            }
            switch (character) {
                case '"' -> {
                    inString = true;
                    pretty.append(character);
                }
                case '{', '[' -> {
                    pretty.append(character).append('\n');
                    depth++;
                    appendIndent(pretty, depth);
                }
                case '}', ']' -> {
                    pretty.append('\n');
                    depth--;
                    appendIndent(pretty, depth);
                    pretty.append(character);
                }
                case ',' -> pretty.append(character).append('\n').append("  ".repeat(depth));
                case ':' -> pretty.append(": ");
                default -> pretty.append(character);
            }
        }
        return pretty.append('\n').toString();
    }

    private static void appendIndent(StringBuilder output, int depth) {
        output.append("  ".repeat(depth));
    }

    private static String markdownCell(String value) {
        return value.replace("|", "\\|").replace("\r", "").replace("\n", "<br>");
    }
}
