package scopemanager.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses endpoint lists pasted from Excel (or any text source).
 *
 * Accepted separators: newlines, carriage returns, tabs (Excel columns),
 * and commas (but only in "simple" lines that contain no whitespace and no
 * '{', so regex patterns like "admin/.*{1,3}" are never split).
 *
 * Lines that are empty, or start with '#' or '//', are ignored. Wrapping
 * quotes are stripped. Duplicates are removed, order preserved.
 */
public final class EndpointParser {

    private EndpointParser() {
    }

    public static List<String> parse(String pastedText) {
        Set<String> result = new LinkedHashSet<>();
        if (pastedText == null) {
            return new ArrayList<>();
        }

        String normalized = pastedText
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\t', '\n');

        for (String rawLine : normalized.split("\n")) {
            for (String chunk : splitCommasIfNeeded(rawLine)) {
                String cleaned = clean(chunk);
                if (cleaned.isEmpty()) {
                    continue;
                }
                if (cleaned.startsWith("#") || cleaned.startsWith("//")) {
                    continue;
                }
                result.add(cleaned);
            }
        }
        return new ArrayList<>(result);
    }

    private static List<String> splitCommasIfNeeded(String line) {
        List<String> parts = new ArrayList<>();
        String trimmed = line.trim();
        // Commas split values, EXCEPT in lines containing '{' (regex quantifiers
        // like "admin/.*{1,3}" must never be split).
        boolean simpleLine = !trimmed.isEmpty()
                && trimmed.indexOf('{') < 0
                && trimmed.indexOf(',') >= 0;
        if (simpleLine) {
            for (String piece : trimmed.split(",")) {
                parts.add(piece);
            }
        } else {
            parts.add(line);
        }
        return parts;
    }

    private static String clean(String value) {
        String s = value.trim();
        // strip wrapping quotes coming from spreadsheet exports
        while (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            boolean wrapped = (first == '"' && last == '"') || (first == '\'' && last == '\'');
            if (!wrapped) {
                break;
            }
            s = s.substring(1, s.length() - 1).trim();
        }
        // strip one trailing comma or semicolon left over from tool exports
        while (s.endsWith(",") || s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }
}
