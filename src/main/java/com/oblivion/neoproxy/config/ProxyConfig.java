package com.oblivion.neoproxy.config;

import java.util.List;
import java.util.Map;

public class ProxyConfig {
    private Map<String, ServerInfo> servers;
    private List<ListenerConfig> listeners;
    private Map<String, List<String>> permissions;

    // Default constructor for SnakeYAML
    public ProxyConfig() {
    }

    // Getters and Setters
    public Map<String, ServerInfo> getServers() {
        return servers;
    }

    public void setServers(Map<String, ServerInfo> servers) {
        this.servers = servers;
    }

    public List<ListenerConfig> getListeners() {
        return listeners;
    }

    public void setListeners(List<ListenerConfig> listeners) {
        this.listeners = listeners;
    }

    public Map<String, List<String>> getPermissions() {
        return permissions;
    }

    public void setPermissions(Map<String, List<String>> permissions) {
        this.permissions = permissions;
    }

    @Override
    public String toString() {
        return "ProxyConfig{" +
               "servers=" + servers +
               ", listeners=" + listeners +
               ", permissions=" + permissions +
               '}';
    }
}
