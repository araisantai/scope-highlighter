package scopehighlighter;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import scopehighlighter.http.HighlighterHandler;
import scopehighlighter.model.GroupStore;
import scopehighlighter.ui.ScopeHighlighterTab;

import javax.swing.SwingUtilities;

/**
 * Scope Highlighter - Burp Suite extension (Montoya API).
 *
 * Auto-highlights requests in Burp history based on user-defined endpoint
 * groups, each with its own title and Burp highlight color.
 */
public final class ScopeHighlighterExtension implements BurpExtension {

    public static final String TAB_NAME = "Scope Highlighter";

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName(TAB_NAME);

        GroupStore store = new GroupStore();
        store.attach(api.persistence().extensionData());

        api.http().registerHttpHandler(new HighlighterHandler(api, store));

        SwingUtilities.invokeLater(() -> {
            ScopeHighlighterTab tab = new ScopeHighlighterTab(store, new ScopeHighlighterTab.Host() {
                @Override
                public java.awt.Component applyTheme(java.awt.Component component) {
                    api.userInterface().applyThemeToComponent(component);
                    return component;
                }

                @Override
                public void log(String message) {
                    api.logging().logToOutput(message);
                    api.logging().logToError(message);
                }

                @Override
                public void highlightHistory(Runnable onFinished) {
                    new Thread(() -> {
                        int highlighted = 0;
                        int total = 0;
                        try {
                            for (burp.api.montoya.proxy.ProxyHttpRequestResponse entry : api.proxy().history()) {
                                total++;
                                try {
                                    String url = entry.finalRequest().url();
                                    java.util.Optional<GroupStore.Match> match = store.findMatch(url);
                                    if (match.isPresent()) {
                                        entry.annotations().setHighlightColor(match.get().group().color());
                                        highlighted++;
                                    }
                                } catch (Exception e) {
                                    // a single odd entry must not abort the backfill
                                }
                            }
                            final int h = highlighted;
                            final int t = total;
                            SwingUtilities.invokeLater(() -> onFinished.run());
                            api.logging().logToOutput(
                                    "Scope Highlighter: highlighted " + h + " of " + t + " proxy history items");
                        } catch (Exception e) {
                            api.logging().logToError("Scope Highlighter: history backfill failed: " + e);
                            SwingUtilities.invokeLater(() -> onFinished.run());
                        }
                    }, "scope-highlighter-backfill").start();
                }
            });
            api.userInterface().registerSuiteTab(TAB_NAME, tab);
        });
    }
}
