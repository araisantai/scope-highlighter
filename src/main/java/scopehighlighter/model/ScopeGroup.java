package scopehighlighter.model;

import burp.api.montoya.core.HighlightColor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One highlight group: a title, a Burp highlight color, a match mode and the
 * endpoint list (patterns) the user pasted for this group.
 */
public final class ScopeGroup {

    private String name;
    private HighlightColor color;
    private MatchMode mode;
    private boolean enabled;
    private final List<String> patterns = new ArrayList<>();

    public ScopeGroup(String name, HighlightColor color, MatchMode mode, boolean enabled, List<String> patterns) {
        this.name = name;
        this.color = color == null ? HighlightColor.RED : color;
        this.mode = mode == null ? MatchMode.PREFIX : mode;
        this.enabled = enabled;
        if (patterns != null) {
            this.patterns.addAll(patterns);
        }
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public HighlightColor color() {
        return color;
    }

    public void setColor(HighlightColor color) {
        this.color = color;
    }

    public MatchMode mode() {
        return mode;
    }

    public void setMode(MatchMode mode) {
        this.mode = mode;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> patterns() {
        return Collections.unmodifiableList(patterns);
    }

    public void setPatterns(List<String> newPatterns) {
        patterns.clear();
        if (newPatterns != null) {
            patterns.addAll(newPatterns);
        }
    }

    public int patternCount() {
        return patterns.size();
    }

    /**
     * Returns the first pattern of this group that matches the given request
     * URL, or empty when the group does not match.
     *
     * Matching is case-insensitive. Three shapes are tried per URL:
     *   full     https://host:port/path?query
     *   bare     host:port/path?query   (scheme stripped)
     *   path     /path?query
     */
    public Optional<String> match(String url) {
        if (!enabled || url == null || url.isEmpty()) {
            return Optional.empty();
        }
        String full = url;
        String bare = stripScheme(url);
        String path = extractPath(url);

        for (String rawPattern : patterns) {
            String pattern = rawPattern.trim();
            if (pattern.isEmpty()) {
                continue;
            }
            if (matches(pattern, full, bare, path)) {
                return Optional.of(rawPattern);
            }
        }
        return Optional.empty();
    }

    private boolean matches(String pattern, String full, String bare, String path) {
        String p = pattern.toLowerCase(Locale.ROOT);
        switch (mode) {
            case PREFIX:
                if (p.startsWith("/")) {
                    return prefixMatch(path, p);
                }
                if (p.startsWith("http://") || p.startsWith("https://")) {
                    return prefixMatch(full, p);
                }
                return prefixMatch(bare, p);
            case CONTAINS:
                return full.toLowerCase(Locale.ROOT).contains(p)
                        || bare.toLowerCase(Locale.ROOT).contains(p);
            case REGEX:
            default:
                try {
                    return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(full).find()
                            || Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(bare).find();
                } catch (Exception e) {
                    // invalid regex pattern: never matches, never throws at request time
                    return false;
                }
        }
    }

    /**
     * Prefix match on a URL segment boundary: "/admin/career" matches
     * "/admin/career" and "/admin/career/jobs?id=1" but NOT "/admin/careerx".
     * A pattern already ending in '/' matches everything below it.
     */
    private static boolean prefixMatch(String candidate, String lowerPattern) {
        String lowerCandidate = candidate.toLowerCase(Locale.ROOT);
        if (!lowerCandidate.startsWith(lowerPattern)) {
            return false;
        }
        if (lowerPattern.endsWith("/") || lowerCandidate.length() == lowerPattern.length()) {
            return true;
        }
        char boundary = lowerCandidate.charAt(lowerPattern.length());
        return boundary == '/' || boundary == '?' || boundary == '#';
    }

    private static String stripScheme(String url) {
        int idx = url.indexOf("://");
        return idx >= 0 ? url.substring(idx + 3) : url;
    }

    private static String extractPath(String url) {
        int schemeIdx = url.indexOf("://");
        int start = schemeIdx >= 0 ? schemeIdx + 3 : 0;
        int slash = url.indexOf('/', start);
        return slash >= 0 ? url.substring(slash) : "/";
    }
}
