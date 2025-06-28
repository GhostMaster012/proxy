package com.oblivion.neoproxy.protocol;

import io.netty.buffer.ByteBuf;

public class VarIntUtil {

    private static final int SEGMENT_BITS = 0x7F; // 0111 1111
    private static final int CONTINUE_BIT = 0x80; // 1000 0000

    public static int readVarInt(ByteBuf buf) {
        int value = 0;
        int position = 0;
        byte currentByte;

        while (true) {
            currentByte = buf.readByte();
            value |= (currentByte & SEGMENT_BITS) << position;

            if ((currentByte & CONTINUE_BIT) == 0) break;

            position += 7;

            if (position >= 32) throw new RuntimeException("VarInt is too big");
        }
        return value;
    }

    public static void writeVarInt(ByteBuf buf, int value) {
        while (true) {
            if ((value & ~SEGMENT_BITS) == 0) { // Check if the rest of the bits are 0
                buf.writeByte(value);
                return;
            }
            buf.writeByte((value & SEGMENT_BITS) | CONTINUE_BIT);
            value >>>= 7; // Unsigned right shift
        }
    }

    // Helper method for calculating the size of a VarInt, useful for packet length prefixing
    public static int getVarIntSize(int value) {
        int size = 0;
        while (true) {
            size++;
            if ((value & ~SEGMENT_BITS) == 0) {
                return size;
            }
            value >>>= 7;
        }
    }
}
