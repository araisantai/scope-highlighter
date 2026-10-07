package scopehighlighter.log;

/**
 * The four Burp places the user wants to reveal requests from, plus ALL.
 */
public enum Source {
    ALL("show all"),
    REPEATER("repeater"),
    INTRUDER("intruder"),
    HISTORY("history"),
    TARGET("target");

    private final String buttonLabel;

    Source(String buttonLabel) {
        this.buttonLabel = buttonLabel;
    }

    public String buttonLabel() {
        return buttonLabel;
    }

    /** Label used in the record's Source column ("Repeater", "Intruder", ...). */
    public String recordLabel() {
        switch (this) {
            case ALL:
                return "All";
            case HISTORY:
                return "History";
            default:
                return buttonLabel.substring(0, 1).toUpperCase() + buttonLabel.substring(1);
        }
    }
}
