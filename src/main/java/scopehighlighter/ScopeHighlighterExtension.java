package scopehighlighter;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.HighlightColor;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import scopehighlighter.http.HighlighterHandler;
import scopehighlighter.log.MessageLog;
import scopehighlighter.log.MessageRecord;
import scopehighlighter.log.Source;
import scopehighlighter.model.GroupStore;
import scopehighlighter.model.ScopeGroup;
import scopehighlighter.ui.Host;
import scopehighlighter.ui.ScopeHighlighterTab;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Scope Highlighter - Burp Suite extension (Montoya API).
 *
 * - auto-highlights requests in Burp history per endpoint group
 * - "Scope Highlighter" tab with an Excel-like endpoint grid and per-row
 *   show actions (all / repeater / intruder / history / target)
 * - records every request with its tool of origin, so Repeater and Intruder
 *   requests stay recoverable and are saved inside the Burp project file
 */
public final class ScopeHighlighterExtension implements BurpExtension {

    public static final String TAB_NAME = "Scope Highlighter";

    private MontoyaApi api;
    private GroupStore store;
    private MessageLog messageLog;

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName(TAB_NAME);

        store = new GroupStore();
        store.attach(api.persistence().extensionData());

        messageLog = new MessageLog();
        messageLog.attach(api.persistence().extensionData());
        if (messageLog.size() == 0) {
            seedFromProxyHistory();
        }

        api.http().registerHttpHandler(new HighlighterHandler(api, store, messageLog));
        api.extension().registerUnloadingHandler(() -> messageLog.saveNow());

