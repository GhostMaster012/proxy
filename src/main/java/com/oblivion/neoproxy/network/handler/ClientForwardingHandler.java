package com.oblivion.neoproxy.network.handler;

import com.oblivion.neoproxy.player.PlayerSession;
import com.oblivion.neoproxy.protocol.ConnectionState;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientForwardingHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientForwardingHandler.class);
    public static final String NAME = "clientForwardingHandler";


    // PlayerSession will be stored in the channel's attributes.
    // This handler is created per-channel, so it doesn't store session state itself,
    // but retrieves it from the channel.

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        PlayerSession session = ctx.channel().attr(NettyChannelAttributes.PLAYER_SESSION_KEY).get();
        if (session != null) {
            LOGGER.info("[{}] ClientForwardingHandler added to pipeline for player {}.",
                        ctx.channel().id().asShortText(), session.getPlayer().getUsername());
        } else {
            LOGGER.warn("[{}] ClientForwardingHandler added, but no PlayerSession found in channel attributes. This is unexpected.",
                        ctx.channel().id().asShortText());
            // Consider closing the channel if session is essential and missing.
        }
        super.handlerAdded(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        PlayerSession session = ctx.channel().attr(NettyChannelAttributes.PLAYER_SESSION_KEY).get();
        ConnectionState state = ctx.channel().attr(NettyChannelAttributes.CONNECTION_STATE_KEY).get();

        if (session == null) {
            LOGGER.warn("[{}] ClientForwardingHandler received message, but no PlayerSession found. Closing connection.",
                        ctx.channel().id().asShortText());
            if (msg instanceof ByteBuf) {
                ((ByteBuf) msg).release();
            }
            ctx.close();
            return;
        }

        if (state != ConnectionState.PLAY) {
            LOGGER.warn("[{}] ClientForwardingHandler for player {} received message in unexpected state: {}. Ignoring.",
                        ctx.channel().id().asShortText(), session.getPlayer().getUsername(), state);
            if (msg instanceof ByteBuf) {
                ((ByteBuf) msg).release();
            }
            return;
        }

        if (!(msg instanceof ByteBuf)) {
            LOGGER.warn("[{}] ClientForwardingHandler for player {} received non-ByteBuf message: {}. Ignoring.",
                        ctx.channel().id().asShortText(), session.getPlayer().getUsername(), msg.getClass().getName());
            // Potentially call super.channelRead(ctx, msg) if other handlers might process it,
            // but for forwarding, we expect ByteBuf.
            return;
        }

        ByteBuf packet = (ByteBuf) msg;
        // LOGGER.debug("Forwarding packet from client {} to server.", session.getPlayer().getUsername());
        session.sendToServer(packet.retain()); // PlayerSession is responsible for releasing if not sent.
                                              // Retain because ByteToMessageDecoder might release original.
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        PlayerSession session = ctx.channel().attr(NettyChannelAttributes.PLAYER_SESSION_KEY).get();
        if (session != null) {
            LOGGER.info("Client {} disconnected. Notifying PlayerSession.", session.getPlayer().getUsername());
            session.close(); // This will handle backend disconnection and cleanup.
        } else {
            LOGGER.warn("[{}] Client channel became inactive, but no PlayerSession found.", ctx.channel().id().asShortText());
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        PlayerSession session = ctx.channel().attr(NettyChannelAttributes.PLAYER_SESSION_KEY).get();
        String username = (session != null) ? session.getPlayer().getUsername() : "Unknown Player";
        LOGGER.error("Exception in ClientForwardingHandler for player {}: {}", username, cause.getMessage(), cause);
        if (session != null) {
            session.close(); // Close session on exception
        } else {
            ctx.close();
        }
    }
}
