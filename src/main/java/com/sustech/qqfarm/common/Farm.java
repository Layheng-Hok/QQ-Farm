package com.sustech.qqfarm.common;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Farm implements Serializable {
    private final String owner;
    private int coins;
    private final List<Plot> plots;

    public Farm(String owner) {
        this.owner = owner;
        this.coins = 40;
        this.plots = new ArrayList<>();
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
}