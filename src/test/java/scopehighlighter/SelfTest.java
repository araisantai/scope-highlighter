package scopehighlighter;

import scopehighlighter.model.GroupStore;
import scopehighlighter.model.MatchMode;
import scopehighlighter.model.ScopeGroup;
import scopehighlighter.ui.ScopeHighlighterTab;
import scopehighlighter.util.EndpointParser;
import burp.api.montoya.core.HighlightColor;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Headless self-test: exercises the Excel paste parser, the URL matcher,
 * store serialization round-trip, match precedence and the tab UI model.
 * Run via ./test.sh
 */
public final class SelfTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        parserTests();
        matcherTests();
        storeRoundTripTests();
        precedenceTests();
        tabSmokeTest();

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

    private static void tabSmokeTest() {
        try {
            System.setProperty("java.awt.headless", "true");
            GroupStore store = new GroupStore();
            store.addGroup(new ScopeGroup("admin career page", HighlightColor.RED, MatchMode.PREFIX, true,
                    List.of("https://admin.site.com/career")));

            ScopeHighlighterTab tab = new ScopeHighlighterTab(store, new ScopeHighlighterTab.Host() {
                @Override
                public java.awt.Component applyTheme(java.awt.Component component) {
                    return component;
                }

                @Override
                public void log(String message) {
                }

                @Override
                public void highlightHistory(Runnable onFinished) {
                    onFinished.run();
                }
            });

            check("ui: tab constructs headless", tab != null && tab.getComponentCount() == 3);
            javax.swing.JTable table = findTable(tab);
            check("ui: table has one group row", table != null && table.getRowCount() == 1);
            check("ui: table shows title", "admin career page".equals(table.getValueAt(0, 1)));
        } catch (Throwable t) {
            check("ui: tab smoke test (" + t + ")", false);
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

    // ------------------------------------------------------------------

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
