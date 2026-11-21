package io.jettra.core.three.d.model;

public class WorldEvent {
    public String message;
    public float timestamp;
    public int r, g, b, a = 255;

    public WorldEvent() {}
    public WorldEvent(String msg, float ts, int r, int g, int b) {
        this.message = msg;
        this.timestamp = ts;
        this.r = r; this.g = g; this.b = b;
    }
}
