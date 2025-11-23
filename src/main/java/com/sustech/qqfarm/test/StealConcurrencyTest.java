package com.sustech.qqfarm.test;

import com.sustech.qqfarm.common.Farm;
import com.sustech.qqfarm.common.PlotState;
import com.sustech.qqfarm.server.FarmManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class StealConcurrencyTest {
    public static void main(String[] args) throws InterruptedException {
        System.out.println(">>> INITIALIZING REAL OBJECTS STRESS TEST (MULTI-PLANT) <<<");

        FarmManager manager = FarmManager.getInstance();

        // 1. Setup Participants
        String victim = "PlayerC";
        String thiefA = "ClientA";
        String thiefB = "ClientB";

        manager.getOrCreateFarm(victim);
        manager.getOrCreateFarm(thiefA);
        manager.getOrCreateFarm(thiefB);

        // 2. "God Mode" Setup: Prepare MULTIPLE ripe crops
        // Reason: The game logic requires that we don't exceed ~25% theft.
        // If we only have 1 ripe plant, the limit is 0. We need at least 4.
        Farm victimFarm = manager.getFarm(victim);

        System.out.println("--- SETTING UP 5 RIPE PLANTS ---");
        for (int i = 0; i < 5; i++) {
            victimFarm.getPlots().get(i).setState(PlotState.RIPE);
        }

        System.out.println("--- PRE-TEST STATE ---");
        System.out.println("Victim Plot 0: " + victimFarm.getPlots().get(0).getState());
        System.out.println("Thief A Coins: " + manager.getFarm(thiefA).getCoins());
        System.out.println("Thief B Coins: " + manager.getFarm(thiefB).getCoins());

        // 3. The Concurrency Harness
        // Both threads will target Plot Index 0 specifically
        int targetPlotIndex = 0;

        ExecutorService service = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);

        Runnable taskA = () -> {
            try {
                latch.await();
                String result = manager.steal(thiefA, victim, targetPlotIndex);
                System.out.println("Thief A Request Result: " + result);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        };

        Runnable taskB = () -> {
            try {
                latch.await();
                String result = manager.steal(thiefB, victim, targetPlotIndex);
                System.out.println("Thief B Request Result: " + result);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        };

        // 4. Execute
        service.submit(taskA);
        service.submit(taskB);

        System.out.println(">>> FIRING SIMULTANEOUS STEAL REQUESTS ON PLOT " + targetPlotIndex + " <<<");
        latch.countDown();

        service.shutdown();
        service.awaitTermination(2, TimeUnit.SECONDS);

        // 5. Validate Results
        System.out.println("--- POST-TEST RESULTS ---");
        PlotState finalState = manager.getFarm(victim).getPlots().get(targetPlotIndex).getState();
        int coinsA = manager.getFarm(thiefA).getCoins();
        int coinsB = manager.getFarm(thiefB).getCoins();

        System.out.println("Victim Plot " + targetPlotIndex + " State: " + finalState);
        System.out.println("Thief A Coins: " + coinsA);
        System.out.println("Thief B Coins: " + coinsB);

        // 6. Assertion Logic
        boolean stateCorrect = (finalState == PlotState.STOLEN);

        // Check that one succeeded (52 coins) and one failed (40 coins)
        // Note: The failure reason might be "Already stolen" OR "Limit reached" depending on timing,
        // but crucially, they shouldn't BOTH have 52 coins.
        boolean aWon = (coinsA == 52);
        boolean bWon = (coinsB == 52);

        if (stateCorrect && (aWon ^ bWon)) {
            System.out.println("\n[SUCCESS] Race condition handled. Only one thief got the loot.");
        } else if (aWon && bWon) {
            System.out.println("\n[FAILURE] Both thieves stole the same crop! (Double Spend)");
        } else {
            System.out.println("\n[FAILURE] Neither succeeded? Check logic limits.");
        }
    }
}
