package scopemanager.model;

/**
 * How a group's endpoint patterns are matched against a request URL.
 */
public enum MatchMode {
    PREFIX("Prefix"),
    CONTAINS("Contains"),
    REGEX("Regex");

    private final String displayName;

    MatchMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
