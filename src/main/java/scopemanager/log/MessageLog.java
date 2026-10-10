package scopemanager.log;

import burp.api.montoya.persistence.PersistedObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.function.Predicate;

/**
 * The extension's own request log: every exchange seen by the HTTP handler is
 * recorded here with its tool of origin (Repeater, Intruder, History/Proxy,
 * Scanner, ...). Burp does not keep Repeater/Intruder history via its API,
 * so this log is what makes those requests recoverable - and it is persisted
 * into the Burp project file, so it survives save/reload and restarts.
 *
 * Persistence: ONE plain string blob ("v2", one base64 triple per line).
 * NOTE: PersistedList&lt;String&gt; is deliberately NOT used - Burp returns
 * internal wrapper objects instead of Strings when reading a persisted
 * string list back (ClassCastException), so plain setString/getString is
 * the only reliable channel.
 *
 * Flushes are throttled (every {@link #FLUSH_EVERY} records + on extension
 * unload) to keep traffic handling fast.
 */
public final class MessageLog {

    public static final int MAX_ENTRIES = 1500;
    public static final int FLUSH_EVERY = 250;
    public static final int MAX_RAW_CHARS = 50_000;

    // Preserve the original keys when the extension is renamed.
    private static final String KEY_LOG = "scopehighlighter.log.v2";
    private static final String[] LEGACY_LIST_KEYS = {
            "scopehighlighter.log.meta", "scopehighlighter.log.req", "scopehighlighter.log.resp"};

    private final List<MessageRecord> records = new ArrayList<>();
    private PersistedObject persistedData;
    private int sinceFlush = 0;
    private final java.util.concurrent.atomic.AtomicBoolean saveRunning =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean savePending =
            new java.util.concurrent.atomic.AtomicBoolean();

    // ------------------------------------------------------------------
    // persistence
    // ------------------------------------------------------------------

    public synchronized void attach(PersistedObject data) {
        this.persistedData = data;
        deleteLegacyLists();
        String blob = data.getString(KEY_LOG);
        if (blob != null && !blob.isEmpty()) {
            loadFromBlob(blob);
        }
    }

    /**
     * Removes the v1 persisted string lists: their elements come back as
     * Burp-internal wrapper objects and cannot be read as Strings.
     */
    private void deleteLegacyLists() {
        for (String key : LEGACY_LIST_KEYS) {
            try {
                persistedData.deleteStringList(key);
            } catch (Exception ignored) {
                // key may not exist / older Burp builds may differ - harmless
            }
        }
    }

    public void saveNow() {
        PersistedObject data;
        List<MessageRecord> snapshot;
        synchronized (this) {
            data = persistedData;
            snapshot = new ArrayList<>(records);
            sinceFlush = 0;
        }
        if (data == null) {
            return;
        }
        data.setString(KEY_LOG, toBlob(snapshot));
    }

    private void saveLater() {
        savePending.set(true);
        if (!saveRunning.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                do {
                    savePending.set(false);
                    saveNow();
                } while (savePending.get());
            } finally {
                saveRunning.set(false);
                if (savePending.get()) {
                    saveLater();
                }
            }
        }, "scope-manager-save");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Blob format:
     *   v2
     *   &lt;b64 meta&gt;\t&lt;b64 request&gt;\t&lt;b64 response&gt;   (one line per record)
     * with meta = ts|source|status|reqLen|respLen|method|url.
     */
    public synchronized String toBlob() {
        return toBlob(new ArrayList<>(records));
    }

    private static String toBlob(List<MessageRecord> snapshot) {
        StringBuilder sb = new StringBuilder("v2\n");
        for (MessageRecord record : snapshot) {
            sb.append(b64(record.toMetaLine())).append('\t')
                    .append(b64(record.requestRaw())).append('\t')
                    .append(b64(record.responseRaw())).append('\n');
        }
        return sb.toString();
    }

    public synchronized void loadFromBlob(String blob) {
        records.clear();
        if (blob == null || blob.isEmpty()) {
            return;
        }
        for (String line : blob.split("\n")) {
            if (line.isEmpty() || line.equals("v2") || line.startsWith("v1")) {
                continue;
            }
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) {
                continue;
            }
            MessageRecord record = MessageRecord.fromMetaLine(
                    unb64(parts[0]), unb64(parts[1]), unb64(parts[2]));
            if (record != null) {
                records.add(record);
            }
        }
        trim();
    }

    private static String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // recording / reading
    // ------------------------------------------------------------------

    public void record(MessageRecord record) {
        boolean shouldSave;
        synchronized (this) {
            records.add(record);
            sinceFlush++;
            trim();
            shouldSave = sinceFlush >= FLUSH_EVERY;
        }
        if (shouldSave) {
            saveLater();
        }
    }

    /**
     * Updates every copy of an exchange. The same request can be visible in
     * more than one Burp tool, but its note is a single user-owned value.
     */
    public int updateNotes(String recordKey, String notes) {
        int updated = 0;
        String value = notes == null ? "" : notes;
        synchronized (this) {
            for (int i = 0; i < records.size(); i++) {
                MessageRecord record = records.get(i);
                if (record.key().equals(recordKey) && !record.notes().equals(value)) {
                    records.set(i, record.withNotes(value));
                    updated++;
                }
            }
        }
        if (updated > 0) {
            saveLater();
        }
        return updated;
    }

    /** Pull a note changed in a live Burp view into the persisted log. */
    public synchronized void mergeNotes(MessageRecord authoritative) {
        if (authoritative == null) {
            return;
        }
        updateNotes(authoritative.key(), authoritative.notes());
    }

    /**
     * Batch version used while reading Burp histories. It indexes the live
     * records once, scans the local log once, and persists at most once.
     */
    public int mergeNotes(List<MessageRecord> authoritativeRecords) {
        if (authoritativeRecords == null || authoritativeRecords.isEmpty()) {
            return 0;
        }
        Map<String, String> notesByKey = new HashMap<>();
        for (MessageRecord record : authoritativeRecords) {
            if (record != null) {
                notesByKey.put(record.key(), record.notes());
            }
        }
        int updated = 0;
        synchronized (this) {
            for (int i = 0; i < records.size(); i++) {
                MessageRecord record = records.get(i);
                String notes = notesByKey.get(record.key());
                if (notes != null && !record.notes().equals(notes)) {
                    records.set(i, record.withNotes(notes));
                    updated++;
                }
            }
        }
        if (updated > 0) {
            saveLater();
        }
        return updated;
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
        return raw.substring(0, MAX_RAW_CHARS) + "\n...[truncated by Scope Manager]";
    }
}
