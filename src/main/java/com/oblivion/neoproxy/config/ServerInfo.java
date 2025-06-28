package com.oblivion.neoproxy.config;

public class ServerInfo {
    private String address;
    private boolean restricted;
    // Add other per-server properties here if needed later (e.g., motd, priority)

    // Default constructor for SnakeYAML
    public ServerInfo() {
    }

    public ServerInfo(String address, boolean restricted) {
        this.address = address;
        this.restricted = restricted;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public boolean isRestricted() {
        return restricted;
    }

    public void setRestricted(boolean restricted) {
        this.restricted = restricted;
    }

    @Override
    public String toString() {
        return "ServerInfo{" +
               "address='" + address + '\'' +
               ", restricted=" + restricted +
               '}';
    }
}
