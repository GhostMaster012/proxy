package com.oblivion.neoproxy.player;

import com.oblivion.neoproxy.config.ConfigManager;
import com.oblivion.neoproxy.config.ServerInfo;
import com.oblivion.neoproxy.server.BackendConnection;
import com.oblivion.neoproxy.server.BackendServerManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.netty.buffer.ByteBufUtil; // Added

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PlayerSession {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerSession.class);

    private final ProxyPlayer player;
    private BackendConnection backendConnection;
    private final ConfigManager configManager;
    private final BackendServerManager serverManager;
    private final EventLoopGroup backendWorkerGroup;

    private String currentTargetServerName;
    private final Queue<ByteBuf> clientPacketBuffer = new ConcurrentLinkedQueue<>();
    private volatile boolean backendJoinGameForwardedToClient = false; // Renamed

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
            backendConnection.getChannel().isActive() && backendJoinGameForwardedToClient) { // Used renamed flag

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
            clientPacketBuffer.offer(packet);

            packet.markReaderIndex();
            int packetId = -1;
            if (packet.readableBytes() >= 1) {
                try { packetId = com.oblivion.neoproxy.protocol.VarIntUtil.readVarInt(packet); } catch (Exception e) { /* ignore */ }
            }
            packet.resetReaderIndex();

            LOGGER.debug("[BUFFERING] Player {}: Backend Join Game not yet forwarded (Flag: {}), buffering client Packet ID 0x{} (size: {}). Buffer size: {}",
                         player.getUsername(), backendJoinGameForwardedToClient, Integer.toHexString(packetId), packet.readableBytes(), clientPacketBuffer.size());
        }
    }

    /**
     * Called by BackendForwardingHandler after it has successfully forwarded the Join Game packet
     * (or equivalent critical setup packet) from the backend to the client.
     */
    public void onBackendJoinGameForwarded() { // Renamed method
        LOGGER.info("Player {}: Backend Join Game packet has been forwarded to client. Setting flag and flushing client packet buffer.", player.getUsername());
        this.backendJoinGameForwardedToClient = true; // Set the new flag
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
                int packetIdLength = 0;
                String hexDump = "N/A";

                if (bufferedPacket.readableBytes() >= 1) {
                    try {
                        packetId = com.oblivion.neoproxy.protocol.VarIntUtil.readVarInt(bufferedPacket);
                        packetIdLength = bufferedPacket.readerIndex(); // Bytes read for VarInt

                        // Capture hex dump of payload (after packet ID)
                        int payloadReadableBytes = bufferedPacket.readableBytes();
                        int dumpLength = Math.min(payloadReadableBytes, 16);
                        if (dumpLength > 0) {
                            hexDump = ByteBufUtil.hexDump(bufferedPacket, bufferedPacket.readerIndex(), dumpLength);
                        } else {
                            hexDump = "[No Payload Data]";
                        }

                    } catch (Exception e) {
                        LOGGER.trace("Player {}: Error peeking at Packet ID/payload for hex dump in flushClientPacketBuffer. Packet Size: {}",
                                     player.getUsername(), bufferedPacket.readableBytes(), e);
                    }
                }
                bufferedPacket.resetReaderIndex(); // IMPORTANT: Reset to send the full packet (ID + Data)

                LOGGER.debug("[FLUSHING] Player {}: Processing buffered client Packet ID 0x{} (size: {}). Payload Hex (first {} bytes after ID): {}",
                             player.getUsername(), Integer.toHexString(packetId), bufferedPacket.readableBytes(),
                             Math.min(bufferedPacket.readableBytes() - packetIdLength, 16), hexDump);

                // Filter out packets 0x00 and 0x03
                if (packetId == 0x00 || packetId == 0x03) {
                    LOGGER.info("[FILTERING] Player {}: Filtering out client Packet ID 0x{} (size: {}). Payload Hex: {}",
                                 player.getUsername(), Integer.toHexString(packetId), bufferedPacket.readableBytes(), hexDump);
                    bufferedPacket.release(); // Release the filtered packet
                    // Do not increment flushedCount for filtered packets if it means "sent to backend"
                } else {
                    // Packet is not filtered, send it
                    LOGGER.info("[FORWARDING] Player {}: Forwarding (previously buffered) client Packet ID 0x{} (size: {}) to backend.",
                                 player.getUsername(), Integer.toHexString(packetId), bufferedPacket.readableBytes());
                    backendConnection.sendPacket(bufferedPacket); // This packet was retained by ClientForwardingHandler
                    flushedCount++; // Increment only for packets actually sent
                }
            }
        }
        if (flushedCount > 0) {
            LOGGER.info("Player {}: Successfully forwarded {} previously buffered client packets to backend.", player.getUsername(), flushedCount);
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
