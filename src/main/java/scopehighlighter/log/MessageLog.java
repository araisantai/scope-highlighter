package scopehighlighter.log;

import burp.api.montoya.persistence.PersistedList;
import burp.api.montoya.persistence.PersistedObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The extension's own request log: every exchange seen by the HTTP handler is
 * recorded here with its tool of origin (Repeater, Intruder, History/Proxy,
 * Scanner, ...). Burp does not keep Repeater/Intruder history via its API,
 * so this log is what makes those requests recoverable - and it is persisted
 * into the Burp project file, so it survives save/reload and restarts.
 *
 * Persistence: three parallel string lists (meta / request / response) so no
 * serialization of message objects is needed. Flushes are throttled (every
 * {@link #FLUSH_EVERY} records + on extension unload) to keep traffic fast.
 */
public final class MessageLog {

    public static final int MAX_ENTRIES = 3000;
    public static final int FLUSH_EVERY = 50;
    public static final int MAX_RAW_CHARS = 200_000;

    private static final String KEY_META = "scopehighlighter.log.meta";
    private static final String KEY_REQ = "scopehighlighter.log.req";
    private static final String KEY_RESP = "scopehighlighter.log.resp";

    private final List<MessageRecord> records = new ArrayList<>();
    private PersistedObject persistedData;
    private int sinceFlush = 0;

    // ------------------------------------------------------------------
    // persistence
    // ------------------------------------------------------------------

    public synchronized void attach(PersistedObject data) {
        this.persistedData = data;
        List<String> meta = toStringList(data.getStringList(KEY_META));
        List<String> req = toStringList(data.getStringList(KEY_REQ));
        List<String> resp = toStringList(data.getStringList(KEY_RESP));
        loadFromStrings(meta, req, resp);
    }

    public synchronized void saveNow() {
        if (persistedData == null) {
            return;
        }
        PersistedList<String> meta = PersistedList.persistedStringList();
        PersistedList<String> req = PersistedList.persistedStringList();
        PersistedList<String> resp = PersistedList.persistedStringList();
        synchronized (this) {
            for (MessageRecord record : records) {
                meta.add(record.toMetaLine());
                req.add(record.requestRaw());
                resp.add(record.responseRaw());
            }
        }
        persistedData.setStringList(KEY_META, meta);
        persistedData.setStringList(KEY_REQ, req);
        persistedData.setStringList(KEY_RESP, resp);
        sinceFlush = 0;
    }

    /** Round-trip format used for persistence and tests. */
    public synchronized void loadFromStrings(List<String> meta, List<String> requestRaw, List<String> responseRaw) {
        records.clear();
        int count = Math.min(meta.size(), Math.min(requestRaw.size(), responseRaw.size()));
        for (int i = 0; i < count; i++) {
            MessageRecord record = MessageRecord.fromMetaLine(meta.get(i), requestRaw.get(i), responseRaw.get(i));
            if (record != null) {
                records.add(record);
            }
        }
        trim();
    }

    public synchronized List<List<String>> saveToStrings() {
        List<String> meta = new ArrayList<>();
        List<String> req = new ArrayList<>();
        List<String> resp = new ArrayList<>();
        for (MessageRecord record : records) {
            meta.add(record.toMetaLine());
            req.add(record.requestRaw());
            resp.add(record.responseRaw());
        }
        List<List<String>> out = new ArrayList<>();
        out.add(meta);
        out.add(req);
        out.add(resp);
        return out;
    }

    // ------------------------------------------------------------------
    // recording / reading
    // ------------------------------------------------------------------

    public synchronized void record(MessageRecord record) {
        records.add(record);
        sinceFlush++;
        trim();
        if (sinceFlush >= FLUSH_EVERY) {
            saveNow();
        }
    }

    public synchronized int size() {
        return records.size();
    }

    /** All records, chronological (oldest first). */
    public synchronized List<MessageRecord> entries() {
        return new ArrayList<>(records);
    }

    public synchronized List<MessageRecord> filter(Predicate<MessageRecord> predicate) {
        List<MessageRecord> out = new ArrayList<>();
        for (MessageRecord record : records) {
            if (predicate.test(record)) {
                out.add(record);
            }
        }
        return out;
    }

    private void trim() {
        while (records.size() > MAX_ENTRIES) {
            records.remove(0);
        }
    }

    /** Truncates oversized bodies so a single huge response cannot blow up the project file. */
    public static String clampRaw(String raw) {
        if (raw == null) {
            return "";
        }
        if (raw.length() <= MAX_RAW_CHARS) {
            return raw;
        }
        return raw.substring(0, MAX_RAW_CHARS) + "\n...[truncated by Scope Highlighter]";
    }

    private static List<String> toStringList(PersistedList<String> persisted) {
        List<String> out = new ArrayList<>();
        if (persisted != null) {
            out.addAll(persisted);
        }
        return out;
    }
}
