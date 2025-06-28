package com.oblivion.neoproxy.protocol;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

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

    public static String readString(ByteBuf buf) {
        int length = readVarInt(buf);
        if (length < 0) {
            throw new IllegalArgumentException("String length cannot be negative: " + length);
        }
        // Max length of a string in Minecraft is 32767 characters, each char can be up to 3 bytes in UTF-8.
        // So, max byte length is roughly 32767 * 3. A VarInt can represent higher values,
        // so a sanity check on length might be good depending on context.
        // For now, trusting the client/server sends valid lengths.
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void writeString(ByteBuf buf, String string) {
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
        // Max length check (32767 characters, but here we check byte length)
        // if (bytes.length > (32767 * 3)) { // A rough upper bound for byte length
        //    throw new IllegalArgumentException("String too long: " + bytes.length + " bytes");
        // }
        writeVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    public static UUID readUUID(ByteBuf buf) {
        long mostSigBits = buf.readLong();
        long leastSigBits = buf.readLong();
        return new UUID(mostSigBits, leastSigBits);
    }

    public static void writeUUID(ByteBuf buf, UUID uuid) {
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
    }
}
