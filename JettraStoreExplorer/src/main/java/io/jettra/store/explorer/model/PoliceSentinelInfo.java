package io.jettra.store.explorer.model;

import java.io.Serializable;

public class PoliceSentinelInfo implements Serializable {
    private String role;
    private String name;
    private String assignedNode;
    private String status;
    private int healthScore;
    private int totalAlerts;
    private String description;
    private String color;

    public PoliceSentinelInfo() {}

    public PoliceSentinelInfo(String role, String name, String assignedNode, String status, int healthScore, int totalAlerts, String description, String color) {
        this.role = role;
        this.name = name;
        this.assignedNode = assignedNode;
        this.status = status;
        this.healthScore = healthScore;
        this.totalAlerts = totalAlerts;
        this.description = description;
        this.color = color;
    }

    public String getRole() { return role; }
    public String getName() { return name; }
    public String getAssignedNode() { return assignedNode; }
    public String getStatus() { return status; }
    public int getHealthScore() { return healthScore; }
    public int getTotalAlerts() { return totalAlerts; }
    public String getDescription() { return description; }
    public String getColor() { return color; }
}
