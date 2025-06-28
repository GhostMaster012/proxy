package com.oblivion.neoproxy.network.handler;

import com.oblivion.neoproxy.protocol.ConnectionState;
import com.oblivion.neoproxy.protocol.VarIntUtil;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import java.nio.charset.StandardCharsets;

public class ServerListPingHandler extends ChannelInboundHandlerAdapter {

    private static final Gson GSON = new Gson();
    private static final String SERVER_NAME = "NeoProxy 1.20.4";
    private static final int SERVER_PROTOCOL = 765;
    private static final int MAX_PLAYERS = 1000;
    private static final String MOTD_TEXT = "§6NeoProxy §7- §bNext Generation Minecraft Proxy";

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.channel().attr(NettyChannelAttributes.CONNECTION_STATE_KEY).set(ConnectionState.HANDSHAKE);
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf packetData = (ByteBuf) msg; // PacketDecoder sends ByteBuf (PacketID + Data)
        ConnectionState currentState = ctx.channel().attr(NettyChannelAttributes.CONNECTION_STATE_KEY).get();

        try {
            if (currentState == null) { // Should have been set in channelActive
                currentState = ConnectionState.HANDSHAKE;
                ctx.channel().attr(NettyChannelAttributes.CONNECTION_STATE_KEY).set(currentState);
            }

            if (currentState == ConnectionState.HANDSHAKE) {
                handleHandshake(ctx, packetData);
            } else if (currentState == ConnectionState.STATUS) {
                handleStatus(ctx, packetData);
            } else {
                // LOGIN or PLAY state, not handled by this handler for server list ping
                // System.out.println("Packet received in state " + currentState + ", not handled by ServerListPingHandler.");
            }
        } finally {
            packetData.release(); // Release the buffer after processing
        }
    }

    private void handleHandshake(ChannelHandlerContext ctx, ByteBuf packetData) {
        int packetId = VarIntUtil.readVarInt(packetData);
        if (packetId != 0x00) {
            // Not a handshake packet, or malformed
            ctx.close();
            return;
        }

        int protocolVersion = VarIntUtil.readVarInt(packetData);
        ctx.channel().attr(NettyChannelAttributes.PROTOCOL_VERSION_KEY).set(protocolVersion);

        int serverAddressLength = VarIntUtil.readVarInt(packetData);
        packetData.skipBytes(serverAddressLength); // Server address string

        packetData.skipBytes(2); // Server port unsigned short

        int nextStateValue = VarIntUtil.readVarInt(packetData);
        if (nextStateValue == 1) { // Status
            ctx.channel().attr(NettyChannelAttributes.CONNECTION_STATE_KEY).set(ConnectionState.STATUS);
        } else if (nextStateValue == 2) { // Login
            ctx.channel().attr(NettyChannelAttributes.CONNECTION_STATE_KEY).set(ConnectionState.LOGIN);
            // Further login handling would be done by a different handler
        } else {
            ctx.close(); // Invalid next state
        }
    }

    private void handleStatus(ChannelHandlerContext ctx, ByteBuf packetData) {
        int packetId = VarIntUtil.readVarInt(packetData);

        if (packetId == 0x00) { // Status Request
            sendServerStatusResponse(ctx);
        } else if (packetId == 0x01) { // Ping Request
            sendPongResponse(ctx, packetData);
        } else {
            // Unknown packet in STATUS state
            ctx.close();
        }
    }

    private void sendServerStatusResponse(ChannelHandlerContext ctx) {
        JsonObject responseJson = new JsonObject();

        JsonObject versionJson = new JsonObject();
        versionJson.addProperty("name", SERVER_NAME);
        versionJson.addProperty("protocol", SERVER_PROTOCOL);
        responseJson.add("version", versionJson);

        JsonObject playersJson = new JsonObject();
        playersJson.addProperty("max", MAX_PLAYERS);
        playersJson.addProperty("online", 0); // Static online count for now
        // playersJson.add("sample", new JsonArray()); // Optional: add sample players
        responseJson.add("players", playersJson);

        // Using Adventure for MOTD to allow color codes via legacy serializer if needed,
        // but problem asks for specific JSON string, implying direct text.
        // For "§6NeoProxy §7- §bNext Generation Minecraft Proxy"
        // This can be directly put as a string in description, or use Adventure.
        // Adventure ensures correct JSON formatting for text components.
        Component motdComponent = Component.text(MOTD_TEXT); // Raw string with section signs
        responseJson.add("description", GsonComponentSerializer.gson().serializeToTree(motdComponent));

        String responseString = GSON.toJson(responseJson);
        byte[] responseBytes = responseString.getBytes(StandardCharsets.UTF_8);

        ByteBuf buffer = ctx.alloc().buffer();
        VarIntUtil.writeVarInt(buffer, 0x00); // Packet ID for Status Response
        VarIntUtil.writeVarInt(buffer, responseBytes.length);
        buffer.writeBytes(responseBytes);

        ctx.writeAndFlush(buffer); // PacketEncoder will prefix this whole buffer with its length
    }

    private void sendPongResponse(ChannelHandlerContext ctx, ByteBuf pingRequestPayload) {
        // pingRequestPayload here contains only the long payload from the Ping Request packet
        // as PacketDecoder has already read packetId.
        ByteBuf pongPacket = ctx.alloc().buffer();
        VarIntUtil.writeVarInt(pongPacket, 0x01); // Packet ID for Pong Response
        pongPacket.writeBytes(pingRequestPayload.readBytes(pingRequestPayload.readableBytes())); // Echo the payload

        ctx.writeAndFlush(pongPacket); // PacketEncoder will prefix this whole buffer with its length
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}
