package scopehighlighter;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.HighlightColor;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import scopehighlighter.http.HighlighterHandler;
import scopehighlighter.log.MessageLog;
import scopehighlighter.log.MessageRecord;
import scopehighlighter.log.Source;
import scopehighlighter.model.GroupStore;
import scopehighlighter.model.ScopeGroup;
import scopehighlighter.organizer.OrganizerSync;
import scopehighlighter.ui.BurpMessageViewer;
import scopehighlighter.ui.Host;
import scopehighlighter.ui.MessageViewer;
import scopehighlighter.ui.ScopeHighlighterTab;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
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
 * - "Scope Highlighter" tab: Excel-like endpoint grid with copy endpoint /
 *   show all / notes to organizer actions and a Burp-style context menu
 * - request logger: Repeater/Intruder/History/Target requests stay
 *   recoverable and are saved inside the Burp project file
 * - automatic Organizer sync, noted with the group title
 */
public final class ScopeHighlighterExtension implements BurpExtension {

    public static final String TAB_NAME = "Scope Highlighter";

    private MontoyaApi api;
    private GroupStore store;
    private MessageLog messageLog;
    private OrganizerSync organizerSync;

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName(TAB_NAME);

        store = new GroupStore();
        try {
            store.attach(api.persistence().extensionData());
        } catch (Exception e) {
            api.logging().logToError("Scope Highlighter: failed to load groups: " + e);
        }

        organizerSync = new OrganizerSync();
        try {
            organizerSync.attach(api.persistence().extensionData());
        } catch (Exception e) {
            api.logging().logToError("Scope Highlighter: failed to load organizer state: " + e);
        }

        messageLog = new MessageLog();
        try {
            messageLog.attach(api.persistence().extensionData());
        } catch (Exception e) {
            api.logging().logToError("Scope Highlighter: failed to load request log: " + e);
        }
        if (messageLog.size() == 0) {
            seedFromProxyHistory();
        }

        api.http().registerHttpHandler(new HighlighterHandler(api, store, messageLog, this::onRecorded));
        api.extension().registerUnloadingHandler(() -> {
            messageLog.saveNow();
            organizerSync.saveNow();
        });

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
    // auto "notes to organizer"
    // ------------------------------------------------------------------

    /** Every recorded request matching a group is auto-added to the Organizer. */
    private void onRecorded(MessageRecord record, ScopeGroup match) {
        if (match == null || !store.isAutoOrganizerEnabled()) {
            return;
        }
        sendRecordToOrganizer(record, match.name(), match.color());
    }

    /**
     * Sends one recorded exchange to the Organizer, noted with the group
     * title and highlighted in the group color. Deduplicated via
     * {@link OrganizerSync}. NOTE: the Montoya API has no Organizer
     * "collections" - the group title is written into the item's notes, which
     * is the closest grouping mechanism available to extensions.
     */
    private boolean sendRecordToOrganizer(MessageRecord record, String note, HighlightColor color) {
        if (!organizerSync.markSent(record.key())) {
            return false;
        }
        try {
            HttpService service = serviceFor(record.url());
            HttpRequest request = HttpRequest.httpRequest(service, record.requestRaw());
            if (record.responseRaw().isEmpty() || record.responseRaw().isBlank()) {
                api.organizer().sendToOrganizer(request);
            } else {
                HttpResponse response = HttpResponse.httpResponse(record.responseRaw());
                api.organizer().sendToOrganizer(
                        HttpRequestResponse.httpRequestResponse(request, response,
                                Annotations.annotations(note, color)));
            }
            return true;
        } catch (Exception e) {
            api.logging().logToError("Scope Highlighter: organizer send failed: " + e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // host: bridge for the UI
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

        @Override
        public void sendToRepeater(String endpointOrUrl) {
            HttpRequest request = buildRequest(endpointOrUrl);
            if (request != null) {
                api.repeater().sendToRepeater(request);
                log("Scope Highlighter: sent " + endpointOrUrl + " to Repeater");
            } else {
                log("Scope Highlighter: no request available yet for " + endpointOrUrl
                        + " (browse the endpoint once, then try again)");
            }
        }

        @Override
        public void sendToIntruder(String endpointOrUrl) {
            HttpRequest request = buildRequest(endpointOrUrl);
            if (request != null) {
                api.intruder().sendToIntruder(request);
                log("Scope Highlighter: sent " + endpointOrUrl + " to Intruder");
            } else {
                log("Scope Highlighter: no request available yet for " + endpointOrUrl
                        + " (browse the endpoint once, then try again)");
            }
        }

        @Override
        public int syncToOrganizer(ScopeGroup matcher) {
            int sent = 0;
            for (MessageRecord record : messageLog.entries()) {
                if (matcher.match(record.url()).isPresent()
                        && sendRecordToOrganizer(record, matcher.name(), matcher.color())) {
                    sent++;
                }
            }
            return sent;
        }

        @Override
        public void copyToClipboard(String text) {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(text), null);
            } catch (Exception e) {
                log("Scope Highlighter: clipboard failed: " + e);
            }
        }

        @Override
        public MessageViewer createMessageViewer() {
            return BurpMessageViewer.create(api.userInterface());
        }
    }

    /**
     * Builds the request to send to Repeater/Intruder: the most recent
     * recorded request of that endpoint (keeps method and body), or a plain
     * GET when the endpoint is a full URL and was never requested.
     */
    private HttpRequest buildRequest(String endpointOrUrl) {
        try {
            ScopeGroup matcher = new ScopeGroup("", HighlightColor.RED,
                    scopehighlighter.model.MatchMode.PREFIX, true, List.of(endpointOrUrl));
            List<MessageRecord> records = messageLog.filter(r -> matcher.match(r.url()).isPresent());
            if (!records.isEmpty()) {
                MessageRecord latest = records.get(records.size() - 1);
                return HttpRequest.httpRequest(serviceFor(latest.url()), latest.requestRaw());
            }
            if (endpointOrUrl.contains("://")) {
                return HttpRequest.httpRequestFromUrl(endpointOrUrl);
            }
        } catch (Exception e) {
            api.logging().logToError("Scope Highlighter: build request failed: " + e);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static HttpService serviceFor(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme();
            String host = uri.getHost() == null ? url : uri.getHost();
            int port = uri.getPort();
            if (port < 0) {
                port = "http".equalsIgnoreCase(scheme) ? 80 : 443;
            }
            return HttpService.httpService(host, port, !"http".equalsIgnoreCase(scheme));
        } catch (Exception e) {
            return HttpService.httpService(url, 443, true);
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
        String notes = entry.annotations() != null && entry.annotations().hasNotes()
                ? entry.annotations().notes() : "";
        return new MessageRecord(0, Source.TARGET.recordLabel(), request.method(), request.url(),
                status, requestRaw.length(), responseRaw.length(), requestRaw, responseRaw, notes);
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
                    String notes = entry.annotations() != null && entry.annotations().hasNotes()
                            ? entry.annotations().notes() : "";
                    messageLog.record(new MessageRecord(timestamp, "History",
                            entry.finalRequest().method(), entry.finalRequest().url(), status,
                            requestRaw.length(), responseRaw.length(), requestRaw, responseRaw, notes));
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
