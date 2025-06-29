package com.oblivion.neoproxy.server;

import com.oblivion.neoproxy.config.ServerInfo;
import com.oblivion.neoproxy.player.PlayerSession;
import com.oblivion.neoproxy.protocol.PacketDecoder;
import com.oblivion.neoproxy.protocol.PacketEncoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Custom imports
import com.oblivion.neoproxy.protocol.VarIntUtil;
import com.oblivion.neoproxy.protocol.ConnectionState;
import com.oblivion.neoproxy.network.handler.NettyChannelAttributes;


import java.net.InetSocketAddress; // Added
import java.util.concurrent.TimeUnit;

public class BackendConnection {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackendConnection.class);

    private final PlayerSession playerSession;
    private final ServerInfo serverInfo;
    private Channel channel;
    private final EventLoopGroup workerGroup; // Should be passed from a central place, e.g., NeoProxyApplication or a Netty manager

    public BackendConnection(PlayerSession playerSession, ServerInfo serverInfo, EventLoopGroup workerGroup) {
        this.playerSession = playerSession;
        this.serverInfo = serverInfo;
        this.workerGroup = workerGroup;
    }

    public void connect() {
        if (serverInfo == null || serverInfo.getAddress() == null) {
            LOGGER.error("Cannot connect to backend: ServerInfo or address is null for player {}", playerSession.getPlayer().getUsername());
            playerSession.disconnect("Internal server error: Invalid backend server configuration.");
            return;
        }

        String[] addressParts = serverInfo.getAddress().split(":");
        if (addressParts.length != 2) {
            LOGGER.error("Invalid backend server address format: {} for player {}", serverInfo.getAddress(), playerSession.getPlayer().getUsername());
            playerSession.disconnect("Internal server error: Invalid backend server address format.");
            return;
        }
        String host = addressParts[0];
        int port = Integer.parseInt(addressParts[1]);

        Bootstrap b = new Bootstrap();
        b.group(workerGroup)
         .channel(NioSocketChannel.class)
         .handler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) throws Exception {
                 ch.pipeline().addLast("readTimeoutHandler", new ReadTimeoutHandler(30, TimeUnit.SECONDS)); // 30s timeout
                 ch.pipeline().addLast("packetDecoder", new PacketDecoder()); // Reuse existing
                 ch.pipeline().addLast("packetEncoder", new PacketEncoder()); // Reuse existing
                 ch.pipeline().addLast("backendForwardingHandler", new BackendForwardingHandler(playerSession, serverInfo.getAddress()));
             }
         });

        LOGGER.info("Player {} attempting to connect to backend server {} ({}:{})",
                    playerSession.getPlayer().getUsername(), serverInfo, host, port);

        ChannelFuture future = b.connect(host, port);
        future.addListener(f -> {
            if (f.isSuccess()) {
                this.channel = future.channel();
                LOGGER.info("Player {} successfully TCP connected to backend server {} ({})",
                            playerSession.getPlayer().getUsername(), serverInfo.getAddress(), this.channel.remoteAddress());
                // Set initial attributes for the backend channel before sending handshake
                this.channel.attr(com.oblivion.neoproxy.network.handler.NettyChannelAttributes.CONNECTION_STATE_KEY).set(com.oblivion.neoproxy.protocol.ConnectionState.HANDSHAKE);
                this.channel.attr(com.oblivion.neoproxy.network.handler.NettyChannelAttributes.PROTOCOL_VERSION_KEY).set(765); // Minecraft 1.20.4

                playerSession.onBackendConnected(this); // This will trigger sendHandshakeToBackend -> sendLoginStartToBackend
            } else {
                LOGGER.error("Player {} failed to TCP connect to backend server {} ({}:{}): {}",
                             playerSession.getPlayer().getUsername(), serverInfo.getAddress(), host, port, f.cause().getMessage(), f.cause());
                playerSession.onBackendConnectionFailed(f.cause());
            }
        });
    }

    public void sendPacket(ByteBuf packet) { // packet is expected to be already retained by PlayerSession.sendToServer
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(packet); // writeAndFlush consumes the packet
        } else {
            LOGGER.warn("Attempted to send packet to inactive backend channel for player {}", playerSession.getPlayer().getUsername());
            packet.release();
        }
    }

    public void disconnect() {
        if (channel != null && channel.isActive()) {
            LOGGER.info("Disconnecting from backend server {} for player {}", serverInfo.getAddress(), playerSession.getPlayer().getUsername());
            channel.close();
        }
        this.channel = null;
    }

    public Channel getChannel() {
        return channel;
    }

    public ServerInfo getServerInfo() { // Added getter
        return serverInfo;
    }

    public void sendHandshakeToBackend() {
        if (channel == null || !channel.isActive()) {
            LOGGER.warn("Cannot send handshake to backend, channel is not active for player {}.", playerSession.getPlayer().getUsername());
            return;
        }

        String fullAddress = serverInfo.getAddress();
        String[] addressParts = fullAddress.split(":");
        String originalBackendHostname = addressParts[0];
        int originalBackendPort = Integer.parseInt(addressParts[1]);

        // Retrieve client's actual IP and UUID
        String clientIp = "127.0.0.1"; // Default/fallback
        if (playerSession.getPlayer().getClientCtx().channel().remoteAddress() instanceof InetSocketAddress) {
            InetSocketAddress clientAddress = (InetSocketAddress) playerSession.getPlayer().getClientCtx().channel().remoteAddress();
            clientIp = clientAddress.getAddress().getHostAddress();
        }
        String clientUuidNoDashes = playerSession.getPlayer().getUuid().toString().replace("-", "");
        String propertiesJson = "[]"; // Empty JSON array for player properties

        // Construct the BungeeCord IP Forwarding string
        // Format: originalHostname\00clientIP\00clientUUID\00properties
        String forwardedHostString = originalBackendHostname + "\00" +
                                     clientIp + "\00" +
                                     clientUuidNoDashes + "\00" +
                                     propertiesJson;

        LOGGER.info("Using IP Forwarding. Forwarded host string for backend handshake: '{}'", forwardedHostString);

        // Ensure state is HANDSHAKE for PacketEncoder to work correctly for this packet
        channel.attr(NettyChannelAttributes.CONNECTION_STATE_KEY).set(ConnectionState.HANDSHAKE);
        // Protocol version (765) should already be set on the channel from the connect() method.

        ByteBuf handshakePacket = channel.alloc().buffer();
        VarIntUtil.writeVarInt(handshakePacket, 0x00); // Handshake Packet ID
        VarIntUtil.writeVarInt(handshakePacket, 765);  // Protocol Version (1.20.4)
        VarIntUtil.writeString(handshakePacket, forwardedHostString); // Server address with IP forwarding data
        handshakePacket.writeShort(originalBackendPort);              // Original backend server port
        VarIntUtil.writeVarInt(handshakePacket, 2);    // Next state: 2 (Login)

        channel.writeAndFlush(handshakePacket).addListener(future -> {
            if (future.isSuccess()) {
                LOGGER.info("Successfully sent Handshake to backend {} for player {}", serverInfo.getAddress(), playerSession.getPlayer().getUsername());
                // Transition backend channel state to LOGIN for the next packet
                channel.attr(NettyChannelAttributes.CONNECTION_STATE_KEY).set(ConnectionState.LOGIN);
                sendLoginStartToBackend(); // Chain the next step
            } else {
                LOGGER.error("Failed to send Handshake to backend {} for player {}: {}",
                             serverInfo.getAddress(), playerSession.getPlayer().getUsername(), future.cause().getMessage(), future.cause());
                playerSession.onBackendConnectionFailed(future.cause()); // Or a more specific method
            }
        });
    }

    private void sendLoginStartToBackend() {
        if (channel == null || !channel.isActive()) {
            LOGGER.warn("Cannot send Login Start to backend, channel is not active for player {}.", playerSession.getPlayer().getUsername());
            return;
        }
        // State should already be LOGIN here, set after successful handshake send.

        ByteBuf loginStartPacket = channel.alloc().buffer();
        VarIntUtil.writeVarInt(loginStartPacket, 0x00); // Login Start Packet ID (in LOGIN state)
        VarIntUtil.writeString(loginStartPacket, playerSession.getPlayer().getUsername());
        VarIntUtil.writeUUID(loginStartPacket, playerSession.getPlayer().getUuid());
        // For 1.20.4, no more fields for Login Start after UUID if not using Mojang auth for proxy itself.

        channel.writeAndFlush(loginStartPacket).addListener(future -> {
            if (future.isSuccess()) {
                LOGGER.info("Successfully sent Login Start to backend {} for player {}", serverInfo.getAddress(), playerSession.getPlayer().getUsername());
                // Now we wait for the backend to respond (e.g., Set Compression, Login Success)
                // The BackendForwardingHandler will process these.
            } else {
                LOGGER.error("Failed to send Login Start to backend {} for player {}: {}",
                             serverInfo.getAddress(), playerSession.getPlayer().getUsername(), future.cause().getMessage(), future.cause());
                playerSession.onBackendConnectionFailed(future.cause());
            }
        });
    }

    private static class BackendForwardingHandler extends ChannelInboundHandlerAdapter {
        private final PlayerSession playerSession;
        private final String serverAddress;

        public BackendForwardingHandler(PlayerSession playerSession, String serverAddress) {
            this.playerSession = playerSession;
            this.serverAddress = serverAddress;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!(msg instanceof ByteBuf)) {
                LOGGER.warn("[{}] BackendForwardingHandler for {} received non-ByteBuf message: {}",
                            ctx.channel().id().asShortText(), playerSession.getPlayer().getUsername(), msg.getClass().getName());
                super.channelRead(ctx, msg);
                return;
            }

            ByteBuf packet = (ByteBuf) msg;
            ConnectionState backendState = ctx.channel().attr(NettyChannelAttributes.CONNECTION_STATE_KEY).get();

            if (backendState == ConnectionState.LOGIN) {
                // We are in the LOGIN state with the backend, expect Login Success or Set Compression
                packet.markReaderIndex();
                int packetId = VarIntUtil.readVarInt(packet);
                packet.resetReaderIndex(); // Reset so the full packet (ID + data) can be processed or forwarded

                if (packetId == 0x02) { // Login Success (Backend)
                    // Packet structure: UUID, Username, Number of properties, [Properties]
                    // We don't strictly need to parse it for the proxy's core function here,
                    // but it's good to acknowledge.
                    LOGGER.info("Received Login Success (0x02) from backend {} for player {}",
                                serverAddress, playerSession.getPlayer().getUsername());

                    ctx.channel().attr(NettyChannelAttributes.CONNECTION_STATE_KEY).set(ConnectionState.PLAY);
                    LOGGER.info("Backend connection for player {} transitioned to PLAY state.",
                                playerSession.getPlayer().getUsername());

                    // Notify PlayerSession that backend is ready and to flush any buffered packets
                    playerSession.setBackendPlayReadyAndFlushBuffer();

                    packet.release(); // Consume the Login Success packet, do not forward.
                    return;

                } else if (packetId == 0x03) { // Set Compression (Backend)
                    // packet.skipBytes(VarIntUtil.getVarIntSize(packetId)); // Already skipped by resetReaderIndex logic below
                    // int threshold = VarIntUtil.readVarInt(packet); // Original position before correction
                    // LOGGER.info("Received Set Compression (0x03) from backend {} for player {} (threshold: {}).",
                    //             serverAddress, playerSession.getPlayer().getUsername(), threshold);

                    packet.resetReaderIndex(); // Go back to start of packet (ID + Data)
                    packet.skipBytes(VarIntUtil.getVarIntSize(packetId)); // Skip the Packet ID itself
                    int threshold = VarIntUtil.readVarInt(packet); // Now read the threshold

                    LOGGER.info("Received Set Compression (0x03) from backend {} for player {} (threshold: {}). Enabling compression.",
                                serverAddress, playerSession.getPlayer().getUsername(), threshold);

                    if (threshold >= 0) {
                        // Dynamically add compressor and decompressor to the pipeline
                        // Decompressor goes after the initial framing PacketDecoder
                        ctx.pipeline().addAfter("packetDecoder", "minecraftDecompressor",
                                                new com.oblivion.neoproxy.protocol.compression.MinecraftPacketDecompressor(threshold));
                        // Compressor goes before the final framing PacketEncoder
                        ctx.pipeline().addBefore("packetEncoder", "minecraftCompressor",
                                                 new com.oblivion.neoproxy.protocol.compression.MinecraftPacketCompressor(threshold));
                        LOGGER.info("Compression handlers added to pipeline for backend connection of player {}.",
                                    playerSession.getPlayer().getUsername());
                    } else {
                        // Threshold < 0 typically means disable compression, though usually servers don't send this after enabling.
                        // If it means disable, we might need to remove handlers if they were added.
                        LOGGER.warn("Received Set Compression with negative threshold ({}) for player {}. Compression not enabled/changed.",
                                    threshold, playerSession.getPlayer().getUsername());
                    }

                    packet.release(); // Consume the Set Compression packet, do not forward
                    return;
                } else if (packetId == 0x00 && packet.readableBytes() > 0) { // Disconnect (Login)
                     packet.resetReaderIndex(); // Ensure we are at the start of the packet payload
                     packet.skipBytes(VarIntUtil.getVarIntSize(packetId)); // Skip packet ID
                     String reason = VarIntUtil.readString(packet);
                     LOGGER.warn("Backend {} disconnected player {} during login: {}", serverAddress, playerSession.getPlayer().getUsername(), reason);
                     playerSession.disconnect("Backend error: " + reason);
                     packet.release();
                     ctx.close(); // Close connection to backend
                     return;
                }
                // Else, unknown packet during LOGIN state with backend, could be an error or unexpected.
                LOGGER.warn("[{}] Received unexpected packet ID 0x{} from backend {} for player {} during LOGIN state.",
                            ctx.channel().id().asShortText(), Integer.toHexString(packetId), serverAddress, playerSession.getPlayer().getUsername());
                // For now, forward it if we don't recognize it, though this might be risky.
                // Or, better, disconnect. For now, let's just forward and see.
                 playerSession.sendToClient(packet.retain());


            } else if (backendState == ConnectionState.PLAY) {
                packet.markReaderIndex();
                int packetId = -1;
                if (packet.readableBytes() >= 1) {
                    try {
                        packetId = VarIntUtil.readVarInt(packet);
                    } catch (Exception e) {
                         LOGGER.trace("[BACKEND->PROXY] Player {}: Error peeking at Packet ID from backend (packet too small for VarInt?). Size: {}",
                                     playerSession.getPlayer().getUsername(), packet.readableBytes(), e);
                    }
                }
                packet.resetReaderIndex();
                LOGGER.debug("[BACKEND->PROXY] Player {}: Forwarding Packet ID 0x{} to client. Size: {}",
                             playerSession.getPlayer().getUsername(), Integer.toHexString(packetId), packet.readableBytes());

                // Example logging for specific important game packets from backend
                // Ensure these IDs are correct for Minecraft 1.20.4 (protocol 765)
                // Common Clientbound Packet IDs (Login and Play): https://wiki.vg/Protocol#Clientbound_2
                // Play state:
                // Join Game: 0x29 (was 0x26 in 1.19.4, 0x28 in 1.20.2) - Check wiki.vg for 765. For 1.20.4 (765) it's 0x29.
                // Spawn Position: 0x4B (was 0x40 in 1.19.4, 0x49 in 1.20.2) - For 1.20.4 (765) it's 0x4B.
                // Player Abilities: 0x37 (was 0x32 in 1.19.4, 0x36 in 1.20.2) - For 1.20.4 (765) it's 0x37.

                if (packetId == 0x29) {
                    LOGGER.debug("*** [BACKEND->PROXY] Player {}: Forwarding JOIN GAME (0x29) to client. ***", playerSession.getPlayer().getUsername());
                } else if (packetId == 0x4B) {
                    LOGGER.debug("*** [BACKEND->PROXY] Player {}: Forwarding SPAWN POSITION (0x4B) to client. ***", playerSession.getPlayer().getUsername());
                } else if (packetId == 0x37) {
                    LOGGER.debug("*** [BACKEND->PROXY] Player {}: Forwarding PLAYER ABILITIES (0x37) to client. ***", playerSession.getPlayer().getUsername());
                }


                playerSession.sendToClient(packet.retain());
            } else {
                // Handshake state or other unexpected state for backend connection after initial TCP connect.
                LOGGER.warn("[{}] BackendForwardingHandler for {} received packet in unexpected backend state: {}. Releasing packet.",
                            ctx.channel().id().asShortText(), playerSession.getPlayer().getUsername(), backendState);
                packet.release();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            LOGGER.info("Disconnected from backend server {} for player {}", serverAddress, playerSession.getPlayer().getUsername());
            playerSession.onBackendDisconnected();
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            LOGGER.error("Exception in BackendForwardingHandler for player {} connected to {}: {}",
                         playerSession.getPlayer().getUsername(), serverAddress, cause.getMessage(), cause);
            playerSession.onBackendConnectionFailed(cause); // Treat exception as a failure/disconnect
            ctx.close();
        }
    }
}
