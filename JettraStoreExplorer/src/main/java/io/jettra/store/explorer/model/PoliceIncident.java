package io.jettra.store.explorer.model;

import java.io.Serializable;

public class PoliceIncident implements Serializable {
    private String timestamp;
    private String sentinelName;
    private String eventDescription;
    private String severity; // OK, INFO, WARN, CRITICAL
    private String actionTaken;

    public PoliceIncident() {}

    public PoliceIncident(String timestamp, String sentinelName, String eventDescription, String severity, String actionTaken) {
        this.timestamp = timestamp;
        this.sentinelName = sentinelName;
        this.eventDescription = eventDescription;
        this.severity = severity;
        this.actionTaken = actionTaken;
    }

    public String getTimestamp() { return timestamp; }
    public String getSentinelName() { return sentinelName; }
    public String getEventDescription() { return eventDescription; }
    public String getSeverity() { return severity; }
    public String getActionTaken() { return actionTaken; }
}
