package scopehighlighter.log;

/**
 * One recorded HTTP exchange, independent of Burp's own (volatile) tool
 * histories. Carries the raw request/response text so it can be shown later
 * even after the original Repeater/Intruder tab is gone.
 */
public final class MessageRecord {

    private final long timestamp;
    private final String source;          // "Repeater", "Intruder", "History", "Target", "Scanner", ...
    private final String method;
    private final String url;
    private final int status;
    private final int requestLength;
    private final int responseLength;
    private final String requestRaw;
    private final String responseRaw;
    private final String notes;           // group title / annotations note

    public MessageRecord(long timestamp, String source, String method, String url,
                         int status, int requestLength, int responseLength,
                         String requestRaw, String responseRaw) {
        this(timestamp, source, method, url, status, requestLength, responseLength,
                requestRaw, responseRaw, "");
    }

    public MessageRecord(long timestamp, String source, String method, String url,
                         int status, int requestLength, int responseLength,
                         String requestRaw, String responseRaw, String notes) {
        this.timestamp = timestamp;
        this.source = source == null ? "Other" : source;
        this.method = method == null ? "" : method;
        this.url = url == null ? "" : url;
        this.status = status;
        this.requestLength = requestLength;
        this.responseLength = responseLength;
        this.requestRaw = requestRaw == null ? "" : requestRaw;
        this.responseRaw = responseRaw == null ? "" : responseRaw;
        this.notes = notes == null ? "" : notes;
    }

    public long timestamp() {
        return timestamp;
    }

    public String source() {
        return source;
    }

    public String method() {
        return method;
    }

    public String url() {
        return url;
    }

    public int status() {
        return status;
    }

    public int requestLength() {
        return requestLength;
    }

    public int responseLength() {
        return responseLength;
    }

    public String requestRaw() {
        return requestRaw;
    }

    public String responseRaw() {
        return responseRaw;
    }

    public String notes() {
        return notes;
    }

    public boolean isFrom(String sourceLabel) {
        return source.equalsIgnoreCase(sourceLabel);
    }

    /**
     * Identity key used to de-duplicate the same exchange seen through two
     * sources (e.g. recorded live AND present in the site map), and to avoid
     * sending the same request to the Organizer twice.
     */
    public String key() {
        return method + " " + url + " " + status + " " + requestLength + "/" + responseLength;
    }

    /** Metadata line for persistence: ts|source|status|reqLen|respLen|method|b64(url)|b64(notes).
     *  url and notes are base64-encoded because they may contain '|'. */
    public String toMetaLine() {
        return timestamp + "|" + source + "|" + status + "|" + requestLength + "|"
                + responseLength + "|" + method + "|" + b64(url) + "|" + b64(notes);
    }

    public static MessageRecord fromMetaLine(String meta, String requestRaw, String responseRaw) {
        String[] parts = meta.split("\\|", 8);
        if (parts.length < 7) {
            return null;
        }
        try {
            return new MessageRecord(
                    Long.parseLong(parts[0]),
                    parts[1],
                    parts[5],
                    parts.length > 6 ? unb64(parts[6]) : "",
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]),
                    Integer.parseInt(parts[4]),
                    requestRaw,
                    responseRaw,
                    parts.length > 7 ? unb64(parts[7]) : "");
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String b64(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String unb64(String value) {
        try {
            return new String(java.util.Base64.getUrlDecoder().decode(value),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
