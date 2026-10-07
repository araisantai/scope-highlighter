package scopehighlighter.http;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Annotations;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import scopehighlighter.model.GroupStore;

import java.util.Optional;

/**
 * Auto-highlights every request that flows through Burp (Proxy, Repeater,
 * Scanner, ...) according to the matching scope group.
 */
public final class HighlighterHandler implements HttpHandler {

    private final MontoyaApi api;
    private final GroupStore store;

    public HighlighterHandler(MontoyaApi api, GroupStore store) {
        this.api = api;
        this.store = store;
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
