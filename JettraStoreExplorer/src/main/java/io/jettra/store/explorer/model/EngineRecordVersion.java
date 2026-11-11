package io.jettra.store.explorer.model;

import java.io.Serializable;

/**
 * Representa una versión histórica inmutable de un registro o documento en JettraStore.
 */
public class EngineRecordVersion implements Serializable {

    private long version;
    private String timestamp;
    private String summary;
    private String details;
    private String author;
    private String changeNote;

    public EngineRecordVersion() {}

    public EngineRecordVersion(long version, String timestamp, String summary, String details, String author, String changeNote) {
        this.version = version;
        this.timestamp = timestamp;
        this.summary = summary;
        this.details = details;
        this.author = author != null ? author : "admin";
        this.changeNote = changeNote != null ? changeNote : "Modificación de registro";
    }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getChangeNote() { return changeNote; }
    public void setChangeNote(String changeNote) { this.changeNote = changeNote; }
}

