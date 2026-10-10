package scopemanager.model;

import burp.api.montoya.core.HighlightColor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One highlight group: a title, a Burp highlight color, a match mode and the
 * endpoint list (patterns) the user pasted for this group.
 */
public final class ScopeGroup {

    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\{\\{[A-Za-z_][A-Za-z0-9_]*\\}\\}|\\{[A-Za-z_][A-Za-z0-9_]*\\}");

    private String name;
    private HighlightColor color;
    private MatchMode mode;
    private boolean enabled;
    private final List<String> patterns = new ArrayList<>();
    private final Map<String, TemplatePatterns> templates = new HashMap<>();

    private record TemplatePatterns(Pattern full, Pattern withoutQuery) {
        boolean matches(String candidate) {
            Pattern selected = candidate.indexOf('?') < 0 && withoutQuery != null
                    ? withoutQuery : full;
            return selected.matcher(candidate).find();
        }
    }

    public ScopeGroup(String name, HighlightColor color, MatchMode mode, boolean enabled, List<String> patterns) {
        this.name = name;
        this.color = color == null ? HighlightColor.RED : color;
        this.mode = mode == null ? MatchMode.PREFIX : mode;
        this.enabled = enabled;
        if (patterns != null) {
            this.patterns.addAll(patterns);
        }
        rebuildTemplates();
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
        rebuildTemplates();
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
        rebuildTemplates();
    }

    public int patternCount() {
        return patterns.size();
    }

    /** Compile user-friendly URL placeholders once, not for every HTTP request. */
    private void rebuildTemplates() {
        templates.clear();
        if (mode == MatchMode.REGEX) {
            return;
        }
        for (String raw : patterns) {
            String template = raw.trim();
            if (!PLACEHOLDER.matcher(template).find()) {
                continue;
            }
            int queryStart = template.indexOf('?');
            Pattern withoutQuery = queryStart < 0 ? null
                    : compileTemplate(template.substring(0, queryStart), true);
            templates.put(template, new TemplatePatterns(compileTemplate(template, false), withoutQuery));
        }
    }

    private Pattern compileTemplate(String template, boolean pathOnly) {
        Matcher placeholders = PLACEHOLDER.matcher(template);
        StringBuilder regex = new StringBuilder(mode == MatchMode.PREFIX ? "^" : "");
        int previous = 0;
        while (placeholders.find()) {
            regex.append(Pattern.quote(template.substring(previous, placeholders.start())));
            boolean inQuery = template.substring(0, placeholders.start()).indexOf('?') >= 0;
            regex.append(inQuery ? "[^&#]+" : "[^/?#]+");
            previous = placeholders.end();
        }
        regex.append(Pattern.quote(template.substring(previous)));
        if (mode == MatchMode.PREFIX && !template.endsWith("/")) {
            // A queryless request can match the path of a URL template,
            // but a path placeholder must not swallow another segment.
            regex.append(pathOnly ? "(?=$|#)" : "(?=$|[?#])");
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
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
        TemplatePatterns template = templates.get(pattern);
        switch (mode) {
            case PREFIX:
                if (p.startsWith("/")) {
                    return template == null ? prefixMatch(path, p) : template.matches(path);
                }
                if (p.startsWith("http://") || p.startsWith("https://")) {
                    return template == null ? prefixMatch(full, p) : template.matches(full);
                }
                return template == null ? prefixMatch(bare, p) : template.matches(bare);
            case CONTAINS:
                if (template != null) {
                    return template.matches(full) || template.matches(bare);
                }
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
