package com.oblivion.neoproxy.player;

import com.oblivion.neoproxy.config.ConfigManager;
import com.oblivion.neoproxy.config.ServerInfo;
import com.oblivion.neoproxy.server.BackendConnection;
import com.oblivion.neoproxy.server.BackendServerManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class PlayerSession {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerSession.class);

    private final ProxyPlayer player;
    private BackendConnection backendConnection;
    private final ConfigManager configManager; // For general proxy config if needed
    private final BackendServerManager serverManager; // To get ServerInfo
    private final EventLoopGroup backendWorkerGroup; // For BackendConnection

    private String currentTargetServerName;

    public PlayerSession(ProxyPlayer player, ConfigManager configManager, BackendServerManager serverManager, EventLoopGroup backendWorkerGroup) {
        this.player = player;
        this.configManager = configManager;
        this.serverManager = serverManager;
        this.backendWorkerGroup = backendWorkerGroup;
        this.player.setSession(this); // Link back
        LOGGER.info("PlayerSession created for {}", player.getUsername());
    }

    public ProxyPlayer getPlayer() {
        return player;
    }

    public void connectToBackend(String serverName) {
        this.currentTargetServerName = serverName;
        Optional<ServerInfo> serverInfoOpt = serverManager.getServer(serverName);

        if (serverInfoOpt.isEmpty()) {
            LOGGER.error("Player {} tried to connect to unknown server: {}", player.getUsername(), serverName);
            disconnect("Server '" + serverName + "' not found.");
            return;
        }

        ServerInfo targetServer = serverInfoOpt.get();
        LOGGER.info("Player {} connecting to backend server: {} ({})", player.getUsername(), serverName, targetServer.getAddress());

        // Disconnect from previous backend if any
        if (this.backendConnection != null) {
            this.backendConnection.disconnect();
            this.backendConnection = null;
        }

        this.backendConnection = new BackendConnection(this, targetServer, backendWorkerGroup);
        this.backendConnection.connect(); // Asynchronous
    }

    // Called by BackendConnection
    public void onBackendConnected(BackendConnection connection) {
        LOGGER.info("Player {} successfully established backend connection to {}.", player.getUsername(), currentTargetServerName);
        player.setCurrentServerName(currentTargetServerName);
        // TODO: Initiate handshake sequence with the backend server.
        // This involves sending a Handshake packet (with next state LOGIN),
        // then a LoginStart packet with the player's actual username/UUID.
        // For now, this is a placeholder.
        LOGGER.info("TODO: Player {} - Send Handshake & LoginStart to backend server {}.", player.getUsername(), currentTargetServerName);
    }

    // Called by BackendConnection
    public void onBackendConnectionFailed(Throwable cause) {
        LOGGER.warn("Player {} failed to connect to backend {}: {}", player.getUsername(), currentTargetServerName, cause.getMessage());
        // TODO: Implement fallback logic (e.g., try another server, send message to player)
        disconnect("Could not connect to the server: " + currentTargetServerName);
    }

    // Called by BackendConnection's BackendForwardingHandler
    public void onBackendDisconnected() {
        LOGGER.info("Player {} was disconnected from backend server {}.", player.getUsername(), player.getCurrentServerName());
        player.setCurrentServerName(null);
        // TODO: Implement logic for when backend disconnects (e.g., send to lobby, kick player)
        disconnect("Disconnected from server."); // Generic message for now
    }


    public void sendToClient(ByteBuf packet) {
        if (player.getClientCtx() != null && player.getClientCtx().channel().isActive()) {
            player.getClientCtx().writeAndFlush(packet.retain());
        } else {
            packet.release(); // Must release if not sending
        }
    }

    public void sendToServer(ByteBuf packet) {
        if (backendConnection != null && backendConnection.getChannel() != null && backendConnection.getChannel().isActive()) {
            backendConnection.sendPacket(packet.retain());
        } else {
            packet.release(); // Must release if not sending
        }
    }

    public void disconnect(String reason) {
        LOGGER.info("Disconnecting player {} with reason: {}", player.getUsername(), reason);
        if (backendConnection != null) {
            backendConnection.disconnect();
            backendConnection = null;
        }
        player.disconnect(reason); // This should send packet to client and close client connection
    }

    public void close() { // General cleanup
        LOGGER.info("Closing PlayerSession for {}", player.getUsername());
        disconnect("Proxy shutting down or session ended.");
    }
}
