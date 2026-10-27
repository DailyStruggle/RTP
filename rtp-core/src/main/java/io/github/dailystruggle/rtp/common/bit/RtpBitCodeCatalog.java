package io.github.dailystruggle.rtp.common.bit;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitCodeNamespace;
import io.github.dailystruggle.bit.BitCodeRegistry;
import io.github.dailystruggle.bit.BitComponent;
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
    private static final String BIT_COMPONENT_DESCRIPTOR = "Lio/github/dailystruggle/bit/BitComponent;";

    private RtpBitCodeCatalog() {}

    /** Pretty-prints the registry's versioned JSON export without changing its data model. */
    public static String json(BitCodeRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");
        return prettyJson(registry.toCatalogJson());
    }

    /** Renders a Markdown reference from the same ordered namespace and code records as JSON. */
    public static String markdown(BitCodeRegistry registry) {
        return markdown(registry, null, "BIT Code Catalog");
    }

    /** Renders one addon's namespace as a separate Markdown catalog. */
    public static String markdown(BitCodeRegistry registry, String namespaceFilter, String title) {
        Objects.requireNonNull(registry, "registry must not be null");
        String prefix = namespaceFilter == null || namespaceFilter.isBlank()
                ? null : namespaceFilter.trim().toUpperCase(java.util.Locale.ROOT);
        String heading = title == null || title.isBlank() ? "BIT Code Catalog" : title.trim();
        StringBuilder markdown = new StringBuilder("# ").append(markdownCell(heading)).append("\n\n")
                .append("Generated from the registered BIT catalog. IDs and keys are stable; retired codes are not reused.\n\n");
        List<BitCodeNamespace> namespaces = registry.namespaces().stream()
                .filter(namespace -> prefix == null || namespace.prefix().equals(prefix)).toList();
        if (prefix == null) {
            markdown.append("## Namespaces\n\n")
                    .append("| Prefix |\n|---|\n");
            for (BitCodeNamespace namespace : namespaces) {
                markdown.append("| `").append(markdownCell(namespace.prefix())).append("` |\n");
            }
        }
        for (BitCodeNamespace namespace : namespaces) {
            markdown.append("\n## `").append(markdownCell(namespace.prefix())).append("`\n\n")
                    .append("| ID | Code | Severity | Tier | Subsystem | Message key | Remediation |\n")
                    .append("|---:|---|---|---|---|---|---|\n");
            for (BitCode code : registry.codes()) {
                if (!code.namespace().equals(namespace.prefix())) continue;
                markdown.append("| ").append(code.id())
                        .append(" | `").append(markdownCell(code.key())).append('`')
                        .append(" | ").append(code.defaultSeverity().name())
                        .append(" | ").append(code.tier().name())
                        .append(" | ").append(markdownCell(code.subsystem()))
                        .append(" | ").append(code.messageKey().isBlank() ? "-" : markdownCell(code.messageKey()))
                        .append(" | ").append(markdownCell(code.remediation())).append(" |\n");
            }
        }
        List<io.github.dailystruggle.bit.RetiredBitCode> retired = registry.retired().stream()
                .filter(code -> prefix == null || code.namespace().equals(prefix)).toList();
        if (!retired.isEmpty()) {
            markdown.append("\n## Retired\n\n")
                    .append("| Namespace | ID | Code | Retired since |\n|---|---:|---|---|\n");
            for (var code : retired) {
                markdown.append("| `").append(markdownCell(code.namespace())).append("` | ")
                        .append(code.id()).append(" | `").append(markdownCell(code.key())).append("` | ")
                        .append(markdownCell(code.since())).append(" |\n");
            }
        }
        return markdown.toString();
    }

    /** Finds compiled classes declaring a static {@link BitCode} or public static final {@link BitComponent}. */
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
                String bytecode = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
                if (fileName.equals("module-info.class") || fileName.equals("package-info.class")
                        || !(bytecode.contains(BIT_CODE_DESCRIPTOR) || bytecode.contains(BIT_COMPONENT_DESCRIPTOR))) continue;
                String classPath = classesRoot.relativize(classFile).toString()
                        .replace(classFile.getFileSystem().getSeparator(), ".");
                String className = classPath.substring(0, classPath.length() - ".class".length());
                Class<?> candidate;
                try {
                    candidate = Class.forName(className, false, loader);
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("BIT owner class could not be loaded: " + className, e);
                }
                if (declaresOwnerField(candidate)) owners.add(candidate);
            }
        }
        owners.sort(Comparator.comparing(Class::getName));
        return List.copyOf(owners);
    }

    private static boolean declaresOwnerField(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() == BitCode.class && Modifier.isStatic(modifiers)) return true;
            if (field.getType() == BitComponent.class && Modifier.isPublic(modifiers)
                    && Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)) return true;
        }
        return false;
    }

    private static String prettyJson(String compact) {
        StringBuilder pretty = new StringBuilder(compact.length() + 64);
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int index = 0;
        while (index < compact.length()) {
            char character = compact.charAt(index);
            if (inString) {
                pretty.append(character);
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') inString = false;
                index++;
                continue;
            }
            switch (character) {
                case '"' -> {
                    inString = true;
                    pretty.append(character);
                }
                case '{', '[' -> {
                    char close = character == '{' ? '}' : ']';
                    if (index + 1 < compact.length() && compact.charAt(index + 1) == close) {
                        pretty.append(character).append(close);
                        index += 2;
                        continue;
                    }
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
            index++;
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
