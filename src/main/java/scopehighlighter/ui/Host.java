package scopehighlighter.ui;

import scopehighlighter.log.MessageRecord;
import scopehighlighter.log.Source;
import scopehighlighter.model.ScopeGroup;

import java.awt.Component;
import java.util.List;

/**
 * Everything the UI needs from the Burp host. Kept as an interface so the
 * components stay headless-testable and Burp-free.
 */
public interface Host {

    Component applyTheme(Component component);

    void log(String message);

    /**
     * Collect the recorded/live requests for the given source filter that
     * match the given endpoint matcher, oldest first.
     */
    List<MessageRecord> collect(Source source, ScopeGroup matcher);

    /** Re-apply highlights to existing Burp history after group changes. */
    void reapplyHighlights();
}
