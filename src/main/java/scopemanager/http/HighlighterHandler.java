package scopemanager.http;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import scopemanager.log.MessageLog;
import scopemanager.log.MessageRecord;
import scopemanager.model.GroupStore;
import scopemanager.model.ScopeGroup;

import java.util.Optional;

/**
 * 1) Auto-highlights every request that flows through Burp (Proxy, Repeater,
 *    Scanner, ...) according to the matching scope group.
 * 2) Records every completed exchange into the {@link MessageLog} with its
 *    tool of origin and its user-owned note, so Repeater/Intruder requests
 *    can be replayed from the Scope Manager tab later (Burp keeps no
 *    history API for those tools).
 * 3) Notifies a {@link Listener} for every recorded match (used for the
 *    automatic "notes to organizer" sync).
 */
public final class HighlighterHandler implements HttpHandler {

    /** Called after a message was recorded; match is null when no group matched. */
    public interface Listener {
        void onRecorded(MessageRecord record, ScopeGroup match);
    }

    private final MontoyaApi api;
    private final GroupStore store;
    private final MessageLog messageLog;
    private final Listener listener;

    public HighlighterHandler(MontoyaApi api, GroupStore store, MessageLog messageLog, Listener listener) {
        this.api = api;
        this.store = store;
        this.messageLog = messageLog;
        this.listener = listener;
    }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent requestToBeSent) {
        try {
            if (!store.isAutoHighlightEnabled()) {
                return RequestToBeSentAction.continueWith(requestToBeSent);
            }
            Optional<GroupStore.Match> match = store.findMatch(requestToBeSent.url());
            if (match.isEmpty()) {
                return RequestToBeSentAction.continueWith(requestToBeSent);
            }
            Annotations annotations = buildAnnotations(requestToBeSent.annotations(), match.get());
            return RequestToBeSentAction.continueWith(requestToBeSent, annotations);
        } catch (Exception e) {
            log("Scope Manager: request handler error: " + e);
            return RequestToBeSentAction.continueWith(requestToBeSent);
        }
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived responseReceived) {
        Optional<GroupStore.Match> match = Optional.empty();
        try {
            match = store.findMatch(responseReceived.initiatingRequest().url());
        } catch (Throwable e) {
            log("Scope Manager: match error: " + e);
        }
        try {
            record(responseReceived, match.map(GroupStore.Match::group).orElse(null));
        } catch (Throwable e) {
            log("Scope Manager: record dispatch error: " + e);
        }
        try {
            if (!store.isAutoHighlightEnabled()) {
                return ResponseReceivedAction.continueWith(responseReceived);
            }
            if (match.isEmpty()) {
                return ResponseReceivedAction.continueWith(responseReceived);
            }
            Annotations annotations = buildAnnotations(responseReceived.annotations(), match.get());
            return ResponseReceivedAction.continueWith(responseReceived, annotations);
        } catch (Exception e) {
            log("Scope Manager: response handler error: " + e);
            return ResponseReceivedAction.continueWith(responseReceived);
        }
    }

    private void record(HttpResponseReceived responseReceived, ScopeGroup match) {
        try {
            String source = sourceLabel(responseReceived.toolSource().toolType());
            String requestRaw = MessageLog.clampRaw(responseReceived.initiatingRequest().toString());
            String responseRaw = MessageLog.clampRaw(responseReceived.toString());
            String notes = responseReceived.annotations().hasNotes()
                    ? responseReceived.annotations().notes() : "";
            MessageRecord record = new MessageRecord(
                    System.currentTimeMillis(),
                    source,
                    responseReceived.initiatingRequest().method(),
                    responseReceived.initiatingRequest().url(),
                    responseReceived.statusCode(),
                    requestRaw.length(),
                    responseRaw.length(),
                    requestRaw,
                    responseRaw,
                    notes);
            messageLog.record(record);
            if (listener != null) {
                listener.onRecorded(record, match);
            }
        } catch (Throwable e) {
            log("Scope Manager: record error: " + e);
        }
    }

    /** User vocabulary: Proxy traffic = "History". */
    private static String sourceLabel(ToolType toolType) {
        if (toolType == null) {
            return "Other";
        }
        switch (toolType) {
            case PROXY:
                return "History";
            case REPEATER:
                return "Repeater";
            case INTRUDER:
                return "Intruder";
            case SCANNER:
                return "Scanner";
            case TARGET:
                return "Target";
            default:
                return toolType.toolName();
        }
    }

    private Annotations buildAnnotations(Annotations current, GroupStore.Match match) {
        return current.withHighlightColor(match.group().color());
    }

    private void log(String message) {
        try {
            api.logging().logToError(message);
        } catch (Exception ignored) {
            // logging must never break traffic handling
        }
    }
}
