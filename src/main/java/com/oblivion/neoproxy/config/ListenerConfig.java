package com.oblivion.neoproxy.config;

import java.util.Map;

public class ListenerConfig {
    private int query_port; // Assuming this is intentional for now
    private String motd;
    private String tab_list; // Could be an enum later: GLOBAL_PING, SERVER, etc.
    private String host;
    private int max_players;
    private Map<String, String> forced_hosts;

    // Default constructor for SnakeYAML
    public ListenerConfig() {
    }

    // Getters and Setters
    public int getQuery_port() {
        return query_port;
    }

    public void setQuery_port(int query_port) {
        this.query_port = query_port;
    }

    public String getMotd() {
        return motd;
    }

    public void setMotd(String motd) {
        this.motd = motd;
    }

    public String getTab_list() {
        return tab_list;
    }

    public void setTab_list(String tab_list) {
        this.tab_list = tab_list;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getMax_players() {
        return max_players;
    }

    public void setMax_players(int max_players) {
        this.max_players = max_players;
    }

    public Map<String, String> getForced_hosts() {
        return forced_hosts;
    }

    public void setForced_hosts(Map<String, String> forced_hosts) {
        this.forced_hosts = forced_hosts;
    }

    @Override
    public String toString() {
        return "ListenerConfig{" +
               "query_port=" + query_port +
               ", motd='" + motd + '\'' +
               ", tab_list='" + tab_list + '\'' +
               ", host='" + host + '\'' +
               ", max_players=" + max_players +
               ", forced_hosts=" + forced_hosts +
               '}';
    }
}
