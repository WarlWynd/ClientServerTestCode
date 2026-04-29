package com.game.shared;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A single placed object instance in a WorldDef scene. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorldObject {

    public String instanceId;   // UUID — unique per placed instance
    public String modelId;      // asset catalog key (e.g. "barrel", "torch")
    public float  x, y, z;     // world-space position
    public float  yaw;          // rotation in degrees around Y axis
    public float  scale;        // uniform scale factor

    /** Required by Jackson */
    public WorldObject() {
        this.scale = 1f;
    }

    public WorldObject(String instanceId, String modelId,
                       float x, float y, float z, float yaw, float scale) {
        this.instanceId = instanceId;
        this.modelId    = modelId;
        this.x          = x;
        this.y          = y;
        this.z          = z;
        this.yaw        = yaw;
        this.scale      = scale;
    }
}
