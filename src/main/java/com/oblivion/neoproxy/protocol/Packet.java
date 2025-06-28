package com.oblivion.neoproxy.protocol;

import io.netty.buffer.ByteBuf;

public interface Packet {
    void read(ByteBuf buf, ConnectionState state, int protocolVersion); // Added protocolVersion and state
    void write(ByteBuf buf, ConnectionState state, int protocolVersion); // Added protocolVersion and state
    int getId(ConnectionState state, int protocolVersion); // Packet ID can change based on state and version
}
