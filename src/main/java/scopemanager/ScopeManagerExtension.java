package scopemanager;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.HighlightColor;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import scopemanager.http.HighlighterHandler;
import scopemanager.log.MessageLog;
import scopemanager.log.MessageRecord;
import scopemanager.log.Source;
import scopemanager.model.GroupStore;
import scopemanager.model.ScopeGroup;
import scopemanager.organizer.OrganizerSync;
import scopemanager.ui.BurpMessageViewer;
import scopemanager.ui.Host;
import scopemanager.ui.MessageViewer;
import scopemanager.ui.ScopeManagerTab;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scope Manager - Burp Suite extension (Montoya API).
 *
 * - auto-highlights requests in Burp history per endpoint group
 * - "Scope Manager" tab: Excel-like endpoint grid with a notes toolbar
 *   control, double-click results, and a Burp-style context menu
 * - request logger: Repeater/Intruder/History/Target requests stay
 *   recoverable and are saved inside the Burp project file
 * - automatic Organizer sync for matching requests with user-written notes
 */
public final class ScopeManagerExtension implements BurpExtension {

    public static final String TAB_NAME = "Scope Manager";
    public static final String VERSION = "v10";

    private MontoyaApi api;
    private GroupStore store;
    private MessageLog messageLog;
    private OrganizerSync organizerSync;
    private final ExecutorService organizerWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "scope-manager-organizer");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService noteSyncWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "scope-manager-notes");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> organizerInFlight = ConcurrentHashMap.newKeySet();

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName(TAB_NAME);

        store = new GroupStore();
        try {
            store.attach(api.persistence().extensionData());
        } catch (Exception e) {
            api.logging().logToError("Scope Manager: failed to load groups: " + e);
        }

        organizerSync = new OrganizerSync();
        try {
            organizerSync.attach(api.persistence().extensionData());
        } catch (Exception e) {
            api.logging().logToError("Scope Manager: failed to load organizer state: " + e);
        }

        messageLog = new MessageLog();
        try {
            messageLog.attach(api.persistence().extensionData());
        } catch (Exception e) {
            api.logging().logToError("Scope Manager: failed to load request log: " + e);
        }
        if (messageLog.size() == 0) {
            seedFromProxyHistory();
        }

        api.http().registerHttpHandler(new HighlighterHandler(api, store, messageLog, this::onRecorded));
        api.extension().registerUnloadingHandler(() -> {
            organizerWorker.shutdown();
            noteSyncWorker.shutdown();
            messageLog.saveNow();
            organizerSync.saveNow();
        });

        SwingUtilities.invokeLater(() -> {
            Host host = new BurpHost();
            ScopeManagerTab tab = new ScopeManagerTab(store, host);
            // any change to groups/settings re-applies highlights automatically
            store.setChangeListener(() -> {
                SwingUtilities.invokeLater(tab::refreshAll);
                host.reapplyHighlights();
            });
            api.userInterface().registerSuiteTab(TAB_NAME, tab);
            // Re-apply updated matcher rules to requests captured before reload.
            // Startup must not clear unrelated user highlights.
            applyHighlightsToHistory(false);
        });

        api.logging().logToOutput("Scope Manager " + VERSION + " loaded \u2014 " + logSummary());
    }

    /** One-line logger diagnostics (shown in the tab status and at startup). */
    private String logSummary() {
        int repeater = messageLog.filter(r -> r.isFrom("Repeater")).size();
        int intruder = messageLog.filter(r -> r.isFrom("Intruder")).size();
        int history = messageLog.filter(r -> r.isFrom("History")).size();
        return "logger: " + messageLog.size() + " recorded (repeater " + repeater
                + ", intruder " + intruder + ", history " + history + ")";
    }

    // ------------------------------------------------------------------
    // auto "notes to organizer"
    // ------------------------------------------------------------------

    /** Every recorded request matching a group is auto-added to the Organizer. */
    private void onRecorded(MessageRecord record, ScopeGroup match) {
        if (match == null || record.notes().isBlank()) {
            return;
        }
        organizerWorker.execute(() -> sendRecordToOrganizer(record, match.name(), match.color()));
    }

    /**
     * Sends one recorded exchange to the Organizer with its user note and
     * the group color. Deduplicated via
     * {@link OrganizerSync}. NOTE: the Montoya API has no Organizer
     * "collections", so the group title can select requests but cannot create
     * or choose a native Organizer collection.
     */
    private boolean sendRecordToOrganizer(MessageRecord record, String groupTitle, HighlightColor color) {
        if (record.notes().isBlank()) {
            return false;
        }
        String sentKey = OrganizerSync.keyFor(record.key(), record.notes());
        if (organizerSync.alreadySent(sentKey) || !organizerInFlight.add(sentKey)) {
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
                                Annotations.annotations(record.notes(), color)));
            }
            api.logging().logToOutput("Scope Manager: sent noted request matching scope group \""
                    + groupTitle + "\" to Organizer Inbox");
            organizerSync.markSent(sentKey);
            return true;
        } catch (Exception e) {
            api.logging().logToError("Scope Manager: organizer send failed: " + e);
            return false;
        } finally {
            organizerInFlight.remove(sentKey);
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
            Set<String> keys = new HashSet<>();
            List<MessageRecord> history = source == Source.ALL || source == Source.HISTORY
                    ? proxyHistoryRecords(matcher) : List.of();
            List<MessageRecord> target = source == Source.TARGET || source == Source.ALL
                    ? siteMapRecords(matcher) : List.of();
            List<MessageRecord> live = new ArrayList<>(history.size() + target.size());
            live.addAll(history);
            live.addAll(target);
            messageLog.mergeNotes(live);
            if (source == Source.ALL || source == Source.HISTORY) {
                // live proxy history is authoritative and includes entries the
                // logger never saw (loaded before the extension, or evicted)
                for (MessageRecord record : history) {
                    keys.add(record.key());
                    if (matcher.match(record.url()).isPresent()) {
                        out.add(record);
                    }
                }
            }
            if (source == Source.TARGET || source == Source.ALL) {
                for (MessageRecord record : target) {
                    if (!keys.contains(record.key()) && matcher.match(record.url()).isPresent()) {
                        out.add(record);
                    }
                }
            }
            if (source != Source.TARGET) {
                String label = source == Source.ALL ? null : source.recordLabel();
                for (MessageRecord record : messageLog.entries()) {
                    if (!keys.contains(record.key())
                            && (label == null || record.isFrom(label))
                            && matcher.match(record.url()).isPresent()) {
                        out.add(record);
                    }
                }
            }
            out.sort(Comparator.comparingLong(MessageRecord::timestamp));
            queueOrganizerSweep(out);
            return out;
        }

        @Override
        public String loggerSummary() {
            List<MessageRecord> all = messageLog.entries();
            int repeater = 0;
            int intruder = 0;
            int history = 0;
            int other = 0;
            for (MessageRecord record : all) {
                if (record.isFrom("Repeater")) {
                    repeater++;
                } else if (record.isFrom("Intruder")) {
                    intruder++;
                } else if (record.isFrom("History")) {
                    history++;
                } else {
                    other++;
                }
            }
            return "logger: " + all.size() + " recorded (repeater " + repeater
                    + ", intruder " + intruder + ", history " + history + ", other " + other + ")";
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
                log("Scope Manager: sent " + endpointOrUrl + " to Repeater");
            } else {
                log("Scope Manager: no request available yet for " + endpointOrUrl
                        + " (browse the endpoint once, then try again)");
            }
        }

        @Override
        public void sendToIntruder(String endpointOrUrl) {
            HttpRequest request = buildRequest(endpointOrUrl);
            if (request != null) {
                api.intruder().sendToIntruder(request);
                log("Scope Manager: sent " + endpointOrUrl + " to Intruder");
            } else {
                log("Scope Manager: no request available yet for " + endpointOrUrl
                        + " (browse the endpoint once, then try again)");
            }
        }

        @Override
        public void sendToComparer(String endpointOrUrl) {
            HttpRequest request = buildRequest(endpointOrUrl);
            if (request != null) {
                api.comparer().sendToComparer(burp.api.montoya.core.ByteArray.byteArray(request.toString()));
                log("Scope Manager: sent " + endpointOrUrl + " to Comparer");
            } else {
                log("Scope Manager: no request available yet for " + endpointOrUrl
                        + " (browse the endpoint once, then try again)");
            }
        }

        @Override
        public List<MessageRecord> allKnownRecords() {
            Map<String, MessageRecord> byKey = new LinkedHashMap<>();
            for (MessageRecord record : messageLog.entries()) {
                byKey.put(record.key(), record);
            }
            List<MessageRecord> history = proxyHistoryRecords();
            for (MessageRecord record : history) {
                byKey.put(record.key(), record);
            }
            List<MessageRecord> target = siteMapRecords();
            for (MessageRecord record : target) {
                byKey.putIfAbsent(record.key(), record);
            }
            List<MessageRecord> live = new ArrayList<>(history.size() + target.size());
            live.addAll(history);
            live.addAll(target);
            messageLog.mergeNotes(live);
            List<MessageRecord> out = new ArrayList<>(byKey.values());
            out.sort(Comparator.comparingLong(MessageRecord::timestamp));
            queueOrganizerSweep(out);
            return out;
        }

        /** Catch up notes discovered while reading Burp's live History or Target. */
        private void queueOrganizerSweep(List<MessageRecord> records) {
            if (organizerWorker.isShutdown()) {
                return;
            }
            List<MessageRecord> pending = new ArrayList<>();
            for (MessageRecord record : records) {
                if (!record.notes().isBlank()
                        && !organizerSync.alreadySent(OrganizerSync.keyFor(record.key(), record.notes()))) {
                    pending.add(record);
                }
            }
            if (!pending.isEmpty()) {
                organizerWorker.execute(() -> {
                    for (MessageRecord record : pending) {
                        store.findMatch(record.url()).ifPresent(match ->
                                sendRecordToOrganizer(record, match.group().name(), match.group().color()));
                    }
                });
            }
        }

        @Override
        public int syncToOrganizer(ScopeGroup matcher) {
            int sent = 0;
            Set<String> handled = new HashSet<>();
            for (MessageRecord record : allKnownRecords()) {
                if (!record.notes().isBlank()
                        && handled.add(record.key())
                        && matcher.match(record.url()).isPresent()
                        && sendRecordToOrganizer(record, matcher.name(), matcher.color())) {
                    sent++;
                }
            }
            return sent;
        }

        @Override
        public int syncAllToOrganizer() {
            int sent = 0;
            Set<String> handled = new HashSet<>();
            for (MessageRecord record : allKnownRecords()) {
                if (record.notes().isBlank() || !handled.add(record.key())) {
                    continue;
                }
                Optional<GroupStore.Match> match = store.findMatch(record.url());
                if (match.isPresent() && sendRecordToOrganizer(record,
                        match.get().group().name(), match.get().group().color())) {
                    sent++;
                }
            }
            return sent;
        }

        @Override
        public void updateNotes(MessageRecord record, String notes) {
            String value = notes == null ? "" : notes;
            messageLog.updateNotes(record.key(), value);
            noteSyncWorker.execute(() -> {
                try {
                    for (ProxyHttpRequestResponse entry : api.proxy().history()) {
                        MessageRecord live = toRecord(entry);
                        if (live != null && live.key().equals(record.key())) {
                            entry.annotations().setNotes(value);
                        }
                    }
                    for (HttpRequestResponse entry : api.siteMap().requestResponses()) {
                        MessageRecord live = toRecord(entry);
                        if (live != null && live.key().equals(record.key()) && entry.annotations() != null) {
                            entry.annotations().setNotes(value);
                        }
                    }
                } catch (Exception e) {
                    log("Scope Manager: note sync failed: " + e);
                }
            });
            if (!value.isBlank()) {
                store.findMatch(record.url()).ifPresent(match -> organizerWorker.execute(
                        () -> sendRecordToOrganizer(record.withNotes(value),
                                match.group().name(), match.group().color())));
            }
        }

        @Override
        public void copyToClipboard(String text) {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(text), null);
            } catch (Exception e) {
                log("Scope Manager: clipboard failed: " + e);
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
                    scopemanager.model.MatchMode.PREFIX, true, List.of(endpointOrUrl));
            List<MessageRecord> records = messageLog.filter(r -> matcher.match(r.url()).isPresent());
            if (!records.isEmpty()) {
                MessageRecord latest = records.get(records.size() - 1);
                return HttpRequest.httpRequest(serviceFor(latest.url()), latest.requestRaw());
            }
            if (endpointOrUrl.contains("://")) {
                return HttpRequest.httpRequestFromUrl(endpointOrUrl);
            }
        } catch (Exception e) {
            api.logging().logToError("Scope Manager: build request failed: " + e);
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
        return siteMapRecords(null);
    }

    private List<MessageRecord> siteMapRecords(ScopeGroup matcher) {
        List<MessageRecord> out = new ArrayList<>();
        try {
            for (HttpRequestResponse entry : api.siteMap().requestResponses()) {
                if (entry == null || entry.request() == null
                        || (matcher != null && matcher.match(entry.request().url()).isEmpty())) {
                    continue;
                }
                MessageRecord record = toRecord(entry);
                if (record != null) {
                    out.add(record);
                }
            }
        } catch (Exception e) {
            api.logging().logToError("Scope Manager: site map read failed: " + e);
        }
        return out;
    }

    /** Burp's Proxy history, queried live (labelled "History"). */
    private List<MessageRecord> proxyHistoryRecords() {
        return proxyHistoryRecords(null);
    }

    private List<MessageRecord> proxyHistoryRecords(ScopeGroup matcher) {
        List<MessageRecord> out = new ArrayList<>();
        try {
            for (ProxyHttpRequestResponse entry : api.proxy().history()) {
                try {
                    if (matcher != null && matcher.match(entry.finalRequest().url()).isEmpty()) {
                        continue;
                    }
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
                    out.add(new MessageRecord(timestamp, "History",
                            entry.finalRequest().method(), entry.finalRequest().url(), status,
                            requestRaw.length(), responseRaw.length(), requestRaw, responseRaw, notes));
                } catch (Exception ignored) {
                    // skip unreadable entries
                }
            }
        } catch (Exception e) {
            api.logging().logToError("Scope Manager: proxy history read failed: " + e);
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

    private static MessageRecord toRecord(ProxyHttpRequestResponse entry) {
        if (entry == null || entry.request() == null) {
            return null;
        }
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
        return new MessageRecord(timestamp, "History", entry.finalRequest().method(),
                entry.finalRequest().url(), status, requestRaw.length(), responseRaw.length(),
                requestRaw, responseRaw, notes);
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
        applyHighlightsToHistory(true);
    }

    private void applyHighlightsToHistory(boolean clearUnmatched) {
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
                            colored++;
                        } else if (clearUnmatched && annotations.hasHighlightColor()) {
                            annotations.setHighlightColor(HighlightColor.NONE);
                            cleared++;
                        }
                    } catch (Exception ignored) {
                        // one odd entry must not abort the refresh
                    }
                }
                api.logging().logToOutput("Scope Manager: auto-applied highlights ("
                        + colored + " set, " + cleared + " cleared) on proxy history");
            } catch (Exception e) {
                api.logging().logToError("Scope Manager: highlight refresh failed: " + e);
            }
        }, "scope-manager-apply");
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
            api.logging().logToOutput("Scope Manager: recorded existing proxy history into the logger");
        } catch (Exception e) {
            api.logging().logToError("Scope Manager: history seeding failed: " + e);
        }
    }
}
