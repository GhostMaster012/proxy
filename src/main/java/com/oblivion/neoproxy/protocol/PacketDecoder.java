package com.oblivion.neoproxy.protocol;

import com.oblivion.neoproxy.network.handler.NettyChannelAttributes;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

public class PacketDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!in.isReadable()) {
            return;
        }

        in.markReaderIndex();
        int packetLength = VarIntUtil.readVarInt(in);

        if (in.readableBytes() < packetLength) {
            in.resetReaderIndex(); // Not enough data yet
            return;
        }

        // We have the full packet
        ByteBuf packetData = in.readBytes(packetLength);

        // For now, we'll just pass the raw ByteBuf containing packet ID + data.
        // Later, this will be replaced with actual packet objects.
        // The ServerListPingHandler will read the packet ID from this buffer.
        out.add(packetData);

        // If there's more data in the buffer, the decoder will be called again.
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace(); // Basic error handling
        ctx.close();
    }
}
