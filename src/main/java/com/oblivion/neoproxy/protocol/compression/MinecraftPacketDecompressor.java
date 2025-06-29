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

            byte[] compressedBytesArray = new byte[in.readableBytes()];
            in.readBytes(compressedBytesArray);

            inflater.setInput(compressedBytesArray);

            // Create a heap byte array for the decompressed output
            byte[] decompressedOutputArray = new byte[dataLength];
            ByteBuf finalDecompressedBuf = null; // Declare here to ensure it's in scope for finally block if needed for release on error

            try {
                int actualDecompressedBytes = inflater.inflate(decompressedOutputArray);

                if (!inflater.finished()) {
                     // This implies the output buffer 'decompressedOutputArray' was too small,
                     // or the input 'compressedBytesArray' was incomplete/corrupted.
                     // Given dataLength is known, this should ideally not happen if dataLength is correct.
                    inflater.reset();
                    throw new RuntimeException("Decompression error: Inflater not finished. Output array possibly too small or stream corrupted. " +
                                               "Decompressed " + actualDecompressedBytes + " of expected " + dataLength);
                }

                if (actualDecompressedBytes != dataLength) {
                    inflater.reset();
                    throw new RuntimeException("Decompression error: Bytes decompressed (" + actualDecompressedBytes +
                                               ") != expected dataLength (" + dataLength + ")");
                }

                // Write the heap array into a ByteBuf (Netty can optimize this if it's heap -> heap or copy if heap -> direct)
                finalDecompressedBuf = ctx.alloc().buffer(dataLength);
                finalDecompressedBuf.writeBytes(decompressedOutputArray, 0, actualDecompressedBytes);

                out.add(finalDecompressedBuf); // Pass the new buffer to the next handler
                LOGGER.trace("Decompressor: Successfully decompressed {} bytes into {} bytes.", compressedBytesArray.length, dataLength);

            } catch (Exception e) {
                if (finalDecompressedBuf != null) {
                    finalDecompressedBuf.release(); // Release buffer if created before error
                }
                inflater.reset(); // Reset inflater on any error during inflation
                LOGGER.error("Error during packet decompression: {}", e.getMessage(), e);
                throw e;
            } finally {
                // Ensure inflater is reset for the next packet, especially if finished() wasn't true but no exception.
                // If an exception occurred, it should have been reset in catch.
                // If finished, it's also good practice to reset if it's to be reused.
                if (inflater.finished() || !ctx.channel().isActive()) { // Reset if finished or channel is closing
                    inflater.reset();
                }
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
