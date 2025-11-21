package com.sustech.qqfarm.server;

import com.sustech.qqfarm.common.Farm;
import com.sustech.qqfarm.common.Plot;
import com.sustech.qqfarm.common.PlotState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FarmManager {
    private static final FarmManager instance = new FarmManager();
    private final Map<String, Farm> farms = new ConcurrentHashMap<>();
    private final Map<String, String> playerViews = new ConcurrentHashMap<>(); // Track who is looking at what: Map<Viewer, FarmOwner>

    private FarmManager() {}

    public static FarmManager getInstance() {
        return instance;
    }

    public synchronized Farm getOrCreateFarm(String username) {
        return farms.computeIfAbsent(username, Farm::new);
    }

    public Farm getFarm(String username) {
        return farms.get(username);
    }

    // --- PRESENCE TRACKING ---

    public void updatePlayerView(String viewer, String farmOwner) {
        playerViews.put(viewer, farmOwner);
    }

    public void removePlayer(String username) {
        playerViews.remove(username);
    }

    // --- ATOMIC OPERATIONS ---

    public boolean plant(String username, int plotIndex) {
        Farm farm = getFarm(username);
        if (farm == null) return false;

        synchronized (farm) {
            if (farm.getCoins() < 5) return false;
            Plot plot = farm.getPlots().get(plotIndex);
            if (plot.getState() != PlotState.EMPTY) return false;

            farm.setCoins(farm.getCoins() - 5);
            plot.setState(PlotState.GROWING);
            plot.setPlantedTime(System.currentTimeMillis());
            System.out.println("[LOG] " + username + " planted on plot " + plotIndex);
            return true;
        }
    }

    public boolean harvest(String username, int plotIndex) {
        Farm farm = getFarm(username);
        if (farm == null) return false;

        synchronized (farm) {
            Plot plot = farm.getPlots().get(plotIndex);
            if (plot.isReadyToHarvest()) {
                plot.setState(PlotState.RIPE);
            }
            if (plot.getState() != PlotState.RIPE) return false;

            farm.setCoins(farm.getCoins() + 12);
            plot.setState(PlotState.EMPTY);
            System.out.println("[LOG] " + username + " harvested plot " + plotIndex);

            // Clean up history if no ripe crops remain
            checkAndResetHistory(farm);

            return true;
        }
    }

    public String steal(String thiefName, String victimName, int plotIndex) {
        if (thiefName.equals(victimName)) return "Cannot steal from yourself.";
        Farm victimFarm = getFarm(victimName);
        Farm thiefFarm = getFarm(thiefName);

        if (victimFarm == null || thiefFarm == null) return "Farm not found.";

        // --- ENFORCE AWAY RULE ---
        String victimCurrentView = playerViews.get(victimName);
        if (victimCurrentView != null && victimCurrentView.equals(victimName)) {
            return "Owner is watching! Stealing failed.";
        }

        synchronized (victimFarm) {
            // Update states to ensure consistency
            long ripeCount = 0;
            for(Plot p : victimFarm.getPlots()) {
                if(p.isReadyToHarvest()) p.setState(PlotState.RIPE);
                if(p.getState() == PlotState.RIPE) ripeCount++;
            }

            if (ripeCount == 0) return "No ripe crops to steal.";

            // --- ENFORCE 25% RULE ---
            // We verify the limit against (Current Available + Already Stolen By You).
            // This prevents the logic flaw where stealing reduces the count,
            // allowing you to steal again in the next click.
            int stolenByMe = victimFarm.getStealHistory().getOrDefault(thiefName, 0);
            long virtualTotal = ripeCount + stolenByMe;

            // Calculate max allowed based on the state *before* this specific theft attempt
            int maxAllowed = (int) (virtualTotal * 0.25);

            if (stolenByMe >= maxAllowed) {
                return "You have reached the theft limit (25%) for this farm.";
            }

            // --- STEAL SPECIFIC PLOT ---
            if (plotIndex < 0 || plotIndex >= victimFarm.getPlots().size()) {
                return "Invalid plot.";
            }

            Plot targetPlot = victimFarm.getPlots().get(plotIndex);

            if (targetPlot.getState() != PlotState.RIPE) {
                return "Selected plot is not ripe.";
            }

            // Execute Steal
            targetPlot.setState(PlotState.EMPTY);

            // Update History
            victimFarm.getStealHistory().put(thiefName, stolenByMe + 1);

            synchronized (thiefFarm) {
                thiefFarm.setCoins(thiefFarm.getCoins() + 12);
            }

            System.out.println("[LOG] " + thiefName + " stole plot " + plotIndex + " from " + victimName +
                    " (Stolen: " + (stolenByMe + 1) + "/" + maxAllowed + ")");

            // Clean up history if no ripe crops remain (Batch finished)
            checkAndResetHistory(victimFarm);

            return "SUCCESS";
        }
    }

    // Helper to clear steal history when the "batch" of ripe crops is gone
    private void checkAndResetHistory(Farm farm) {
        boolean hasRipe = false;
        for (Plot p : farm.getPlots()) {
            if (p.getState() == PlotState.RIPE) {
                hasRipe = true;
                break;
            }
        }
        if (!hasRipe) {
            farm.getStealHistory().clear();
        }
    }

    public List<String> updateGrowthStates() {
        List<String> changedFarms = new ArrayList<>();
        farms.values().forEach(farm -> {
            boolean changed = false;
            synchronized (farm) {
                for (Plot p : farm.getPlots()) {
                    if (p.getState() == PlotState.GROWING && p.isReadyToHarvest()) {
                        p.setState(PlotState.RIPE);
                        changed = true;
                    }
                }
            }
            if(changed) changedFarms.add(farm.getOwner());
        });
        return changedFarms;
    }
}