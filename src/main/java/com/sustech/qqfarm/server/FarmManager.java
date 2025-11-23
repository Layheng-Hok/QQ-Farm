package com.sustech.qqfarm.server;

import com.sustech.qqfarm.common.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FarmManager {
    private static final FarmManager instance = new FarmManager();
    private final Map<String, Farm> farms = new ConcurrentHashMap<>();
    final Map<String, String> playerViews = new ConcurrentHashMap<>();

    private FarmManager() {
    }

    public static FarmManager getInstance() {
        return instance;
    }

    public synchronized Farm getOrCreateFarm(String username) {
        return farms.computeIfAbsent(username, Farm::new);
    }

    public Farm getFarm(String username) {
        return farms.get(username);
    }

    public Set<String> getAllPlayers() {
        return farms.keySet();
    }

    public void updatePlayerView(String viewer, String farmOwner) {
        String old = playerViews.get(viewer);
        playerViews.put(viewer, farmOwner);
        boolean wasOnOwn = old != null && old.equals(viewer);
        boolean nowOnOwn = farmOwner.equals(viewer);
        if (wasOnOwn != nowOnOwn) {
            NetMessage update = new NetMessage(Command.UPDATE);
            update.setMessage("View Update");
            update.setData(getFarm(viewer));
            update.setOwnerWatching(nowOnOwn);
            GameServer.broadcast(update);
        }
    }

    public void removePlayer(String username) {
        playerViews.remove(username);
        Farm f = getFarm(username);
        if (f != null) {
            NetMessage update = new NetMessage(Command.UPDATE);
            update.setData(f);
            update.setOwnerWatching(false);
            GameServer.broadcast(update);
        }
    }

    // --- ATOMIC OPERATIONS ---

    public String plant(String username, int plotIndex) {
        Farm farm = getFarm(username);
        if (farm == null) return "Farm not found.";

        synchronized (farm) {
            // Specific check for balance
            if (farm.getCoins() < 5) {
                return "Not enough coins to plant (Need 5).";
            }

            Plot plot = farm.getPlots().get(plotIndex);
            if (plot.getState() != PlotState.EMPTY) {
                return "Plot is not empty.";
            }

            farm.setCoins(farm.getCoins() - 5);
            plot.setState(PlotState.GROWING);
            plot.setPlantedTime(System.currentTimeMillis());
            System.out.println("[LOG] " + username + " planted on plot " + plotIndex);
            return "SUCCESS";
        }
    }

    public String harvest(String username, int plotIndex) {
        Farm farm = getFarm(username);
        if (farm == null) return "FAIL";

        synchronized (farm) {
            Plot plot = farm.getPlots().get(plotIndex);
            if (plot.isReadyToHarvest()) {
                plot.setState(PlotState.RIPE);
            }
            boolean isRipe = plot.getState() == PlotState.RIPE;
            boolean isStolen = plot.getState() == PlotState.STOLEN;
            if (!isRipe && !isStolen) return "FAIL";

            if (isRipe) {
                farm.setCoins(farm.getCoins() + 12);
            } // else if isStolen, +0

            plot.setState(PlotState.EMPTY);
            System.out.println("[LOG] " + username + " harvested plot " + plotIndex);
            checkAndResetHistory(farm);
            return isRipe ? "SUCCESS_HARVEST" : "SUCCESS_CLEAN";
        }
    }

    public String steal(String thiefName, String victimName, int plotIndex) {
        if (thiefName.equals(victimName)) return "Cannot steal from yourself.";

        Farm victimFarm = getFarm(victimName);
        Farm thiefFarm = getFarm(thiefName);
        if (victimFarm == null || thiefFarm == null) return "Farm not found.";

        String victimCurrentView = playerViews.get(victimName);
        if (victimCurrentView != null && victimCurrentView.equals(victimName)) {
            return "Owner is watching! Stealing failed.";
        }

        synchronized (victimFarm) {
            long ripeCount = 0;
            for (Plot p : victimFarm.getPlots()) {
                if (p.isReadyToHarvest()) p.setState(PlotState.RIPE);
                if (p.getState() == PlotState.RIPE) ripeCount++;
            }
            if (ripeCount == 0) return "No ripe crops to steal.";

            int stolenByMe = victimFarm.getStealHistory().getOrDefault(thiefName, 0);
            long virtualTotal = ripeCount + stolenByMe;
            int maxAllowed = (int) (virtualTotal * 0.25);
            if (stolenByMe >= maxAllowed) {
                return "You have reached the theft limit (25%) for this farm.";
            }

            if (plotIndex < 0 || plotIndex >= victimFarm.getPlots().size()) return "Invalid plot.";

            Plot targetPlot = victimFarm.getPlots().get(plotIndex);
            if (targetPlot.getState() != PlotState.RIPE) return "Selected plot is not ripe.";

            targetPlot.setState(PlotState.STOLEN);
            victimFarm.getStealHistory().put(thiefName, stolenByMe + 1);

            synchronized (thiefFarm) {
                thiefFarm.setCoins(thiefFarm.getCoins() + 12);
            }

            System.out.println("[LOG] " + thiefName + " stole plot " + plotIndex + " from " + victimName);
            checkAndResetHistory(victimFarm);
            return "SUCCESS";
        }
    }

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
            if (changed) changedFarms.add(farm.getOwner());
        });
        return changedFarms;
    }
}
