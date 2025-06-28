package com.oblivion.neoproxy.network.handler;

import com.oblivion.neoproxy.config.ListenerConfig;
import com.oblivion.neoproxy.config.ProxyConfig;
import com.oblivion.neoproxy.protocol.ConnectionState;
import com.oblivion.neoproxy.protocol.VarIntUtil;
import io.netty.buffer.ByteBuf;
import com.oblivion.neoproxy.config.ConfigManager;
import com.oblivion.neoproxy.server.BackendServerManager;
import io.netty.channel.EventLoopGroup;
import com.oblivion.neoproxy.player.PlayerSession; // Added
import com.oblivion.neoproxy.player.ProxyPlayer; // Added
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InitialLoginHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(InitialLoginHandler.class);

    private final ProxyConfig proxyConfig;
    private final ListenerConfig listenerConfig;
    private final ConfigManager configManager;
    private final BackendServerManager backendServerManager;
    private final EventLoopGroup backendWorkerGroup;

    public InitialLoginHandler(ProxyConfig proxyConfig, ListenerConfig listenerConfig,
                               ConfigManager configManager, BackendServerManager backendServerManager, EventLoopGroup backendWorkerGroup) {
        this.proxyConfig = proxyConfig;
        this.listenerConfig = listenerConfig;
        this.configManager = configManager;
        this.backendServerManager = backendServerManager;
        this.backendWorkerGroup = backendWorkerGroup;

        if (this.proxyConfig == null || this.listenerConfig == null || this.configManager == null ||
            this.backendServerManager == null || this.backendWorkerGroup == null) {
            LOGGER.warn("InitialLoginHandler created with one or more null dependency objects!");
            // This is a critical issue, potentially throw an error or ensure robust null checks in methods using these.
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // This method might not be called if the handler is added dynamically after channel is active.
        // The state should already be LOGIN as set by ServerListPingHandler.
        ConnectionState currentState = ctx.channel().attr(NettyChannelAttributes.CONNECTION_STATE_KEY).get();
        LOGGER.info("[{}] InitialLoginHandler is now active. Current state: {}", ctx.channel().id().asShortText(), currentState);
        super.channelActive(ctx);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        ConnectionState currentState = ctx.channel().attr(NettyChannelAttributes.CONNECTION_STATE_KEY).get();
        LOGGER.info("[{}] InitialLoginHandler added to pipeline for {}. Current state: {}",
                    ctx.channel().id().asShortText(), ctx.channel().remoteAddress(), currentState);
        super.handlerAdded(ctx);
    }


    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf packetBuffer = (ByteBuf) msg;
        String channelId = ctx.channel().id().asShortText(); // Keep for error/warn logs
        ConnectionState currentState = ctx.channel().attr(NettyChannelAttributes.CONNECTION_STATE_KEY).get();

        // LOGGER.debug("[{}] InitialLoginHandler received a message in state: {}. Buffer readable bytes: {}", // Removed DEBUG
        //              channelId, currentState, packetBuffer.readableBytes());

        if (currentState != ConnectionState.LOGIN) {
            LOGGER.warn("[{}] InitialLoginHandler received message for {} in unexpected state: {}. Ignoring.",
                        channelId, ctx.channel().remoteAddress(), currentState);
            packetBuffer.release();
            return;
        }

        try {
            if (packetBuffer.isReadable()) {
                // Packet ID should be 0x00 for Login Start
                int packetId = VarIntUtil.readVarInt(packetBuffer);

                if (packetId == 0x00) { // Login Start
                    String playerName = VarIntUtil.readString(packetBuffer);
                    java.util.UUID playerUUID = VarIntUtil.readUUID(packetBuffer); // Assuming UUID is always sent in 1.20.4

                    LOGGER.info("[{}] Player {} (UUID: {}) attempting to log in.",
                                channelId, playerName, playerUUID);

                    // For now, proceed with offline mode login success
                    sendLoginSuccess(ctx, playerUUID, playerName);

                    // Transition to PLAY state
                    ctx.channel().attr(NettyChannelAttributes.CONNECTION_STATE_KEY).set(ConnectionState.PLAY);
                    LOGGER.info("[{}] Player {} successfully logged in and transitioned to PLAY state.", channelId, playerName);

                    // TODO: In a subsequent step:
                    // 1. Create ProxyPlayer and PlayerSession
                    // 2. Store PlayerSession in channel attributes
                    // 3. playerSession.connectToBackend("lobby");
                    // 4. Replace this handler with ClientForwardingHandler

                    // Create ProxyPlayer and PlayerSession
                    ProxyPlayer proxyPlayer = new ProxyPlayer(ctx, playerName, playerUUID);
                    PlayerSession playerSession = new PlayerSession(proxyPlayer, configManager, backendServerManager, backendWorkerGroup);
                    // proxyPlayer.setSession(playerSession); // Already done in PlayerSession constructor

                    // Store PlayerSession in channel attributes
                    ctx.channel().attr(NettyChannelAttributes.PLAYER_SESSION_KEY).set(playerSession);

                    LOGGER.info("[{}] PlayerSession created and stored for {}", channelId, playerName);

                    // Initiate connection to the default lobby server
                    // TODO: Make "lobby" configurable as default server or use first available from BackendServerManager
                    playerSession.connectToBackend("lobby");

                    // Replace this handler with ClientForwardingHandler
                    ctx.pipeline().replace(this, ClientForwardingHandler.NAME, new ClientForwardingHandler());
                    LOGGER.info("[{}] InitialLoginHandler replaced with ClientForwardingHandler for {}", channelId, playerName);

                } else {
                    LOGGER.warn("[{}] Received unexpected packet ID in LOGIN state: 0x{}. Expected 0x00 (Login Start). Closing connection.",
                                channelId, Integer.toHexString(packetId));
                    ctx.close();
                }

            } else {
                LOGGER.warn("[{}] Received an empty buffer in LOGIN state from {}.", channelId, ctx.channel().remoteAddress());
            }
        } finally {
            if (packetBuffer.refCnt() > 0) {
                packetBuffer.release();
            }
        }
    }

    private void sendLoginSuccess(ChannelHandlerContext ctx, java.util.UUID playerUUID, String playerName) {
        ByteBuf responseBuffer = ctx.alloc().buffer();
        // Packet ID for Login Success is 0x02
        VarIntUtil.writeVarInt(responseBuffer, 0x02);
        VarIntUtil.writeUUID(responseBuffer, playerUUID);
        VarIntUtil.writeString(responseBuffer, playerName);
        VarIntUtil.writeVarInt(responseBuffer, 0); // Number of properties (0 for now)
        // No properties array follows if count is 0.

        ctx.writeAndFlush(responseBuffer);
        LOGGER.info("[{}] Sent Login Success to player {}", ctx.channel().id().asShortText(), playerName);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.error("[{}] Exception in InitialLoginHandler: {}", ctx.channel().id().asShortText(), cause.getMessage(), cause);
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        LOGGER.info("[{}] Channel became inactive in InitialLoginHandler.", ctx.channel().id().asShortText());
        super.channelInactive(ctx);
    }
}
