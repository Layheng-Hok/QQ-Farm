package com.sustech.qqfarm.server;

import com.sustech.qqfarm.common.Command;
import com.sustech.qqfarm.common.Farm;
import com.sustech.qqfarm.common.NetMessage;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

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
                res.setOwnerWatching(true);
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
                    // FIX: Added null check. If target is disconnected, they are not in playerViews.
                    String viewer = fm.playerViews.get(target);
                    res.setOwnerWatching(viewer != null && viewer.equals(target));
                }
                break;
            case PLANT:
                int pIdx = (Integer) req.getData();
                // FIX: Handle String return for specific error
                String pResult = fm.plant(currentUser, pIdx);
                if ("SUCCESS".equals(pResult)) {
                    res.setSuccess(true);
                    res.setMessage("Planted successfully");
                    notifyFarmViewers(currentUser, new NetMessage(Command.UPDATE));
                } else {
                    res.setSuccess(false);
                    res.setMessage(pResult); // "Not enough coins..."
                }
                res.setData(fm.getFarm(currentUser));
                break;
            case HARVEST:
                int hIdx = (Integer) req.getData();
                String hResult = String.valueOf(fm.harvest(currentUser, hIdx));
                if (hResult.equals("SUCCESS_HARVEST")) {
                    res.setSuccess(true);
                    res.setMessage("Harvested! +12 Coins");
                    notifyFarmViewers(currentUser, new NetMessage(Command.UPDATE));
                } else if (hResult.equals("SUCCESS_CLEAN")) {
                    res.setSuccess(true);
                    res.setMessage("Cleaned up stolen crop.");
                    notifyFarmViewers(currentUser, new NetMessage(Command.UPDATE));
                } else {
                    res.setSuccess(false);
                    res.setMessage("Not ripe yet");
                }
                res.setData(fm.getFarm(currentUser));
                break;
            case STEAL:
                String victim = req.getTargetUser();
                int sIdx = (Integer) req.getData();
                String result = fm.steal(currentUser, victim, sIdx);
                if ("SUCCESS".equals(result)) {
                    res.setSuccess(true);
                    res.setMessage("You stole a crop! +12 Coins");
                    notifyFarmViewers(victim, new NetMessage(Command.UPDATE));
                } else {
                    res.setSuccess(false);
                    res.setMessage(result);
                }
                // Return victim's farm to update view
                res.setData(fm.getFarm(victim));
                res.setOwnerWatching(fm.playerViews.getOrDefault(victim, "").equals(victim));
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

    private void notifyFarmViewers(String ownerName, NetMessage update) {
        update.setMessage("Farm Updated");
        update.setData(FarmManager.getInstance().getFarm(ownerName));
        String currentView = FarmManager.getInstance().playerViews.get(ownerName);
        update.setOwnerWatching(currentView != null && currentView.equals(ownerName));
        GameServer.notifyFarmViewers(ownerName, update);
    }
}
