package io.github.dailystruggle.rtp.common.commands.docs;

import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-function Markdown-to-MenuModel lowering parser (ADR-045, ADR-104).
 *
 * <p>Converts bundled Markdown documentation into structured {@link MenuModel} book pages
 * with dark-contrast colors, heading-driven page breaks, code fences, lists, and interactive links.
 * Contains zero platform imports and zero external dependencies.
 */
public final class MarkdownToMenuModel {

    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^]]+)]\\(([^)]+)\\)");
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*([^*]+)\\*\\*");

    private MarkdownToMenuModel() {
    }

    /**
     * Lowers a Markdown source string into a {@link MenuModel}.
     *
     * @param title          document title
     * @param markdownSource raw Markdown text
     * @param options        lowering options (wrapping, caps)
     * @return structured {@link MenuModel}
     */
    public static MenuModel lower(String title, String markdownSource, DocsLoweringOptions options) {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(options, "options");
        if (markdownSource == null || markdownSource.isBlank()) {
            return new MenuModel(title, List.of(new MenuPage(List.of(
                    MenuLine.of(new MenuFragment("&7(empty document)", null, null))
            ))));
        }

        String[] rawLines = markdownSource.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        List<MenuPage> pages = new ArrayList<>();
        List<MenuLine> currentLines = new ArrayList<>();

        boolean inCodeBlock = false;

        for (String line : rawLines) {
            String trimmed = line.trim();

            // Code fence handling
            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                continue;
            }

            if (inCodeBlock) {
                // Code lines: truncated if over cap, styled with &8
                String codeContent = line;
                String hover = null;
                if (codeContent.length() > options.maxCodeLineWidth()) {
                    hover = codeContent;
                    codeContent = codeContent.substring(0, options.maxCodeLineWidth() - 1) + "\u2026";
                }
                currentLines.add(MenuLine.of(new MenuFragment("&8  " + codeContent, hover, null)));
                continue;
            }

            // ATX Headings: # and ## start a new page
            if (trimmed.startsWith("# ") || trimmed.startsWith("## ")) {
                if (!currentLines.isEmpty()) {
                    pages.add(new MenuPage(List.copyOf(currentLines)));
                    currentLines.clear();
                }
                String headingText = trimmed.replaceFirst("^#+\\s*", "");
                currentLines.add(MenuLine.of(new MenuFragment("&1&l" + headingText, null, null)));
                currentLines.add(new MenuLine(List.of())); // spacer
                continue;
            }

            // Subheadings: ###, #### stay in current page
            if (trimmed.startsWith("### ") || trimmed.startsWith("#### ")) {
                String subText = trimmed.replaceFirst("^#+\\s*", "");
                currentLines.add(MenuLine.of(new MenuFragment("&5&l" + subText, null, null)));
                continue;
            }

            // Horizontal rule
            if (trimmed.equals("---") || trimmed.equals("***") || trimmed.equals("___")) {
                currentLines.add(MenuLine.of(new MenuFragment("&8\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500", null, null)));
                continue;
            }

            // Blockquote
            if (trimmed.startsWith(">")) {
                String quoteText = trimmed.substring(1).trim();
                currentLines.add(MenuLine.of(new MenuFragment("&7\u258e &8" + quoteText, null, null)));
                continue;
            }

            // Bullet list item
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                String itemText = trimmed.substring(2).trim();
                List<MenuFragment> frags = parseInlineFragments("&0\u2022 ", itemText);
                currentLines.add(new MenuLine(frags));
                continue;
            }

            // Numbered list item
            if (trimmed.matches("^\\d+\\.\\s+.*")) {
                int dotIdx = trimmed.indexOf('.');
                String num = trimmed.substring(0, dotIdx + 1);
                String rest = trimmed.substring(dotIdx + 1).trim();
                List<MenuFragment> frags = parseInlineFragments("&0" + num + " ", rest);
                currentLines.add(new MenuLine(frags));
                continue;
            }

            // Empty line
            if (trimmed.isEmpty()) {
                if (!currentLines.isEmpty() && !currentLines.get(currentLines.size() - 1).fragments().isEmpty()) {
                    currentLines.add(new MenuLine(List.of()));
                }
                continue;
            }

            // Regular paragraph line (soft-wrapped if necessary)
            // If the line contains links, do not soft-wrap across link syntax boundaries
            if (trimmed.contains("[") && trimmed.contains("](")) {
                List<MenuFragment> frags = parseInlineFragments("", trimmed);
                currentLines.add(new MenuLine(frags));
            } else {
                List<String> wrapped = softWrap(trimmed, options.maxLineWidth());
                for (String w : wrapped) {
                    List<MenuFragment> frags = parseInlineFragments("", w);
                    currentLines.add(new MenuLine(frags));
                }
            }
        }

        if (!currentLines.isEmpty()) {
            pages.add(new MenuPage(List.copyOf(currentLines)));
        }

        if (pages.isEmpty()) {
            pages.add(new MenuPage(List.of(MenuLine.of(new MenuFragment("&7(empty document)", null, null)))));
        }

        return new MenuModel(title, List.copyOf(pages));
    }

    /**
     * Parses inline links, bold, and codes into {@link MenuFragment} items.
     */
    private static List<MenuFragment> parseInlineFragments(String prefix, String text) {
        List<MenuFragment> frags = new ArrayList<>();
        if (prefix != null && !prefix.isEmpty()) {
            frags.add(new MenuFragment(prefix, null, null));
        }

        Matcher matcher = LINK_PATTERN.matcher(text);
        int lastIndex = 0;

        while (matcher.find()) {
            if (matcher.start() > lastIndex) {
                String before = text.substring(lastIndex, matcher.start());
                frags.addAll(formatBasicText(before));
            }
            String linkText = matcher.group(1);
            String url = matcher.group(2);

            MenuAction action;
            if (url.startsWith("http://") || url.startsWith("https://")) {
                try {
                    action = new MenuAction.OpenExternalUrl(URI.create(url));
                } catch (Exception ignored) {
                    action = null;
                }
            } else if (url.endsWith(".md") || url.contains(".md#")) {
                String cleanPath = url.replaceAll("#.*$", "");
                action = new MenuAction.RunRtpCommand(new String[]{"docs", cleanPath});
            } else {
                action = null;
            }

            frags.add(new MenuFragment("&1" + linkText, url, action));
            lastIndex = matcher.end();
        }

        if (lastIndex < text.length()) {
            frags.addAll(formatBasicText(text.substring(lastIndex)));
        }

        return frags.isEmpty() ? List.of(new MenuFragment(text, null, null)) : frags;
    }

    private static List<MenuFragment> formatBasicText(String text) {
        String cleaned = BOLD_PATTERN.matcher(text).replaceAll("&0&l$1&r&0");
        cleaned = INLINE_CODE_PATTERN.matcher(cleaned).replaceAll("&8[$1]&r&0");
        return List.of(new MenuFragment("&0" + cleaned, null, null));
    }

    private static List<String> softWrap(String text, int maxLine) {
        if (text.length() <= maxLine) {
            return Collections.singletonList(text);
        }
        List<String> result = new ArrayList<>();
        String[] words = text.split("\\s+");
        StringBuilder current = new StringBuilder();

        for (String word : words) {
            if (current.length() + word.length() + (current.isEmpty() ? 0 : 1) > maxLine) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(word);
        }
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }
}
