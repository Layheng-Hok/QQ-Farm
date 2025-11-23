package com.sustech.qqfarm.server;

import com.sustech.qqfarm.common.Command;
import com.sustech.qqfarm.common.Farm;
import com.sustech.qqfarm.common.NetMessage;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

public class GameServer {
    private static final int PORT = 6969;
    public static ConcurrentHashMap<String, ObjectOutputStream> onlineClients = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        System.out.println("Starting QQ Farm Server on port " + PORT);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            List<String> updatedOwners = FarmManager.getInstance().updateGrowthStates();
            if (!updatedOwners.isEmpty()) {
                for (String owner : updatedOwners) {
                    Farm updatedFarm = FarmManager.getInstance().getFarm(owner);
                    NetMessage updateMsg = new NetMessage(Command.UPDATE);
                    updateMsg.setMessage("Farm Updated");
                    updateMsg.setData(updatedFarm);
                    updateMsg.setOwnerWatching(FarmManager.getInstance().playerViews.get(owner) != null && FarmManager.getInstance().playerViews.get(owner).equals(owner));
                    notifyFarmViewers(owner, updateMsg);
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            ExecutorService pool = Executors.newCachedThreadPool();
            while (true) {
                Socket client = serverSocket.accept();
                pool.execute(new ClientHandler(client));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void notifyFarmViewers(String farmOwner, NetMessage msg) {
        for (Map.Entry<String, String> entry : FarmManager.getInstance().playerViews.entrySet()) {
            if (entry.getValue().equals(farmOwner)) {
                String viewer = entry.getKey();
                ObjectOutputStream out = onlineClients.get(viewer);
                if (out != null) {
                    try {
                        synchronized (out) {
                            out.writeObject(msg);
                            out.flush();
                            out.reset();
                        }
                    } catch (IOException e) {
                        // Handle in thread
                    }
                }
            }
        }
    }
}
