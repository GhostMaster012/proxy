package com.oblivion.neoproxy.protocol;

import com.oblivion.neoproxy.network.handler.NettyChannelAttributes;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Made this generic to handle both Packet and ByteBuf for now.
// ByteBuf messages are assumed to be pre-serialized (ID + data)
// and will just have their length prefixed.
public class PacketEncoder extends MessageToByteEncoder<Object> {

    private static final Logger LOGGER = LoggerFactory.getLogger(PacketEncoder.class);

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception {
        String channelId = ctx.channel().id().asShortText();
        ConnectionState currentState = ctx.channel().attr(NettyChannelAttributes.CONNECTION_STATE_KEY).get();
        Integer protocolVersion = ctx.channel().attr(NettyChannelAttributes.PROTOCOL_VERSION_KEY).get();

        if (currentState == null) {
            LOGGER.warn("[{}] Connection state is null during encoding, defaulting to HANDSHAKE.", channelId);
            currentState = ConnectionState.HANDSHAKE; // Default, though should be set
        }
        if (protocolVersion == null) {
            // This might be okay if the packet doesn't depend on protocol version for its ID or structure
            LOGGER.warn("[{}] Protocol version is null during encoding, defaulting to 765 (1.20.4).", channelId);
            protocolVersion = 765;
        }

        if (msg instanceof ByteBuf) {
            ByteBuf data = (ByteBuf) msg;
            int dataLength = data.readableBytes();
            LOGGER.debug("[{}] Encoding ByteBuf message. Data length: {}. Current state: {}, Protocol: {}",
                         channelId, dataLength, currentState, protocolVersion);

            VarIntUtil.writeVarInt(out, dataLength); // Length of (PacketID + Data)
            out.writeBytes(data);
            LOGGER.debug("[{}] ByteBuf message encoded. Total bytes written to output buffer (including length prefix): {}",
                         channelId, VarIntUtil.getVarIntSize(dataLength) + dataLength);
            // Netty will release 'data' if it's a direct buffer and it's responsible.
            // If 'data' was allocated by a handler (e.g. ctx.alloc().buffer()),
            // the handler might release it, or Netty's writeAndFlush handles it.
            // For safety, assume Netty handles release of 'data' after it's written to 'out' or by writeAndFlush.
        } else if (msg instanceof Packet) {
            LOGGER.debug("[{}] Encoding Packet message of type {}. Current state: {}, Protocol: {}",
                         channelId, msg.getClass().getSimpleName(), currentState, protocolVersion);
            Packet packet = (Packet) msg;
            ByteBuf packetBody = ctx.alloc().buffer(); // Buffer for Packet ID + Data
            try {
                int packetId = packet.getId(currentState, protocolVersion);
                VarIntUtil.writeVarInt(packetBody, packetId);
                packet.write(packetBody, currentState, protocolVersion);

                int bodyLength = packetBody.readableBytes();
                VarIntUtil.writeVarInt(out, bodyLength); // Total length of (PacketID + Data)
                out.writeBytes(packetBody);
                LOGGER.debug("[{}] Packet message {} encoded. Packet ID: {}, Body length: {}. Total bytes written: {}",
                             channelId, msg.getClass().getSimpleName(), packetId, bodyLength, VarIntUtil.getVarIntSize(bodyLength) + bodyLength);
            } finally {
                packetBody.release();
                LOGGER.trace("[{}] Released temporary packetBody buffer for Packet message.", channelId);
            }
        } else {
            LOGGER.error("[{}] Unsupported message type for encoding: {}", channelId, msg.getClass().getName());
            throw new IllegalArgumentException("Unsupported message type: " + msg.getClass().getName());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        String channelId = ctx.channel().id().asShortText();
        LOGGER.error("[{}] Exception in PacketEncoder: {}", channelId, cause.getMessage(), cause);
        ctx.close();
    }
}
