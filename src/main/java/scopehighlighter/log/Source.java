package scopehighlighter.log;

/**
 * The sources the "show all" window can reveal requests from, plus ALL.
 */
public enum Source {
    ALL("show all"),
    REPEATER("repeater"),
    INTRUDER("intruder"),
    TARGET("target"),
    HISTORY("proxy history");

    private final String buttonLabel;

    Source(String buttonLabel) {
        this.buttonLabel = buttonLabel;
    }

    public String buttonLabel() {
        return buttonLabel;
    }

    /** Label used in the record's Source column ("Repeater", "History", ...). */
    public String recordLabel() {
        switch (this) {
            case HISTORY:
                return "History";
            case TARGET:
                return "Target";
            case REPEATER:
                return "Repeater";
            case INTRUDER:
                return "Intruder";
            default:
                return "All";
        }
    }
}
