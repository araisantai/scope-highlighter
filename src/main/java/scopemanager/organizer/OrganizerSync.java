package scopemanager.organizer;

import burp.api.montoya.persistence.PersistedObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Remembers which requests were already sent to Burp's Organizer, so the
 * automatic "notes to organizer" sync can run continuously without filling
 * the Organizer with duplicates. Persisted in the Burp project file.
 */
public final class OrganizerSync {

    // Preserve deduplication state in Burp projects created before the rename.
    private static final String KEY_SENT = "scopehighlighter.organizer.sent.v1";
    private static final int MAX_KEYS = 5000;
    private static final int FLUSH_EVERY = 25;

    private final Set<String> sent = new LinkedHashSet<>();
    private PersistedObject persistedData;
    private int sinceFlush = 0;

    /** A single-line, bounded key; notes can contain newlines and be arbitrarily long. */
    public static String keyFor(String requestKey, String notes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(requestKey.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(notes.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public synchronized void attach(PersistedObject data) {
        this.persistedData = data;
        String blob = data.getString(KEY_SENT);
        if (blob != null && !blob.isEmpty()) {
            for (String key : blob.split("\n")) {
                if (!key.isEmpty()) {
                    sent.add(key);
                }
            }
            trim();
        }
    }

    public synchronized void saveNow() {
        if (persistedData != null) {
            persistedData.setString(KEY_SENT, String.join("\n", sent));
            sinceFlush = 0;
        }
    }

    /**
     * Returns true when this key has not been sent yet (and remembers it),
     * false when it was already sent before.
     */
    public synchronized boolean markSent(String key) {
        if (sent.contains(key)) {
            return false;
        }
        sent.add(key);
        trim();
        sinceFlush++;
        if (sinceFlush >= FLUSH_EVERY) {
            saveNow();
        }
        return true;
    }

    public synchronized boolean alreadySent(String key) {
        return sent.contains(key);
    }

    public synchronized int size() {
        return sent.size();
    }

    /** For tests: the persisted representation. */
    public synchronized String toBlob() {
        return String.join("\n", sent);
    }

    public synchronized void loadFromBlob(String blob) {
        sent.clear();
        if (blob != null && !blob.isEmpty()) {
            for (String key : blob.split("\n")) {
                if (!key.isEmpty()) {
                    sent.add(key);
                }
            }
        }
        trim();
    }

    private void trim() {
        List<String> snapshot = new ArrayList<>(sent);
        while (snapshot.size() > MAX_KEYS) {
            String oldest = snapshot.remove(0);
            sent.remove(oldest);
        }
    }
}
