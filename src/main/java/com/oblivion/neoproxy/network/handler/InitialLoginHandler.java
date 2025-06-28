package com.oblivion.neoproxy.network.handler;

import com.oblivion.neoproxy.config.ListenerConfig;
import com.oblivion.neoproxy.config.ProxyConfig;
import com.oblivion.neoproxy.protocol.ConnectionState;
import com.oblivion.neoproxy.protocol.VarIntUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InitialLoginHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(InitialLoginHandler.class);

    private final ProxyConfig proxyConfig;
    private final ListenerConfig listenerConfig;

    public InitialLoginHandler(ProxyConfig proxyConfig, ListenerConfig listenerConfig) {
        this.proxyConfig = proxyConfig;
        this.listenerConfig = listenerConfig;
        if (this.proxyConfig == null || this.listenerConfig == null) {
            LOGGER.warn("InitialLoginHandler created with null configuration objects!");
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
                // Peek at the packet ID for logging, then reset reader index if we're not actually processing it.
                // For now, we consume it as part of the simplified "log and close" logic.
                packetBuffer.markReaderIndex();
                int packetId = VarIntUtil.readVarInt(packetBuffer);
                // packetBuffer.resetReaderIndex(); // Reset if we were only peeking and another handler would process.

                LOGGER.info("[{}] Received Login Attempt from {}. Packet ID: 0x{}. (Full login not yet implemented)",
                             channelId, ctx.channel().remoteAddress(), Integer.toHexString(packetId));

                // TODO: Implement actual login packet handling (e.g., Login Start 0x00)
                LOGGER.warn("[{}] Login sequence for {} not fully implemented. Closing connection.", channelId, ctx.channel().remoteAddress());
                ctx.close();

            } else {
                LOGGER.warn("[{}] Received an empty buffer in LOGIN state from {}.", channelId, ctx.channel().remoteAddress());
            }
        } finally {
            if (packetBuffer.refCnt() > 0) {
                packetBuffer.release();
                // LOGGER.debug("[{}] Released packet buffer in InitialLoginHandler.", channelId); // Removed DEBUG
            }
        }
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
