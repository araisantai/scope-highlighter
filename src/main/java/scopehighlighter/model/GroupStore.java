package scopehighlighter.model;

import burp.api.montoya.core.HighlightColor;
import burp.api.montoya.persistence.PersistedObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Holds all scope groups plus the extension settings, survives Burp restarts
 * via the Montoya persistence API, and resolves which group matches a URL.
 *
 * Storage format (single persisted string, one record per line):
 *   v1
 *   g|&lt;b64 name&gt;|COLOR|MODE|&lt;0|1&gt;|&lt;b64 pattern&gt;|&lt;b64 pattern&gt;|...
 *   s|&lt;autoHighlight 0|1&gt;|&lt;writeNotes 0|1&gt;
 */
public final class GroupStore {

    private static final String KEY_GROUPS = "scopehighlighter.state.v1";
    private static final String KEY_AUTO = "scopehighlighter.auto_highlight";
    private static final String KEY_NOTES = "scopehighlighter.write_notes";

    public static final class Match {
        private final ScopeGroup group;
        private final String pattern;

        public Match(ScopeGroup group, String pattern) {
            this.group = group;
            this.pattern = pattern;
        }

        public ScopeGroup group() {
            return group;
        }

        public String pattern() {
            return pattern;
        }
    }

    private final List<ScopeGroup> groups = new ArrayList<>();
    private volatile boolean autoHighlight = true;
    private volatile boolean writeNotes = true;
    private PersistedObject persistedData;

    // ------------------------------------------------------------------
    // persistence
    // ------------------------------------------------------------------

    public synchronized void attach(PersistedObject data) {
        this.persistedData = data;
        String saved = data.getString(KEY_GROUPS);
        if (saved != null && !saved.isEmpty()) {
            loadFromString(saved);
        }
        Boolean auto = data.getBoolean(KEY_AUTO);
        if (auto != null) {
            autoHighlight = auto;
        }
        Boolean notes = data.getBoolean(KEY_NOTES);
        if (notes != null) {
            writeNotes = notes;
        }
    }

    private void save() {
        if (persistedData != null) {
            persistedData.setString(KEY_GROUPS, saveToString());
            persistedData.setBoolean(KEY_AUTO, autoHighlight);
            persistedData.setBoolean(KEY_NOTES, writeNotes);
        }
    }

    public synchronized String saveToString() {
        StringBuilder sb = new StringBuilder("v1\n");
        for (ScopeGroup g : groups) {
            sb.append("g|")
                    .append(b64(g.name()))
                    .append('|').append(g.color().name())
                    .append('|').append(g.mode().name())
                    .append('|').append(g.enabled() ? "1" : "0");
            for (String pattern : g.patterns()) {
                sb.append('|').append(b64(pattern));
            }
            sb.append('\n');
        }
        sb.append("s|").append(autoHighlight ? "1" : "0")
                .append('|').append(writeNotes ? "1" : "0")
                .append('\n');
        return sb.toString();
    }

    public synchronized void loadFromString(String data) {
        groups.clear();
        for (String line : data.split("\n")) {
            if (line.startsWith("g|")) {
                String[] parts = line.split("\\|", -1);
                if (parts.length < 5) {
                    continue;
                }
                String name = unb64(parts[1]);
                HighlightColor color = parseColor(parts[2]);
                MatchMode mode = parseMode(parts[3]);
                boolean enabled = "1".equals(parts[4]);
                List<String> patterns = new ArrayList<>();
                for (int i = 5; i < parts.length; i++) {
                    String pattern = unb64(parts[i]);
                    if (!pattern.isEmpty()) {
                        patterns.add(pattern);
                    }
                }
                groups.add(new ScopeGroup(name, color, mode, enabled, patterns));
            } else if (line.startsWith("s|")) {
                String[] parts = line.split("\\|", -1);
                if (parts.length > 1) {
                    autoHighlight = "1".equals(parts[1]);
                }
                if (parts.length > 2) {
                    writeNotes = "1".equals(parts[2]);
                }
            }
        }
    }

    private static String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static HighlightColor parseColor(String name) {
        try {
            return HighlightColor.valueOf(name);
        } catch (Exception e) {
            return HighlightColor.RED;
        }
    }

    private static MatchMode parseMode(String name) {
        try {
            return MatchMode.valueOf(name);
        } catch (Exception e) {
            return MatchMode.PREFIX;
        }
    }

    // ------------------------------------------------------------------
    // group access
    // ------------------------------------------------------------------

    public synchronized List<ScopeGroup> groups() {
        return new ArrayList<>(groups);
    }

    public synchronized int groupCount() {
        return groups.size();
    }

    public synchronized int endpointCount() {
        int total = 0;
        for (ScopeGroup g : groups) {
            total += g.patternCount();
        }
        return total;
    }

    public synchronized void addGroup(ScopeGroup group) {
        groups.add(group);
        save();
    }

    public synchronized void updateGroup(int index, ScopeGroup group) {
        if (index >= 0 && index < groups.size()) {
            groups.set(index, group);
            save();
        }
    }

    public synchronized void removeGroup(int index) {
        if (index >= 0 && index < groups.size()) {
            groups.remove(index);
            save();
        }
    }

    public synchronized void move(int index, int delta) {
        int target = index + delta;
        if (index < 0 || index >= groups.size() || target < 0 || target >= groups.size()) {
            return;
        }
        ScopeGroup group = groups.remove(index);
        groups.add(target, group);
        save();
    }

    /**
     * First matching group wins, so table order defines priority.
     */
    public synchronized Optional<Match> findMatch(String url) {
        for (ScopeGroup group : groups) {
            Optional<String> pattern = group.match(url);
            if (pattern.isPresent()) {
                return Optional.of(new Match(group, pattern.get()));
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // settings
    // ------------------------------------------------------------------

    public boolean isAutoHighlightEnabled() {
        return autoHighlight;
    }

    public synchronized void setAutoHighlightEnabled(boolean enabled) {
        autoHighlight = enabled;
        save();
    }

    public boolean isWriteNotesEnabled() {
        return writeNotes;
    }

    public synchronized void setWriteNotesEnabled(boolean enabled) {
        writeNotes = enabled;
        save();
    }
}
