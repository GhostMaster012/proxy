package com.oblivion.neoproxy.protocol;

import com.oblivion.neoproxy.network.handler.NettyChannelAttributes;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

// Made this generic to handle both Packet and ByteBuf for now.
// ByteBuf messages are assumed to be pre-serialized (ID + data)
// and will just have their length prefixed.
public class PacketEncoder extends MessageToByteEncoder<Object> {

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception {
        ConnectionState currentState = ctx.channel().attr(NettyChannelAttributes.CONNECTION_STATE_KEY).get();
        Integer protocolVersion = ctx.channel().attr(NettyChannelAttributes.PROTOCOL_VERSION_KEY).get();

        if (currentState == null) {
            currentState = ConnectionState.HANDSHAKE; // Default, though should be set
        }
        if (protocolVersion == null) {
            protocolVersion = 765; // Default, should be set during handshake
        }

        if (msg instanceof ByteBuf) {
            // This is for pre-serialized packets (e.g., from ServerListPingHandler)
            // These ByteBufs already contain Packet ID + Data.
            // We just need to prefix them with their own length.
            ByteBuf data = (ByteBuf) msg;
            VarIntUtil.writeVarInt(out, data.readableBytes()); // Length of (PacketID + Data)
            out.writeBytes(data);
            // data.release(); // The caller of writeAndFlush (e.g. ServerListPingHandler) should release if it's a pooled buffer it created.
                              // If it's Unpooled.wrappedBuffer or similar, it might not need release or it's handled differently.
                              // For Unpooled.buffer() like in ServerListPingHandler, it should be released there after writeAndFlush.
                              // However, Netty's writeAndFlush usually handles releasing the buffer once it's written.
                              // Let's assume Netty handles release of 'data' after it's written to 'out'.
        } else if (msg instanceof Packet) {
            // This is for structured Packet objects
            Packet packet = (Packet) msg;
            ByteBuf packetBody = ctx.alloc().buffer(); // Buffer for Packet ID + Data
            try {
                VarIntUtil.writeVarInt(packetBody, packet.getId(currentState, protocolVersion));
                packet.write(packetBody, currentState, protocolVersion);

                VarIntUtil.writeVarInt(out, packetBody.readableBytes()); // Total length of (PacketID + Data)
                out.writeBytes(packetBody);
            } finally {
                packetBody.release(); // Release the temporary buffer
            }
        } else {
            throw new IllegalArgumentException("Unsupported message type: " + msg.getClass().getName());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace(); // Basic error handling
        ctx.close();
    }
}
