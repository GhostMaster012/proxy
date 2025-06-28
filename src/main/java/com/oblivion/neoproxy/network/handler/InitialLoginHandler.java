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
        LOGGER.info("[{}] InitialLoginHandler added to pipeline. Current state: {}", ctx.channel().id().asShortText(), currentState);
        super.handlerAdded(ctx);
    }


    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf packetBuffer = (ByteBuf) msg;
        String channelId = ctx.channel().id().asShortText();
        ConnectionState currentState = ctx.channel().attr(NettyChannelAttributes.CONNECTION_STATE_KEY).get();

        LOGGER.debug("[{}] InitialLoginHandler received a message in state: {}. Buffer readable bytes: {}",
                     channelId, currentState, packetBuffer.readableBytes());

        if (currentState != ConnectionState.LOGIN) {
            LOGGER.warn("[{}] InitialLoginHandler received message in unexpected state: {}. Ignoring.", channelId, currentState);
            // We could pass it on, but for now, this handler only expects LOGIN state messages.
            // ctx.fireChannelRead(msg); // Or release and return
            packetBuffer.release();
            return;
        }

        try {
            // For now, just peek at the packet ID and log it.
            // We are not processing the login sequence yet.
            if (packetBuffer.isReadable()) {
                int packetId = VarIntUtil.readVarInt(packetBuffer); // This consumes the VarInt
                LOGGER.info("[{}] Received Login Packet ID: 0x{}. Remaining readable bytes in this packet: {}",
                             channelId, Integer.toHexString(packetId), packetBuffer.readableBytes());

                // TODO: Implement actual login packet handling (e.g., Login Start 0x00)
                // For now, we'll just log and close the connection to indicate it's not fully handled.
                LOGGER.warn("[{}] Login sequence not fully implemented. Closing connection after logging packet ID.", channelId);
                ctx.close();

            } else {
                LOGGER.warn("[{}] Received an empty buffer in LOGIN state.", channelId);
            }
        } finally {
            if (packetBuffer.refCnt() > 0) { // VarIntUtil.readVarInt might have read the whole buffer if it was small
                packetBuffer.release();
                LOGGER.debug("[{}] Released packet buffer in InitialLoginHandler.", channelId);
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
