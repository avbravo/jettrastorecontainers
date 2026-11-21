package io.jettra.core.three.d.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.HashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MegaProject {
    public float x, y, z;
    public float progress;
    public Map<String, Boolean> builders = new HashMap<>();
    public int type; // 0: House, 1: School, 2: City Center
    public boolean finished;

    public MegaProject() {}
}
