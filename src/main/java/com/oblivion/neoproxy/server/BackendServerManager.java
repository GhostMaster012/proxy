package com.oblivion.neoproxy.server;

import com.oblivion.neoproxy.config.ConfigManager;
import com.oblivion.neoproxy.config.ProxyConfig;
import com.oblivion.neoproxy.config.ServerInfo;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class BackendServerManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackendServerManager.class);

    private final ConfigManager configManager;
    private Map<String, ServerInfo> servers = new HashMap<>();

    @Autowired
    public BackendServerManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    @PostConstruct
    private void loadServersFromConfig() {
        ProxyConfig proxyConfig = configManager.getConfiguration();
        if (proxyConfig != null && proxyConfig.getServers() != null) {
            this.servers = new HashMap<>(proxyConfig.getServers()); // Make a mutable copy
            LOGGER.info("Loaded {} backend servers from configuration: {}", servers.size(), servers.keySet());
            if (servers.isEmpty()) {
                LOGGER.warn("No backend servers defined in the configuration.");
            }
        } else {
            LOGGER.warn("Backend server configuration is null or empty. No backend servers loaded.");
            this.servers = new HashMap<>(); // Ensure it's initialized
        }
    }

    /**
     * Adds a server to the manager. Primarily for dynamic server addition if ever needed;
     * main population is from config.
     * @param name The name of the server.
     * @param host The host of the server.
     * @param port The port of the server.
     * @param restricted If the server is restricted.
     */
    public void addServer(String name, String host, int port, boolean restricted) {
        if (name == null || name.trim().isEmpty()) {
            LOGGER.error("Server name cannot be null or empty.");
            return;
        }
        if (host == null || host.trim().isEmpty()) {
            LOGGER.error("Server host cannot be null or empty for server: {}", name);
            return;
        }
        ServerInfo serverInfo = new ServerInfo(host + ":" + port, restricted);
        this.servers.put(name.toLowerCase(), serverInfo); // Store names in lowercase for case-insensitive retrieval
        LOGGER.info("Added/Updated server: {} -> {}", name, serverInfo);
    }

    /**
     * Adds a server using a ServerInfo object.
     * @param name The name of the server.
     * @param serverInfo The ServerInfo object.
     */
    public void addServer(String name, ServerInfo serverInfo) {
        if (name == null || name.trim().isEmpty()) {
            LOGGER.error("Server name cannot be null or empty when adding ServerInfo.");
            return;
        }
        if (serverInfo == null || serverInfo.getAddress() == null || serverInfo.getAddress().trim().isEmpty()) {
            LOGGER.error("ServerInfo or its address cannot be null or empty for server: {}", name);
            return;
        }
        this.servers.put(name.toLowerCase(), serverInfo);
        LOGGER.info("Added/Updated server via ServerInfo: {} -> {}", name, serverInfo);
    }


    /**
     * Gets server information by its name.
     * @param name The name of the server (case-insensitive).
     * @return An Optional containing the ServerInfo if found, otherwise empty.
     */
    public Optional<ServerInfo> getServer(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.servers.get(name.toLowerCase()));
    }

    /**
     * Gets the default lobby server.
     * For now, it tries to find a server named "lobby".
     * If not found, it returns the first server in the configuration order.
     * If no servers are configured, it returns empty.
     * @return An Optional containing the ServerInfo for the lobby server, otherwise empty.
     */
    public Optional<ServerInfo> getLobbyServer() {
        if (this.servers.isEmpty()) {
            LOGGER.warn("Attempted to get lobby server, but no servers are configured.");
            return Optional.empty();
        }
        // Prioritize a server explicitly named "lobby"
        Optional<ServerInfo> lobby = getServer("lobby");
        if (lobby.isPresent()) {
            return lobby;
        }
        // Fallback: return the first server defined if "lobby" isn't found
        // This relies on the iteration order of the map from config, which might not be guaranteed
        // A more robust approach would be to define a default_server in config.yml
        LOGGER.warn("No server explicitly named 'lobby' found. Falling back to the first configured server as lobby.");
        return this.servers.values().stream().findFirst();
    }

    /**
     * Gets an unmodifiable view of all configured servers.
     * @return An unmodifiable map of server names to ServerInfo objects.
     */
    public Map<String, ServerInfo> getAllServers() {
        return Collections.unmodifiableMap(this.servers);
    }
}
