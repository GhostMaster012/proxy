package com.oblivion.neoproxy.protocol.compression;

import com.oblivion.neoproxy.protocol.VarIntUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.zip.Inflater;

public class MinecraftPacketDecompressor extends ByteToMessageDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftPacketDecompressor.class);
    private final Inflater inflater;
    private final int compressionThreshold; // Though not directly used by decompressor logic, good to have if server behavior depends on it.

    public MinecraftPacketDecompressor(int compressionThreshold) {
        this.inflater = new Inflater();
        this.compressionThreshold = compressionThreshold; // Store if needed for context or future logic
        LOGGER.info("MinecraftPacketDecompressor initialized. Compression threshold (for context): {}", compressionThreshold);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!in.isReadable()) {
            return;
        }

        in.markReaderIndex();
        int dataLength = VarIntUtil.readVarInt(in);

        if (dataLength == 0) {
            // Data is not compressed, the rest of the buffer is the packet.
            // The initial PacketDecoder would have already framed this 'in' ByteBuf to be exactly one packet's content (minus outer length).
            // So, 'in' now contains (PacketID + Uncompressed Data).
            LOGGER.trace("Decompressor: Data Length is 0, packet is uncompressed. Passing through {} bytes.", in.readableBytes());
            out.add(in.readRetainedSlice(in.readableBytes()));
        } else {
            // Data is compressed. dataLength is the size of the uncompressed data.
            if (dataLength < this.compressionThreshold && this.compressionThreshold != -1) {
                 // This case should ideally not happen if the server respects its own threshold for sending compressed data.
                 // BungeeCord/Velocity might throw an error here if dataLength < threshold.
                 // For robustness, we could proceed if dataLength > 0, or log a warning.
                 LOGGER.warn("Decompressor: Received compressed packet with uncompressed length {} which is below threshold {}. This might be unusual.", dataLength, this.compressionThreshold);
            }
             if (dataLength > 2097152) { // 2MB, Minecraft's typical limit for uncompressed packet size
                LOGGER.error("Decompressor: Uncompressed data length {} too large (max 2MB). Closing connection.", dataLength);
                ctx.close();
                return;
            }


            LOGGER.trace("Decompressor: Data Length is {}, packet is compressed. Compressed size: {} bytes.", dataLength, in.readableBytes());

            byte[] compressedBytes = new byte[in.readableBytes()];
            in.readBytes(compressedBytes);

            inflater.setInput(compressedBytes);
            ByteBuf decompressed = ctx.alloc().buffer(dataLength); // Allocate buffer for uncompressed data
            try {
                int bytesDecompressed = inflater.inflate(decompressed.array(), decompressed.arrayOffset() + decompressed.writerIndex(), dataLength);
                decompressed.writerIndex(decompressed.writerIndex() + bytesDecompressed);

                if (inflater.finished()) { // Ensure all input was consumed and output matches expected length
                     if (bytesDecompressed != dataLength) {
                        inflater.reset();
                        throw new RuntimeException("Decompression error: Bytes decompressed (" + bytesDecompressed + ") != expected dataLength (" + dataLength + ")");
                    }
                    out.add(decompressed); // decompressed ByteBuf, don't release, pass to next handler
                    LOGGER.trace("Decompressor: Successfully decompressed {} bytes into {} bytes.", compressedBytes.length, dataLength);
                } else {
                    // This means inflater needs more output space or more input, but we expect one full packet.
                    decompressed.release();
                    inflater.reset();
                    throw new RuntimeException("Decompression error: Inflater not finished. Bytes decompressed: " + bytesDecompressed + ", expected: " + dataLength);
                }
            } catch (Exception e) {
                decompressed.release(); // Release buffer on error
                inflater.reset();
                LOGGER.error("Error during packet decompression: {}", e.getMessage(), e);
                throw e; // Rethrow to allow Netty to handle it (e.g., close connection)
            } finally {
                 if (!inflater.finished() && inflater.needsInput() && compressedBytes.length > 0) {
                    // If inflater is not finished but needs more input, it implies the compressed data was incomplete.
                    // This should have been caught by the framing decoder if it happened.
                    // However, if it still occurs, reset.
                    inflater.reset();
                 } else if (!inflater.finished()){ // If not finished for other reasons, reset.
                    inflater.reset();
                 }
                 // Note: Inflater is typically reset when finished or on error.
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // Called when the channel is closed or becomes inactive.
        // Good place to release resources like the inflater.
        inflater.end();
        LOGGER.trace("MinecraftPacketDecompressor: Channel inactive, inflater ended for context {}.", ctx.channel().id().asShortText());
        super.channelInactive(ctx);
    }

    // It's also good practice to end the inflater if an exception causes the handler to be removed or channel to close.
    // ByteToMessageDecoder's exceptionCaught usually closes the context, which would trigger channelInactive.
    // If this handler itself throws an exception that's caught by its own exceptionCaught, cleanup there.
    // However, ByteToMessageDecoder's own exceptionCaught forwards to super.exceptionCaught which closes.
    // If a user overrides exceptionCaught and doesn't call super or close, then manual cleanup might be needed.
    // For now, channelInactive should cover most cases.
}
