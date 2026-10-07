package scopehighlighter.ui;

import scopehighlighter.log.MessageRecord;

import java.awt.Component;

/**
 * A request/response viewer. The Burp-backed implementation renders messages
 * in Burp's own message editors (Pretty / Raw / Hex) so the "show all" window
 * looks and behaves like Burp's own search history.
 */
public interface MessageViewer {

    Component component();

    void showMessage(MessageRecord record);

    /** Highlights the search term inside the viewer (Burp editor feature). */
    void setSearchExpression(String expression);
}
