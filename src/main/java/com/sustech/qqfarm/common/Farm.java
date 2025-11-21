package com.sustech.qqfarm.common;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Farm implements Serializable {
    private final String owner;
    private int coins;
    private final List<Plot> plots;
    private final Map<String, Integer> stealHistory; // Tracks how many crops a specific thief has stolen from the current ripe batch

    public Farm(String owner) {
        this.owner = owner;
        this.coins = 40;
        this.plots = new ArrayList<>();
        this.stealHistory = new HashMap<>();
        for (int i = 0; i < 16; i++) {
            plots.add(new Plot());
        }
    }

    public String getOwner() {
        return owner;
    }

    public int getCoins() {
        return coins;
    }
    public void setCoins(int coins) {
        this.coins = coins;
    }

    public List<Plot> getPlots() {
        return plots;
    }

    public Map<String, Integer> getStealHistory() {
        return stealHistory;
    }
}
