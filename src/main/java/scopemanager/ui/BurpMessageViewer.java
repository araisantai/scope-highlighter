package scopemanager.ui;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import scopemanager.log.MessageRecord;

import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Font;

/**
 * Renders a {@link MessageRecord} in Burp's own request/response editors
 * (the same Pretty/Raw/Hex viewers used across Burp, e.g. in search results).
 * Falls back to plain text areas when the native editors cannot be created.
 */
public final class BurpMessageViewer implements MessageViewer {

    private final HttpRequestEditor requestEditor;
    private final HttpResponseEditor responseEditor;
    private final JTextArea fallbackRequest;
    private final JTextArea fallbackResponse;
    private final boolean nativeMode;

    private BurpMessageViewer(HttpRequestEditor requestEditor, HttpResponseEditor responseEditor,
                              JTextArea fallbackRequest, JTextArea fallbackResponse) {
        this.requestEditor = requestEditor;
        this.responseEditor = responseEditor;
        this.fallbackRequest = fallbackRequest;
        this.fallbackResponse = fallbackResponse;
        this.nativeMode = requestEditor != null && responseEditor != null;
    }

    /** Creates native Burp editors when possible, text areas otherwise. */
    public static BurpMessageViewer create(UserInterface userInterface) {
        HttpRequestEditor requestEditor = null;
        HttpResponseEditor responseEditor = null;
        try {
            requestEditor = userInterface.createHttpRequestEditor(EditorOptions.READ_ONLY);
            responseEditor = userInterface.createHttpResponseEditor(EditorOptions.READ_ONLY);
        } catch (Throwable t) {
            requestEditor = null;
            responseEditor = null;
        }
        return new BurpMessageViewer(requestEditor, responseEditor, newPlainArea(), newPlainArea());
    }

    /** Text-only viewer (headless tests / fallback). */
    public static BurpMessageViewer textOnly() {
        return new BurpMessageViewer(null, null, newPlainArea(), newPlainArea());
    }

    private static JTextArea newPlainArea() {
        JTextArea area = new JTextArea();
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setEditable(false);
        return area;
    }

    public Component requestComponent() {
        return nativeMode ? requestEditor.uiComponent() : fallbackRequest;
    }

    public Component responseComponent() {
        return nativeMode ? responseEditor.uiComponent() : fallbackResponse;
    }

    @Override
    public Component component() {
        return requestComponent();
    }

    @Override
    public void showMessage(MessageRecord record) {
        String responseRaw = record.responseRaw().isEmpty()
                ? "HTTP/1.1 0 No Response Recorded\r\n\r\n" + record.responseRaw()
                : record.responseRaw();
        if (nativeMode) {
            try {
                requestEditor.setRequest(HttpRequest.httpRequest(record.requestRaw()));
            } catch (Exception e) {
                try {
                    requestEditor.setRequest(HttpRequest.httpRequestFromUrl(record.url()));
                } catch (Exception ignored) {
                    // leave the editor unchanged rather than crash the UI
                }
            }
            try {
                responseEditor.setResponse(HttpResponse.httpResponse(responseRaw));
            } catch (Exception e) {
                try {
                    responseEditor.setResponse(HttpResponse.httpResponse("HTTP/1.1 0 Unparsed\r\n\r\n" + record.responseRaw()));
                } catch (Exception ignored) {
                    // leave the editor unchanged rather than crash the UI
                }
            }
        } else {
            fallbackRequest.setText(record.requestRaw());
            fallbackRequest.setCaretPosition(0);
            fallbackResponse.setText(record.responseRaw().isEmpty()
                    ? "(no response recorded)" : record.responseRaw());
            fallbackResponse.setCaretPosition(0);
        }
    }

    @Override
    public void setSearchExpression(String expression) {
        if (nativeMode) {
            try {
                requestEditor.setSearchExpression(expression == null ? "" : expression);
                responseEditor.setSearchExpression(expression == null ? "" : expression);
            } catch (Exception ignored) {
                // search highlighting is cosmetic
            }
        }
    }

    public boolean isNativeMode() {
        return nativeMode;
    }
}
