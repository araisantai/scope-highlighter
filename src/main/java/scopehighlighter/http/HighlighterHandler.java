package scopehighlighter.http;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import scopehighlighter.log.MessageLog;
import scopehighlighter.log.MessageRecord;
import scopehighlighter.model.GroupStore;

import java.util.Optional;

/**
 * 1) Auto-highlights every request that flows through Burp (Proxy, Repeater,
 *    Scanner, ...) according to the matching scope group.
 * 2) Records every completed exchange into the {@link MessageLog} with its
 *    tool of origin, so Repeater/Intruder requests can be replayed from the
 *    Scope Highlighter tab later (Burp keeps no history API for those tools).
 */
public final class HighlighterHandler implements HttpHandler {

    private final MontoyaApi api;
    private final GroupStore store;
    private final MessageLog messageLog;

    public HighlighterHandler(MontoyaApi api, GroupStore store, MessageLog messageLog) {
        this.api = api;
        this.store = store;
        this.messageLog = messageLog;
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
            log("Scope Highlighter: request handler error: " + e);
            return RequestToBeSentAction.continueWith(requestToBeSent);
        }
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived responseReceived) {
        record(responseReceived);
        try {
            if (!store.isAutoHighlightEnabled()) {
                return ResponseReceivedAction.continueWith(responseReceived);
            }
            Optional<GroupStore.Match> match = store.findMatch(responseReceived.initiatingRequest().url());
            if (match.isEmpty()) {
                return ResponseReceivedAction.continueWith(responseReceived);
            }
            Annotations annotations = buildAnnotations(responseReceived.annotations(), match.get());
            return ResponseReceivedAction.continueWith(responseReceived, annotations);
        } catch (Exception e) {
            log("Scope Highlighter: response handler error: " + e);
            return ResponseReceivedAction.continueWith(responseReceived);
        }
    }

    private void record(HttpResponseReceived responseReceived) {
        try {
            String source = sourceLabel(responseReceived.toolSource().toolType());
            String requestRaw = MessageLog.clampRaw(responseReceived.initiatingRequest().toString());
            String responseRaw = MessageLog.clampRaw(responseReceived.toString());
            MessageRecord record = new MessageRecord(
                    System.currentTimeMillis(),
                    source,
                    responseReceived.initiatingRequest().method(),
                    responseReceived.initiatingRequest().url(),
                    responseReceived.statusCode(),
                    requestRaw.length(),
                    responseRaw.length(),
                    requestRaw,
                    responseRaw);
            messageLog.record(record);
        } catch (Exception e) {
            log("Scope Highlighter: record error: " + e);
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
        Annotations updated = current.withHighlightColor(match.group().color());
        if (store.isWriteNotesEnabled() && !current.hasNotes()) {
            updated = updated.withNotes(match.group().name());
        }
        return updated;
    }

    private void log(String message) {
        try {
            api.logging().logToError(message);
        } catch (Exception ignored) {
            // logging must never break traffic handling
        }
    }
}
