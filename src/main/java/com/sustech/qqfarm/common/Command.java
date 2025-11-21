package com.sustech.qqfarm.common;

public enum Command {
    LOGIN,
    PLANT,
    HARVEST,
    STEAL,
    GET_FARM, // For visiting or refreshing
    UPDATE    // Server push to client
}
