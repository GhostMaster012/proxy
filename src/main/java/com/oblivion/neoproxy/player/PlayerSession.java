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
        // Ensure currentTargetServerName is set for logging and context
        player.setCurrentServerName(currentTargetServerName);
        LOGGER.info("Player {} successfully TCP connected to backend server {}. Initiating Minecraft handshake sequence.",
                    player.getUsername(), currentTargetServerName);

        // Delegate the handshake and login packet sending to the BackendConnection instance.
        // BackendConnection.sendHandshakeToBackend() will internally set channel attributes
        // and upon successful handshake send, it will call its own sendLoginStartToBackend().
        connection.sendHandshakeToBackend();
    }

    // Called by BackendConnection
    public void onBackendConnectionFailed(Throwable cause) {
        String safeServerName = currentTargetServerName != null ? currentTargetServerName : "the target server";
        LOGGER.warn("Player {} failed to connect to backend {}. Reason: {}",
                    player.getUsername(), safeServerName, cause.getMessage());
        // TODO: Implement fallback logic (e.g., try another server)
        disconnect("Could not connect to " + safeServerName + ". Please try again later.");
    }

    // Called by BackendConnection's BackendForwardingHandler
    public void onBackendDisconnected() {
        String previouslyConnectedServer = player.getCurrentServerName() != null ? player.getCurrentServerName() : "the server";
        LOGGER.info("Player {} was disconnected from backend server {}.", player.getUsername(), previouslyConnectedServer);
        player.setCurrentServerName(null);
        // TODO: Implement logic for when backend disconnects (e.g., send to lobby, kick player with specific reason if available)
        disconnect("You have been disconnected from " + previouslyConnectedServer + ".");
    }


    public void sendToClient(ByteBuf packet) { // packet is expected to be already retained by the caller (BackendForwardingHandler)
        if (player.getClientCtx() != null && player.getClientCtx().channel().isActive()) {
            // Peek at packet ID for logging
            packet.markReaderIndex();
            int packetId = -1;
            if (packet.readableBytes() >= 1) {
                try { packetId = com.oblivion.neoproxy.protocol.VarIntUtil.readVarInt(packet); } catch (Exception e) { /* ignore */ }
            }
            packet.resetReaderIndex();
            LOGGER.debug("[PROXY->CLIENT] Player {}: Sending Packet ID 0x{} (size: {}) to client.",
                         player.getUsername(), Integer.toHexString(packetId), packet.readableBytes());

            player.getClientCtx().writeAndFlush(packet); // writeAndFlush consumes the packet (decrements refCnt)
        } else {
            LOGGER.warn("Player {} client channel inactive, releasing packet (size: {}) meant for client.", player.getUsername(), packet.readableBytes());
            packet.release();
        }
    }

    public void sendToServer(ByteBuf packet) { // packet is expected to be already retained by the caller (ClientForwardingHandler)
        if (backendConnection != null && backendConnection.getChannel() != null && backendConnection.getChannel().isActive()) {
            // Peek at packet ID for logging
            packet.markReaderIndex();
            int packetId = -1;
            if (packet.readableBytes() >= 1) {
                try { packetId = com.oblivion.neoproxy.protocol.VarIntUtil.readVarInt(packet); } catch (Exception e) { /* ignore */ }
            }
            packet.resetReaderIndex();
            LOGGER.debug("[PROXY->SERVER] Player {}: Sending Packet ID 0x{} (size: {}) to backend server {}.",
                         player.getUsername(), Integer.toHexString(packetId), packet.readableBytes(), backendConnection.getServerInfo().getAddress());

            backendConnection.sendPacket(packet); // sendPacket will pass it to writeAndFlush, which consumes it
        } else {
            LOGGER.warn("Player {} backend channel inactive, releasing packet (size: {}) meant for server.", player.getUsername(), packet.readableBytes());
            packet.release();
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
