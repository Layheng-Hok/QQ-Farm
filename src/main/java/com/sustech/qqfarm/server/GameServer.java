package com.sustech.qqfarm.server;

import com.sustech.qqfarm.common.Command;
import com.sustech.qqfarm.common.Farm;
import com.sustech.qqfarm.common.NetMessage;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
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
                    broadcast(updateMsg);
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

    public static void broadcast(NetMessage msg) {
        onlineClients.forEach((user, out) -> {
            try {
                synchronized (out) {
                    out.writeObject(msg);
                    out.flush();
                    out.reset();
                }
            } catch (IOException e) {
                // Handle in thread
            }
        });
    }

}

class ClientHandler implements Runnable {

    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private String currentUser;

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try {
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());

            while (true) {
                Object obj = in.readObject();
                if (obj instanceof NetMessage) {
                    NetMessage request = (NetMessage) obj;
                    NetMessage response = handleRequest(request);
                    if (response != null) {
                        send(response);
                    }
                }
            }
        } catch (EOFException | java.net.SocketException e) {
            System.out.println("Client disconnected: " + currentUser);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (currentUser != null) {
                GameServer.onlineClients.remove(currentUser);
                FarmManager.getInstance().removePlayer(currentUser);
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private NetMessage handleRequest(NetMessage req) {
        FarmManager fm = FarmManager.getInstance();
        NetMessage res = new NetMessage(req.getCommand());
        res.setSuccess(true);

        switch (req.getCommand()) {
            case LOGIN:
                currentUser = (String) req.getData();
                GameServer.onlineClients.put(currentUser, out);
                fm.getOrCreateFarm(currentUser);
                fm.updatePlayerView(currentUser, currentUser);
                res.setMessage("Logged in as " + currentUser);
                res.setData(fm.getFarm(currentUser));
                break;

            case GET_FARM:
                String target = req.getTargetUser() != null ? req.getTargetUser() : currentUser;
                fm.updatePlayerView(currentUser, target);
                Farm f = fm.getFarm(target);
                if (f == null) {
                    res.setSuccess(false);
                    res.setMessage("Farm not found");
                } else {
                    res.setData(f);
                }
                break;

            case PLANT:
                int pIdx = (Integer) req.getData();
                // FIX: Handle String return for specific error
                String pResult = fm.plant(currentUser, pIdx);
                if ("SUCCESS".equals(pResult)) {
                    res.setSuccess(true);
                    res.setMessage("Planted successfully");
                    broadcastUpdate(currentUser);
                } else {
                    res.setSuccess(false);
                    res.setMessage(pResult); // "Not enough coins..."
                }
                res.setData(fm.getFarm(currentUser));
                break;

            case HARVEST:
                int hIdx = (Integer) req.getData();
                boolean hOk = fm.harvest(currentUser, hIdx);
                res.setSuccess(hOk);
                res.setMessage(hOk ? "Harvested! +12 Coins" : "Not ripe yet");
                res.setData(fm.getFarm(currentUser));
                if (hOk) broadcastUpdate(currentUser);
                break;

            case STEAL:
                String victim = req.getTargetUser();
                int sIdx = (Integer) req.getData();
                String result = fm.steal(currentUser, victim, sIdx);
                if ("SUCCESS".equals(result)) {
                    res.setSuccess(true);
                    res.setMessage("You stole a crop! +12 Coins");
                    broadcastUpdate(victim);
                } else {
                    res.setSuccess(false);
                    res.setMessage(result);
                }
                // Return victim's farm to update view
                res.setData(fm.getFarm(victim));
                break;

            case GET_PLAYERS:
                List<String> players = new ArrayList<>(fm.getAllPlayers());
                players.remove(currentUser);
                res.setData(players);
                break;
        }

        // Always attach the current user's coin balance to the response
        // This ensures the UI shows MY coins even when looking at OTHER farms.
        if (currentUser != null) {
            Farm myFarm = fm.getFarm(currentUser);
            if (myFarm != null) {
                res.setUserCoins(myFarm.getCoins());
            }
        }

        return res;
    }

    private void send(NetMessage msg) {
        try {
            out.writeObject(msg);
            out.flush();
            out.reset();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void broadcastUpdate(String ownerName) {
        NetMessage update = new NetMessage(Command.UPDATE);
        update.setMessage("Farm Updated");
        update.setData(FarmManager.getInstance().getFarm(ownerName));
        GameServer.broadcast(update);
    }

}