        SwingUtilities.invokeLater(() -> {
            Host host = new BurpHost();
            ScopeHighlighterTab tab = new ScopeHighlighterTab(store, host);
            // any change to groups/settings re-applies highlights automatically
            store.setChangeListener(() -> {
                SwingUtilities.invokeLater(tab::refreshAll);
                host.reapplyHighlights();
            });
            api.userInterface().registerSuiteTab(TAB_NAME, tab);
        });
    }

    // ------------------------------------------------------------------
    // host: collect recorded requests for the results viewer
    // ------------------------------------------------------------------

    private final class BurpHost implements Host {

        @Override
        public Component applyTheme(Component component) {
            api.userInterface().applyThemeToComponent(component);
            return component;
        }

        @Override
        public void log(String message) {
            api.logging().logToOutput(message);
        }

        @Override
        public List<MessageRecord> collect(Source source, ScopeGroup matcher) {
            List<MessageRecord> out = new ArrayList<>();
            if (source == Source.ALL) {
                Set<String> keys = new HashSet<>();
                for (MessageRecord record : messageLog.entries()) {
                    keys.add(record.key());
                    if (matcher.match(record.url()).isPresent()) {
                        out.add(record);
                    }
                }
                for (MessageRecord record : siteMapRecords()) {
                    if (!keys.contains(record.key()) && matcher.match(record.url()).isPresent()) {
                        out.add(record);
                    }
                }
            } else if (source == Source.TARGET) {
                for (MessageRecord record : siteMapRecords()) {
                    if (matcher.match(record.url()).isPresent()) {
                        out.add(record);
                    }
                }
            } else {
                String label = source.recordLabel();
                for (MessageRecord record : messageLog.entries()) {
                    if (record.isFrom(label) && matcher.match(record.url()).isPresent()) {
                        out.add(record);
                    }
                }
            }
            out.sort(Comparator.comparingLong(MessageRecord::timestamp));
            return out;
        }

        @Override
        public void reapplyHighlights() {
            applyHighlightsToHistory();
        }
    }

    /**
     * Burp's Target tab = site map. Queried live so pre-extension content is
     * included too.
     */
    private List<MessageRecord> siteMapRecords() {
        List<MessageRecord> out = new ArrayList<>();
        try {
            for (HttpRequestResponse entry : api.siteMap().requestResponses()) {
                MessageRecord record = toRecord(entry);
                if (record != null) {
                    out.add(record);
                }
            }
        } catch (Exception e) {
            api.logging().logToError("Scope Highlighter: site map read failed: " + e);
        }
        return out;
    }

    private static MessageRecord toRecord(HttpRequestResponse entry) {
        if (entry == null || entry.request() == null) {
            return null;
        }
        HttpRequest request = entry.request();
        String requestRaw = MessageLog.clampRaw(request.toString());
        String responseRaw = "";
        int status = 0;
        if (entry.hasResponse() && entry.response() != null) {
            responseRaw = MessageLog.clampRaw(entry.response().toString());
            status = entry.response().statusCode();
        }
        return new MessageRecord(0, Source.TARGET.recordLabel(), request.method(), request.url(),
                status, requestRaw.length(), responseRaw.length(), requestRaw, responseRaw);
    }

    // ------------------------------------------------------------------
    // auto-highlighting of existing traffic (no manual button)
    // ------------------------------------------------------------------

    /**
     * Applies the current groups to everything already in the Proxy history:
     * matched entries get their group's color, entries that no longer match
     * get cleaned up (color/note removed) when the note shows one of our own
     * group titles - so removing or changing a group updates history instantly.
     */
    private void applyHighlightsToHistory() {
        Thread worker = new Thread(() -> {
            int colored = 0;
            int cleared = 0;
            try {
                for (ProxyHttpRequestResponse entry : api.proxy().history()) {
                    try {
                        String url = entry.finalRequest().url();
                        Annotations annotations = entry.annotations();
                        Optional<GroupStore.Match> match = store.findMatch(url);
                        if (match.isPresent()) {
                            annotations.setHighlightColor(match.get().group().color());
                            if (store.isWriteNotesEnabled() && !annotations.hasNotes()) {
                                annotations.setNotes(match.get().group().name());
                            }
                            colored++;
                        } else if (annotations.hasHighlightColor()
                                || (annotations.hasNotes() && store.isKnownName(annotations.notes()))) {
                            boolean ours = !annotations.hasNotes() || store.isKnownName(annotations.notes());
                            if (ours) {
                                if (annotations.hasHighlightColor()) {
                                    annotations.setHighlightColor(HighlightColor.NONE);
                                }
                                if (annotations.hasNotes() && store.isKnownName(annotations.notes())) {
                                    annotations.setNotes("");
                                }
                                cleared++;
                            }
                        }
                    } catch (Exception ignored) {
                        // one odd entry must not abort the refresh
                    }
                }
                api.logging().logToOutput("Scope Highlighter: auto-applied highlights ("
                        + colored + " set, " + cleared + " cleared) on proxy history");
            } catch (Exception e) {
                api.logging().logToError("Scope Highlighter: highlight refresh failed: " + e);
            }
        }, "scope-highlighter-apply");
        worker.setDaemon(true);
        worker.start();
    }

    // ------------------------------------------------------------------
    // startup seeding
    // ------------------------------------------------------------------

    /** First run on a project: import the existing proxy history into the log. */
    private void seedFromProxyHistory() {
        try {
            for (ProxyHttpRequestResponse entry : api.proxy().history()) {
                try {
                    String requestRaw = MessageLog.clampRaw(entry.request().toString());
                    String responseRaw = "";
                    int status = 0;
                    if (entry.hasResponse() && entry.response() != null) {
                        responseRaw = MessageLog.clampRaw(entry.response().toString());
                        status = entry.response().statusCode();
                    }
                    long timestamp = entry.time() == null ? 0 : entry.time().toInstant().toEpochMilli();
                    messageLog.record(new MessageRecord(timestamp, "History",
                            entry.finalRequest().method(), entry.finalRequest().url(), status,
                            requestRaw.length(), responseRaw.length(), requestRaw, responseRaw));
                } catch (Exception ignored) {
                    // skip unreadable entries
                }
            }
            api.logging().logToOutput("Scope Highlighter: recorded existing proxy history into the logger");
        } catch (Exception e) {
            api.logging().logToError("Scope Highlighter: history seeding failed: " + e);
        }
    }
}
