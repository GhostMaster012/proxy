package com.oblivion.neoproxy.protocol.compression;

import com.oblivion.neoproxy.protocol.VarIntUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.zip.Deflater;

public class MinecraftPacketCompressor extends MessageToByteEncoder<ByteBuf> {

    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftPacketCompressor.class);
    private final Deflater deflater;
    private final int compressionThreshold;
    private final byte[] buffer = new byte[8192]; // Reusable buffer for deflater output

    public MinecraftPacketCompressor(int compressionThreshold) {
        this.compressionThreshold = compressionThreshold;
        this.deflater = new Deflater();
        LOGGER.info("MinecraftPacketCompressor initialized with threshold: {}", compressionThreshold);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf uncompressedData, ByteBuf out) throws Exception {
        int uncompressedSize = uncompressedData.readableBytes();

        if (uncompressedSize < this.compressionThreshold) {
            // Data is smaller than threshold, send uncompressed
            VarIntUtil.writeVarInt(out, 0); // Data Length 0 means uncompressed (actual length is derived by PacketEncoder later)
            out.writeBytes(uncompressedData);
            LOGGER.trace("Compressor: Packet size {} < threshold {}, sending uncompressed (Data Length 0 prefix).", uncompressedSize, this.compressionThreshold);
        } else {
            // Data meets threshold, compress it
            VarIntUtil.writeVarInt(out, uncompressedSize); // Write original uncompressed size

            byte[] dataToCompress = new byte[uncompressedSize];
            uncompressedData.readBytes(dataToCompress); // Read all bytes from input ByteBuf

            deflater.setInput(dataToCompress);
            deflater.finish(); // Indicate that this is the entire input dataset

            int compressedSize = 0;
            while (!deflater.finished()) {
                int bytesDeflated = deflater.deflate(this.buffer);
                if (bytesDeflated > 0) {
                    out.writeBytes(this.buffer, 0, bytesDeflated);
                    compressedSize += bytesDeflated;
                } else if (deflater.needsInput() && !deflater.finished()) {
                    // Should not happen if finish() was called and input was set correctly
                    throw new IllegalStateException("Deflater needs input but finish() was called.");
                }
            }
            deflater.reset(); // Reset deflater for next use

            LOGGER.trace("Compressor: Compressed packet from {} bytes to {} bytes (uncompressed size prefix: {}).",
                         uncompressedSize, compressedSize, uncompressedSize);
        }
    }

    // @Override
    // public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    //     // This method is final in ChannelHandlerAdapter, cannot be overridden.
    //     // Rely on Deflater's own finalizer or ensure channel closure leads to GC.
    //     // If this becomes a critical leak, a different handler structure or manual cleanup call
    //     // upon removing from pipeline would be needed.
    //     deflater.end();
    //     LOGGER.trace("MinecraftPacketCompressor removed, deflater ended.");
    //     super.handlerRemoved(ctx);
    // }

    // Note: Deflater.end() should ideally be called. If this handler is removed from the
    // pipeline before the channel is closed, 'end()' might not be invoked if we only rely on
    // channel closure events that might not propagate to outbound handlers in the same way
    // as 'channelInactive' for inbound. For now, we'll omit explicit cleanup here
    // if 'handlerRemoved' is final and no other suitable lifecycle method is available
    // for outbound handlers to tie into for this specific purpose.
    // The Deflater object itself has a finalizer that calls end().
}
