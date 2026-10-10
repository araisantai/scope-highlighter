package scopemanager.ui;

import scopemanager.log.MessageRecord;
import scopemanager.log.Source;
import scopemanager.model.ScopeGroup;

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

    /** Context menu / actions: send a request for this endpoint to Repeater. */
    void sendToRepeater(String endpointOrUrl);

    /** Context menu / actions: send a request for this endpoint to Intruder. */
    void sendToIntruder(String endpointOrUrl);

    /** Context menu / actions: send a request for this endpoint to Comparer. */
    void sendToComparer(String endpointOrUrl);

    /** Send recorded requests with notes matching one row to Organizer Inbox. */
    int syncToOrganizer(ScopeGroup matcher);

    /** Send currently known, grouped requests with notes to Organizer Inbox. */
    int syncAllToOrganizer();

    /** Copy plain text to the system clipboard. */
    void copyToClipboard(String text);

    /** Create a request/response viewer (Burp editors when available). */
    MessageViewer createMessageViewer();

    /** Diagnostics: e.g. "logger: 1234 recorded (repeater 900, intruder 12, history 322)". */
    String loggerSummary();

    /**
     * Every record the extension can see: logger + live proxy history +
     * live site map, deduplicated. Used for the per-row Sources column and
     * the "show all" diagnostics.
     */
    List<MessageRecord> allKnownRecords();

    /** Update this exchange's note in the extension log and mutable Burp views. */
    void updateNotes(MessageRecord record, String notes);
}
