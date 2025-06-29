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
import java.util.Queue; // Added
import java.util.concurrent.ConcurrentLinkedQueue; // Added

public class PlayerSession {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerSession.class);

    private final ProxyPlayer player;
    private BackendConnection backendConnection;
    private final ConfigManager configManager;
    private final BackendServerManager serverManager;
    private final EventLoopGroup backendWorkerGroup;

    private String currentTargetServerName;
    private final Queue<ByteBuf> clientPacketBuffer = new ConcurrentLinkedQueue<>();
    private volatile boolean backendPlayStateReady = false;

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
        if (backendConnection != null && backendConnection.getChannel() != null &&
            backendConnection.getChannel().isActive() && backendPlayStateReady) {

            // Peek at packet ID for logging before sending
            packet.markReaderIndex();
            int packetId = -1;
            if (packet.readableBytes() >= 1) {
                try { packetId = com.oblivion.neoproxy.protocol.VarIntUtil.readVarInt(packet); } catch (Exception e) { /* ignore */ }
            }
            packet.resetReaderIndex();
            LOGGER.debug("[PROXY->SERVER] Player {}: Sending Packet ID 0x{} (size: {}) to backend server {}.",
                         player.getUsername(), Integer.toHexString(packetId), packet.readableBytes(), backendConnection.getServerInfo().getAddress());

            backendConnection.sendPacket(packet); // Forward immediately
        } else {
            // Backend not ready or inactive, buffer the packet
            // The packet is already retained by ClientForwardingHandler, so just add it.
            clientPacketBuffer.offer(packet);

            packet.markReaderIndex(); // Mark for peeking ID
            int packetId = -1;
            if (packet.readableBytes() >= 1) {
                try { packetId = com.oblivion.neoproxy.protocol.VarIntUtil.readVarInt(packet); } catch (Exception e) { /* ignore */ }
            }
            packet.resetReaderIndex(); // Reset after peeking

            LOGGER.debug("[BUFFERING] Player {}: Backend not ready/active (PlayStateReady: {}), buffering client Packet ID 0x{} (size: {}). Buffer size: {}",
                         player.getUsername(), backendPlayStateReady, Integer.toHexString(packetId), packet.readableBytes(), clientPacketBuffer.size());
            // DO NOT release the packet here, it's now owned by the queue.
        }
    }

    public void setBackendPlayReadyAndFlushBuffer() {
        LOGGER.info("Player {}: Backend is now PLAY ready.", player.getUsername());
        this.backendPlayStateReady = true;
        flushClientPacketBuffer();
    }

    private void flushClientPacketBuffer() {
        if (backendConnection == null || backendConnection.getChannel() == null || !backendConnection.getChannel().isActive()) {
            LOGGER.warn("Player {}: Tried to flush client packet buffer, but backend connection is not active. Buffered packets ({}) will remain.",
                        player.getUsername(), clientPacketBuffer.size());
            return;
        }

        int flushedCount = 0;
        LOGGER.debug("Player {}: Flushing {} buffered client packets to backend server {}.",
                     player.getUsername(), clientPacketBuffer.size(), backendConnection.getServerInfo().getAddress());

        while (!clientPacketBuffer.isEmpty()) {
            ByteBuf bufferedPacket = clientPacketBuffer.poll();
            if (bufferedPacket != null) {
                // Peek at packet ID for logging before sending
                bufferedPacket.markReaderIndex();
                int packetId = -1;
                if (bufferedPacket.readableBytes() >= 1) {
                    try { packetId = com.oblivion.neoproxy.protocol.VarIntUtil.readVarInt(bufferedPacket); } catch (Exception e) { /* ignore */ }
                }
                bufferedPacket.resetReaderIndex();
                LOGGER.debug("[FLUSHING] Player {}: Sending buffered client Packet ID 0x{} (size: {}) to backend.",
                             player.getUsername(), Integer.toHexString(packetId), bufferedPacket.readableBytes());

                backendConnection.sendPacket(bufferedPacket); // This packet was retained by ClientForwardingHandler
                flushedCount++;
            }
        }
        if (flushedCount > 0) {
            LOGGER.info("Player {}: Flushed {} client packets to backend.", player.getUsername(), flushedCount);
        }
    }

    public void disconnect(String reason) {
        LOGGER.info("Disconnecting player {} with reason: {}", player.getUsername(), reason);
        if (backendConnection != null) {
            backendConnection.disconnect();
            backendConnection = null;
        }
        // Clear and release any buffered packets
        clearClientPacketBuffer();
        player.disconnect(reason); // This should send packet to client and close client connection
    }

    public void close() { // General cleanup
        LOGGER.info("Closing PlayerSession for {}", player.getUsername());
        disconnect("Proxy shutting down or session ended."); // disconnect() will call clearClientPacketBuffer()
    }

    private void clearClientPacketBuffer() {
        if (!clientPacketBuffer.isEmpty()) {
            LOGGER.debug("Player {}: Clearing {} buffered client packets due to session close/disconnect.",
                         player.getUsername(), clientPacketBuffer.size());
            ByteBuf packet;
            while ((packet = clientPacketBuffer.poll()) != null) {
                packet.release();
            }
        }
    }
}
