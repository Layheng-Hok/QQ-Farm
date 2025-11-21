package com.sustech.qqfarm.common;

import java.io.Serializable;

// Data Transfer Object for all communication
public class NetMessage implements Serializable {
    private final Command command;
    private boolean success;
    private String message;
    private Object data; // Can hold Farm, String (username), or Integer (index)
    private String targetUser;
    private int userCoins = -1; // Explicitly carry the requester's coin balance

    public NetMessage(Command command) { this.command = command; }

    public Command getCommand() {
        return command;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public String getTargetUser() {
        return targetUser;
    }

    public void setTargetUser(String targetUser) {
        this.targetUser = targetUser;
    }

    public int getUserCoins() {
        return userCoins;
    }

    public void setUserCoins(int userCoins) {
        this.userCoins = userCoins;
    }
}
