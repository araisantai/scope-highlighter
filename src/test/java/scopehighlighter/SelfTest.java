package scopehighlighter;

import burp.api.montoya.core.HighlightColor;
import scopehighlighter.log.MessageLog;
import scopehighlighter.log.MessageRecord;
import scopehighlighter.model.GroupStore;
import scopehighlighter.model.MatchMode;
import scopehighlighter.model.ScopeGroup;
import scopehighlighter.ui.GroupDialog;
import scopehighlighter.ui.ResultsFrame;
import scopehighlighter.ui.ScopeHighlighterTab;
import scopehighlighter.util.EndpointParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Headless self-test: Excel paste parser, URL matcher, store persistence and
 * change listener, message logger (record/filter/persist/cap), outline grid
 * model and results table model. Run via ./test.sh
 */
public final class SelfTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        parserTests();
        matcherTests();
        storeRoundTripTests();
        precedenceTests();
        storeListenerTests();
        messageLogTests();
        actionRoutingTests();
        organizerSyncTests();
        detailModelTests();
        tabSmokeTest();
        resultsModelTests();

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------

    private static void parserTests() {
        // plain lines (one column copied from Excel)
        List<String> a = EndpointParser.parse("https://admin.site.com/career\n/admin/verify-location\nexample.com/api\n");
        check("parser: plain lines", a.equals(Arrays.asList(
                "https://admin.site.com/career", "/admin/verify-location", "example.com/api")));

        // CRLF + tabs (multi-column Excel copy) + trailing whitespace
        List<String> b = EndpointParser.parse("https://a.com/one\r\nhttps://b.com/two\t\r\n  /three  \r\n");
        check("parser: excel CRLF + tabs", b.equals(Arrays.asList(
                "https://a.com/one", "https://b.com/two", "/three")));

        // comma-separated simple values
        List<String> c = EndpointParser.parse("https://a.com/x, https://b.com/y,/z");
        check("parser: comma separated", c.equals(Arrays.asList(
                "https://a.com/x", "https://b.com/y", "/z")));

        // regex with {1,3} must NOT be comma-split
        List<String> d = EndpointParser.parse("admin/.*{1,3}extra\n/api/v[0-9]+");
        check("parser: regex kept intact", d.equals(Arrays.asList(
                "admin/.*{1,3}extra", "/api/v[0-9]+")));

        // quotes, comments, duplicates, empty cells
        List<String> e = EndpointParser.parse("\"https://a.com/q\"\n# comment\n// comment\n\nhttps://a.com/q\n");
        check("parser: quotes/comments/dedupe", e.equals(List.of("https://a.com/q")));

        // nothing to parse
        check("parser: empty input", EndpointParser.parse("").isEmpty());
    }

    private static void matcherTests() {
        ScopeGroup prefix = new ScopeGroup("g", HighlightColor.RED, MatchMode.PREFIX, true,
                List.of("https://admin.site.com/career", "/admin/verify-location", "admin.site.com/api"));

        check("match: prefix full url",
                prefix.match("https://admin.site.com/career/jobs?id=3").isPresent());
        check("match: prefix full url negative",
                prefix.match("https://admin.site.com/careerx").isEmpty());
        check("match: prefix path",
                prefix.match("https://other.host:8443/admin/verify-location/step2").isPresent());
        check("match: prefix bare host+path",
                prefix.match("https://admin.site.com/api/v2/users").isPresent());
        check("match: prefix case-insensitive host",
                prefix.match("https://ADMIN.Site.com/Career").isPresent());
        check("match: prefix negative",
                prefix.match("https://admin.site.com/other").isEmpty());

        ScopeGroup contains = new ScopeGroup("g", HighlightColor.BLUE, MatchMode.CONTAINS, true,
                List.of("verify-location"));
        check("match: contains positive",
                contains.match("https://host/x/verify-location?a=1").isPresent());
        check("match: contains negative",
                contains.match("https://host/x/verify").isEmpty());

        ScopeGroup regex = new ScopeGroup("g", HighlightColor.GREEN, MatchMode.REGEX, true,
                List.of("admin/(career|jobs)", "/api/v[0-9]+/"));
        check("match: regex positive",
                regex.match("https://host/admin/jobs?x").isPresent());
        check("match: regex alternation",
                regex.match("https://host/admin/career").isPresent());
        check("match: regex class positive",
                regex.match("https://host/api/v12/secret").isPresent());
        check("match: regex negative",
                regex.match("https://host/public").isEmpty());

        ScopeGroup brokenRegex = new ScopeGroup("g", HighlightColor.RED, MatchMode.REGEX, true,
                List.of("([unclosed"));
        check("match: invalid regex does not throw",
                brokenRegex.match("https://host/anything").isEmpty());

        ScopeGroup disabled = new ScopeGroup("g", HighlightColor.RED, MatchMode.CONTAINS, false,
                List.of("host"));
        check("match: disabled group never matches",
                disabled.match("https://host/x").isEmpty());
    }

    private static void storeRoundTripTests() {
        GroupStore store = new GroupStore();
        store.addGroup(new ScopeGroup("admin career page", HighlightColor.RED, MatchMode.PREFIX, true,
                List.of("https://admin.site.com/career", "/admin/career")));
        store.addGroup(new ScopeGroup("admin verification location", HighlightColor.BLUE, MatchMode.REGEX, true,
                List.of("verify-location.*{1,3}")));
        store.setAutoHighlightEnabled(false);
        store.setWriteNotesEnabled(false);

        String saved = store.saveToString();

        GroupStore restored = new GroupStore();
        restored.loadFromString(saved);

        check("store: group count round-trip", restored.groupCount() == 2);
        ScopeGroup g0 = restored.groups().get(0);
        ScopeGroup g1 = restored.groups().get(1);
        check("store: names round-trip",
                g0.name().equals("admin career page") && g1.name().equals("admin verification location"));
        check("store: colors round-trip",
                g0.color() == HighlightColor.RED && g1.color() == HighlightColor.BLUE);
        check("store: modes round-trip", g0.mode() == MatchMode.PREFIX && g1.mode() == MatchMode.REGEX);
        check("store: patterns round-trip",
                g1.patterns().equals(List.of("verify-location.*{1,3}")));
        check("store: settings round-trip",
                !restored.isAutoHighlightEnabled() && !restored.isWriteNotesEnabled());
        check("store: endpoint count", restored.endpointCount() == 3);
        check("store: known names survive restore",
                restored.isKnownName("admin career page") && restored.isKnownName("admin verification location"));

        restored.setAutoHighlightEnabled(true);
        restored.setWriteNotesEnabled(true);
    }

    private static void precedenceTests() {
        GroupStore store = new GroupStore();
        store.addGroup(new ScopeGroup("first", HighlightColor.RED, MatchMode.CONTAINS, true,
                List.of("admin")));
        store.addGroup(new ScopeGroup("second", HighlightColor.BLUE, MatchMode.PREFIX, true,
                List.of("admin.site.com")));

        Optional<GroupStore.Match> match = store.findMatch("https://admin.site.com/x");
        check("precedence: first matching group wins",
                match.isPresent() && match.get().group().name().equals("first"));

        check("precedence: no match",
                store.findMatch("https://public.site.com/x").isEmpty());

        // disabled first group -> second wins
        store.groups().get(0).setEnabled(false);
        match = store.findMatch("https://admin.site.com/x");
        check("precedence: disabled group skipped",
                match.isPresent() && match.get().group().name().equals("second"));
    }

    private static void storeListenerTests() {
        GroupStore store = new GroupStore();
        int[] fired = {0};
        store.setChangeListener(() -> fired[0]++);

        store.addGroup(new ScopeGroup("hiring stage", HighlightColor.RED, MatchMode.PREFIX, true,
                List.of("https://example.com/hiring-detail")));
        check("listener: fires on add", fired[0] == 1);
        check("listener: add registers known name", store.isKnownName("hiring stage"));

        store.updateGroup(0, new ScopeGroup("hiring stage v2", HighlightColor.BLUE, MatchMode.PREFIX, true,
                List.of("https://example.com/hiring-detail")));
        check("listener: fires on update", fired[0] == 2);

        store.removeGroup(0);
        check("listener: fires on remove", fired[0] == 3);
        check("listener: removed group name stays known (for highlight cleanup)",
                store.isKnownName("hiring stage") && store.isKnownName("hiring stage v2"));
    }

    private static void messageLogTests() {
        MessageLog log = new MessageLog();
        log.record(new MessageRecord(1000, "Repeater", "GET", "https://a.com/one", 200, 10, 20, "REQ1", "RESP1"));
        log.record(new MessageRecord(2000, "Intruder", "POST", "https://a.com/two", 404, 11, 21, "REQ2", "RESP2"));
        log.record(new MessageRecord(3000, "History", "GET", "https://a.com/three", 200, 12, 22, "REQ3", "RESP3"));

        check("log: records 3", log.size() == 3);
        check("log: chronological", log.entries().get(0).url().equals("https://a.com/one"));
        check("log: filter by source", log.filter(r -> r.isFrom("Repeater")).size() == 1
                && log.filter(r -> r.isFrom("Intruder")).size() == 1);
        check("log: raw kept", log.entries().get(1).requestRaw().equals("REQ2")
                && log.entries().get(1).responseRaw().equals("RESP2"));

        // persistence round trip (blob format)
        String blob = log.toBlob();
        MessageLog restored = new MessageLog();
        restored.loadFromBlob(blob);
        check("log: persist round trip", restored.size() == 3);
        check("log: persist keeps details", restored.entries().get(1).url().equals("https://a.com/two")
                && restored.entries().get(1).status() == 404
                && restored.entries().get(1).requestRaw().equals("REQ2"));

        // nasty characters survive the blob (tabs, newlines, unicode, pipes)
        MessageLog nasty = new MessageLog();
        String req = "GET /x?a=1|2\ttab HTTP/1.1\nHost: \u00e9\u00e8\n\nbody|with\ttabs";
        String resp = "HTTP/1.1 200 OK\n\n\u4f60\u597d|world\nline2";
        nasty.record(new MessageRecord(1, "Intruder", "GET", "https://a.com/x?a=1|2", 200,
                req.length(), resp.length(), req, resp, "Hiring|Stage"));
        MessageLog nastyBack = new MessageLog();
        nastyBack.loadFromBlob(nasty.toBlob());
        check("log: blob survives nasty characters", nastyBack.size() == 1
                && nastyBack.entries().get(0).requestRaw().equals(req)
                && nastyBack.entries().get(0).responseRaw().equals(resp)
                && nastyBack.entries().get(0).url().equals("https://a.com/x?a=1|2")
                && nastyBack.entries().get(0).notes().equals("Hiring|Stage"));

        // corrupt blob content is skipped, never throws
        MessageLog corrupt = new MessageLog();
        corrupt.loadFromBlob("v2\nnot-base64\t\t\n\n###\tx\ty\n");
        check("log: corrupt blob tolerated", corrupt.size() == 0);

        // notes (group title) survive persistence
        MessageLog noted = new MessageLog();
        noted.record(new MessageRecord(5, "Repeater", "GET", "https://a.com/n", 200,
                1, 1, "r", "s", "Hiring Stage"));
        MessageLog notedBack = new MessageLog();
        notedBack.loadFromBlob(noted.toBlob());
        check("log: notes survive persistence",
                notedBack.size() == 1 && "Hiring Stage".equals(notedBack.entries().get(0).notes()));

        // truncation marker
        String huge = "x".repeat(MessageLog.MAX_RAW_CHARS + 100);
        String clamped = MessageLog.clampRaw(huge);
        check("log: oversized raw clamped", clamped.length() < huge.length()
                && clamped.endsWith("...[truncated by Scope Highlighter]"));

        // FIFO cap
        MessageLog big = new MessageLog();
        for (int i = 0; i < MessageLog.MAX_ENTRIES + 10; i++) {
            big.record(new MessageRecord(i, "Intruder", "GET", "https://a.com/" + i, 200, 1, 1, "r", "s"));
        }
        check("log: FIFO cap holds", big.size() == MessageLog.MAX_ENTRIES);
        check("log: FIFO drops oldest", big.entries().get(0).url().equals("https://a.com/10"));
    }

    private static void actionRoutingTests() {
        // three equal buttons per cell: border 2px + 3px gaps between
        for (int width : new int[]{200, 333, 360, 500}) {
            int count = ScopeHighlighterTab.ACTIONS.length;
            int gap = 3;
            int inset = 2;
            int buttonWidth = (width - 2 * inset - (count - 1) * gap) / count;
            boolean all = true;
            for (int i = 0; i < count; i++) {
                int center = inset + i * (buttonWidth + gap) + buttonWidth / 2;
                if (ScopeHighlighterTab.actionIndexForCellX(center, width) != i) {
                    all = false;
                }
            }
            check("actions: all three buttons routable at width " + width, all);
        }
        check("actions: first button left edge", ScopeHighlighterTab.actionIndexForCellX(2, 360) == 0);
        check("actions: last button right edge", ScopeHighlighterTab.actionIndexForCellX(357, 360) == 2);
        check("actions: gap maps to nearest button", ScopeHighlighterTab.actionIndexForCellX(120, 360) == 1);
    }

    private static void organizerSyncTests() {
        scopehighlighter.organizer.OrganizerSync sync = new scopehighlighter.organizer.OrganizerSync();
        check("organizer: first send goes through", sync.markSent("GET /a 200 1/2"));
        check("organizer: duplicate suppressed", !sync.markSent("GET /a 200 1/2"));
        check("organizer: second key goes through", sync.markSent("GET /b 200 1/2"));
        check("organizer: tracks size", sync.size() == 2);

        scopehighlighter.organizer.OrganizerSync restored = new scopehighlighter.organizer.OrganizerSync();
        restored.loadFromBlob(sync.toBlob());
        check("organizer: dedupe state survives restore",
                !restored.markSent("GET /a 200 1/2") && restored.size() == 2);
    }

    private static void detailModelTests() {
        MessageRecord record = new MessageRecord(1725000000000L, "Repeater", "POST",
                "https://a.com/x?id=7&name=bob", 200, 50, 60,
                "POST /x?id=7 HTTP/1.1\r\nHost: a.com\r\nX-Test: 1\r\n\r\nbody",
                "HTTP/1.1 200 OK\r\n\r\nok", "Hiring Stage");
        ResultsFrame.DetailTableModel model = new ResultsFrame.DetailTableModel();
        model.setRecord(record);

        boolean headerFound = false;
        boolean paramFound = false;
        boolean methodFound = false;
        for (int i = 0; i < model.getRowCount(); i++) {
            String name = String.valueOf(model.getValueAt(i, 0));
            String value = String.valueOf(model.getValueAt(i, 1));
            if ("X-Test".equals(name) && "1".equals(value)) {
                headerFound = true;
            }
            if ("param id".equals(name) && "7".equals(value)) {
                paramFound = true;
            }
            if ("Method".equals(name) && "POST".equals(value)) {
                methodFound = true;
            }
        }
        check("details: summary fields shown", methodFound);
        check("details: request headers parsed", headerFound);
        check("details: query params parsed", paramFound);
        check("details: notes available", "Hiring Stage".equals(record.notes()));

        model.setRecord(null);
        check("details: cleared with null record", model.getRowCount() == 0);
    }

    private static void tabSmokeTest() {
        try {
            System.setProperty("java.awt.headless", "true");
            GroupStore store = new GroupStore();
            store.addGroup(new ScopeGroup("admin career page", HighlightColor.RED, MatchMode.PREFIX, true,
                    List.of("https://admin.site.com/career", "/admin/verify-location")));

            ScopeHighlighterTab tab = new ScopeHighlighterTab(store, new HostStub());

            check("ui: tab constructs headless", tab != null && tab.getComponentCount() == 3);
            javax.swing.JTable table = findTable(tab);
            check("ui: grid has group + endpoint rows", table != null && table.getRowCount() == 3);
            check("ui: grid has action column", table != null && table.getColumnCount() == 5);
            check("ui: group row title+count", "admin career page  (2 endpoints)".equals(table.getValueAt(0, 1)));
            check("ui: endpoint rows listed like excel",
                    "https://admin.site.com/career".equals(table.getValueAt(1, 1))
                    && "/admin/verify-location".equals(table.getValueAt(2, 1)));
            check("ui: endpoint numbering", "1".equals(table.getValueAt(1, 0))
                    && "2".equals(table.getValueAt(2, 0)));
            check("ui: color and match columns", "Red".equals(table.getValueAt(0, 2))
                    && "Prefix".equals(table.getValueAt(0, 3)));

            // mutation through the store refreshes the grid
            store.addGroup(new ScopeGroup("candidate detail", HighlightColor.BLUE, MatchMode.CONTAINS, true,
                    List.of("https://example.com/candidate-detail")));
            tab.refreshAll();
            check("ui: grid reflects new group", table.getRowCount() == 5);
        } catch (Throwable t) {
            check("ui: tab smoke test (" + t + ")", false);
        }
    }

    private static void resultsModelTests() {
        List<MessageRecord> records = new ArrayList<>();
        records.add(new MessageRecord(0, "Target", "GET", "https://a.com/x", 200, 5, 10, "REQ", "RESP"));
        records.add(new MessageRecord(1725000000000L, "Repeater", "POST", "https://a.com/y", 500, 6, 11, "REQ2", "RESP2"));

        ResultsFrame.ResultsTableModel model =
                new ResultsFrame.ResultsTableModel(records, GroupDialog.lightTint(HighlightColor.RED));
        check("results: row count", model.getRowCount() == 2);
        check("results: columns", model.getColumnCount() == 7);
        check("results: numbering and source", Integer.valueOf(1).equals(model.getValueAt(0, 0))
                && "Target".equals(model.getValueAt(0, 2)));
        check("results: zero timestamp shows dash", "-".equals(model.getValueAt(0, 1)));
        check("results: url/status/length", "https://a.com/y".equals(model.getValueAt(1, 4))
                && Integer.valueOf(500).equals(model.getValueAt(1, 5))
                && Integer.valueOf(11).equals(model.getValueAt(1, 6)));
        check("results: tint set", model.tint() != null);
    }

    // ------------------------------------------------------------------

    private static final class HostStub implements scopehighlighter.ui.Host {
        @Override
        public java.awt.Component applyTheme(java.awt.Component component) {
            return component;
        }

        @Override
        public void log(String message) {
        }

        @Override
        public List<MessageRecord> collect(scopehighlighter.log.Source source, ScopeGroup matcher) {
            return new ArrayList<>();
        }

        @Override
        public void reapplyHighlights() {
        }

        @Override
        public void sendToRepeater(String endpointOrUrl) {
        }

        @Override
        public void sendToIntruder(String endpointOrUrl) {
        }

        @Override
        public int syncToOrganizer(ScopeGroup matcher) {
            return 0;
        }

        @Override
        public void copyToClipboard(String text) {
        }

        @Override
        public scopehighlighter.ui.MessageViewer createMessageViewer() {
            return scopehighlighter.ui.BurpMessageViewer.textOnly();
        }
    }

    private static javax.swing.JTable findTable(java.awt.Container root) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof javax.swing.JTable) {
                return (javax.swing.JTable) c;
            }
            if (c instanceof java.awt.Container) {
                javax.swing.JTable nested = findTable((java.awt.Container) c);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + label);
        } else {
            failed++;
            System.out.println("FAIL  " + label);
        }
    }
}
