package com.oblivion.neoproxy.protocol;

import com.oblivion.neoproxy.network.handler.NettyChannelAttributes;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

public class PacketDecoder extends ByteToMessageDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(PacketDecoder.class);

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        String channelId = ctx.channel().id().asShortText();
        if (!in.isReadable()) {
            // LOGGER.trace("[{}] Input buffer not readable.", channelId); // Removed TRACE
            return;
        }

        in.markReaderIndex();
        int packetLength;
        try {
            packetLength = VarIntUtil.readVarInt(in);
        } catch (Exception e) { // VarIntUtil might throw if not enough bytes for a full VarInt
            // LOGGER.trace("[{}] Could not read packet length VarInt, resetting reader index. Error: {}", channelId, e.getMessage()); // Removed TRACE
            in.resetReaderIndex();
            return;
        }

        // LOGGER.debug("[{}] Attempting to decode packet. Declared length: {}", channelId, packetLength); // Removed DEBUG

        if (packetLength <= 0) { // Protect against zero or negative length packets
             LOGGER.warn("[{}] Invalid packet length received: {}. Closing connection.", channelId, packetLength);
             ctx.close();
             return;
        }

        if (in.readableBytes() < packetLength) {
            // LOGGER.trace("[{}] Not enough readable bytes ({}) for packet length ({}). Resetting reader index.", // Removed TRACE
            //              channelId, in.readableBytes(), packetLength);
            in.resetReaderIndex(); // Not enough data yet
            return;
        }

        // We have the full packet
        ByteBuf packetData = in.readBytes(packetLength);
        // LOGGER.debug("[{}] Successfully decoded packet. Length: {}, Actual Bytes Read: {}. Passing to next handler.", // Removed DEBUG
        //              channelId, packetLength, packetData.readableBytes());

        // The ServerListPingHandler will read the packet ID from this buffer.
        out.add(packetData); // packetData is an unpooled buffer, handler needs to release it

        // If there's more data in the buffer, the decoder will be called again.
        // LOGGER.trace("[{}] Remaining readable bytes in input buffer after decode: {}", channelId, in.readableBytes()); // Removed TRACE
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        String channelId = ctx.channel().id().asShortText();
        LOGGER.error("[{}] Exception in PacketDecoder: {}", channelId, cause.getMessage(), cause);
        ctx.close();
    }
}
