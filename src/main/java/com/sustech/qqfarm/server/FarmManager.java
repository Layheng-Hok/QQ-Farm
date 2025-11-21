package com.sustech.qqfarm.server;

import com.sustech.qqfarm.common.Farm;
import com.sustech.qqfarm.common.Plot;
import com.sustech.qqfarm.common.PlotState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FarmManager {
    // Singleton instance
    private static final FarmManager instance = new FarmManager();
    // Store farms: Username -> Farm
    private final Map<String, Farm> farms = new ConcurrentHashMap<>();

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

    // --- ATOMIC OPERATIONS ---

    public boolean plant(String username, int plotIndex) {
        Farm farm = getFarm(username);
        if (farm == null) return false;

        synchronized (farm) {
            if (farm.getCoins() < 5) return false;
            Plot plot = farm.getPlots().get(plotIndex);
            if (plot.getState() != PlotState.EMPTY) return false;

            // Transaction
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
            // Check dynamic state
            if (plot.isReadyToHarvest()) {
                plot.setState(PlotState.RIPE); // Ensure it's marked ripe internally
            }

            if (plot.getState() != PlotState.RIPE) return false;

            // Transaction
            farm.setCoins(farm.getCoins() + 12);
            plot.setState(PlotState.EMPTY);
            System.out.println("[LOG] " + username + " harvested plot " + plotIndex);
            return true;
        }
    }

    public String steal(String thiefName, String victimName) {
        if (thiefName.equals(victimName)) return "Cannot steal from yourself.";
        Farm victimFarm = getFarm(victimName);
        Farm thiefFarm = getFarm(thiefName);

        if (victimFarm == null || thiefFarm == null) return "Farm not found.";

        // Lock the victim farm to prevent race conditions (double stealing)
        synchronized (victimFarm) {
            long ripeCount = victimFarm.getPlots().stream()
                    .filter(p -> p.getState() == PlotState.RIPE || p.isReadyToHarvest())
                    .count();

            // Update states if they were "physically" growing but "logically" ripe
            for(Plot p : victimFarm.getPlots()) {
                if(p.isReadyToHarvest()) p.setState(PlotState.RIPE);
            }

            if (ripeCount == 0) return "No ripe crops to steal.";

            // Rule: Not exceed 25% of CURRENTLY ripe plots
            int maxStealable = (int) (ripeCount * 0.25);

            // Teacher Q&A: If 8 ripe -> steal max 2. If < 4 ripe, maxStealable is 0.
            if (maxStealable < 1) {
                return "Too few crops to steal (Protection Active).";
            }

            // Steal exactly 1 plot per action as per standard gameplay mechanics implies
            // usually clicking one plot, but here we automate "steal from farm".
            // Let's pick the first RIPE plot and steal it.

            for (int i = 0; i < 16; i++) {
                Plot p = victimFarm.getPlots().get(i);
                if (p.getState() == PlotState.RIPE) {
                    // Steal logic
                    p.setState(PlotState.EMPTY); // Crop is gone/reduced

                    // Update Thief (Needs locking thief too, but deadlock risk if not ordered.
                    // Since we only add coins to thief, strict locking isn't as critical as victim state,
                    // but for correctness:)
                    synchronized (thiefFarm) {
                        thiefFarm.setCoins(thiefFarm.getCoins() + 12);
                    }

                    System.out.println("[LOG] " + thiefName + " stole from " + victimName + " plot " + i);
                    return "SUCCESS";
                }
            }
        }
        return "Steal failed.";
    }

    // Background thread logic to update states
    public void updateGrowthStates() {
        farms.values().forEach(farm -> {
            synchronized (farm) {
                for (Plot p : farm.getPlots()) {
                    if (p.getState() == PlotState.GROWING && p.isReadyToHarvest()) {
                        p.setState(PlotState.RIPE);
                    }
                }
            }
        });
    }
}