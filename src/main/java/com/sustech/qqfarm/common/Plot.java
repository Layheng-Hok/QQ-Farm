package com.sustech.qqfarm.common;

import java.io.Serializable;

public class Plot implements Serializable {
    private PlotState state = PlotState.EMPTY;
    private long plantedTime = 0;

    // Used for logic
    public static final long GROW_TIME_MS = 6000; // 6 seconds

    public PlotState getState() {
        return state;
    }

    public void setState(PlotState state) {
        this.state = state;
    }

    public long getPlantedTime() {
        return plantedTime;
    }

    public void setPlantedTime(long plantedTime) {
        this.plantedTime = plantedTime;
    }

    // Helper to check if actually ripe based on time
    public boolean isReadyToHarvest() {
        return state == PlotState.GROWING &&
                (System.currentTimeMillis() - plantedTime >= GROW_TIME_MS);
    }
}