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
                LOGGER.info("Player {} successfully connected to backend server {} ({})",
                            playerSession.getPlayer().getUsername(), serverInfo, this.channel.remoteAddress());
                playerSession.onBackendConnected(this);
                // TODO: Send handshake + login sequence to backend server
                // This will involve crafting packets similar to how a client would.
                // For now, connection is established, but server doesn't know who we are.
            } else {
                LOGGER.error("Player {} failed to connect to backend server {} ({}:{}): {}",
                             playerSession.getPlayer().getUsername(), serverInfo, host, port, f.cause().getMessage(), f.cause());
                playerSession.onBackendConnectionFailed(f.cause());
            }
        });
    }

    public void sendPacket(ByteBuf packet) {
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(packet.retain()); // Retain because it might be used elsewhere or by Netty async
        } else {
            LOGGER.warn("Attempted to send packet to inactive backend channel for player {}", playerSession.getPlayer().getUsername());
            packet.release(); // Release if not sending
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
                LOGGER.warn("BackendForwardingHandler received non-ByteBuf message: {}", msg.getClass().getName());
                super.channelRead(ctx, msg); // Or release msg if appropriate
                return;
            }
            ByteBuf packet = (ByteBuf) msg;
            // LOGGER.debug("Forwarding packet from backend {} to player {}", serverAddress, playerSession.getPlayer().getUsername());
            playerSession.sendToClient(packet.retain()); // Retain for the client to use
            // Packet will be released by the client's pipeline or if sendToClient releases it.
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
