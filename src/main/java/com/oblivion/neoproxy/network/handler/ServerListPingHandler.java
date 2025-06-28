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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.oblivion.neoproxy.config.ListenerConfig;
import com.oblivion.neoproxy.config.ProxyConfig; // Added import

import java.nio.charset.StandardCharsets;

public class ServerListPingHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerListPingHandler.class);
    private static final Gson GSON = new Gson();

    // Configuration will be injected
    private final ListenerConfig listenerConfig;
    private final ProxyConfig proxyConfig; // To potentially get global settings if needed

    // Constants related to Minecraft protocol version, can be static or derived if needed
    private static final String SERVER_VERSION_NAME_PREFIX = "NeoProxy "; // e.g., "NeoProxy 1.20.4"
    private static final int MINECRAFT_PROTOCOL_VERSION = 765; // For Minecraft 1.20.4

    public ServerListPingHandler(ListenerConfig listenerConfig, ProxyConfig proxyConfig) {
        this.listenerConfig = listenerConfig;
        this.proxyConfig = proxyConfig;
        if (this.listenerConfig == null) {
            LOGGER.error("CRITICAL: ServerListPingHandler initialized with null ListenerConfig!");
            // Consider throwing an IllegalArgumentException or having a fallback,
            // but NeoProxyApplication should prevent this.
        }
         if (this.proxyConfig == null) {
            LOGGER.error("CRITICAL: ServerListPingHandler initialized with null ProxyConfig!");
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // This log is similar to what NeoProxyApplication logs, but specific to the handler.
        // For production, one might be sufficient. The requirement implies this one.
        LOGGER.info("Player connected: {}", ctx.channel().remoteAddress());
        ctx.channel().attr(NettyChannelAttributes.CONNECTION_STATE_KEY).set(ConnectionState.HANDSHAKE);
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        LOGGER.info("Player disconnected: {}", ctx.channel().remoteAddress());
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf packetBuffer = (ByteBuf) msg;
        ConnectionState currentState = ctx.channel().attr(NettyChannelAttributes.CONNECTION_STATE_KEY).get();
        String channelId = ctx.channel().id().asShortText(); // Keep for error/warn logs if needed

        try {
            if (currentState == null) {
                LOGGER.warn("[{}] Connection state was null, defaulting to HANDSHAKE.", channelId);
                currentState = ConnectionState.HANDSHAKE;
                ctx.channel().attr(NettyChannelAttributes.CONNECTION_STATE_KEY).set(currentState);
            }

            // LOGGER.debug("[{}] Received packet in state: {}", channelId, currentState); // Removed DEBUG

            if (!packetBuffer.isReadable()) {
                LOGGER.warn("[{}] Received an empty packet buffer in state {}.", channelId, currentState);
                return;
            }

            if (currentState == ConnectionState.HANDSHAKE) {
                handleHandshake(ctx, packetBuffer);
            } else if (currentState == ConnectionState.STATUS) {
                handleStatus(ctx, packetBuffer);
            } else {
                // LOGGER.debug("[{}] Packet received in state {} (currently unhandled by ServerListPingHandler), ignoring.", channelId, currentState); // Removed DEBUG
                // This case should ideally not be reached if pipeline is managed correctly for LOGIN state.
            }
        } finally {
            packetBuffer.release();
            // LOGGER.debug("[{}] Released packet buffer.", channelId); // Removed DEBUG
        }
    }

    private void handleHandshake(ChannelHandlerContext ctx, ByteBuf packetData) {
        String channelId = ctx.channel().id().asShortText(); // Keep for error/warn logs
        // LOGGER.debug("[{}] Handling HANDSHAKE. Initial readable bytes: {}", channelId, packetData.readableBytes()); // Removed DEBUG

        int packetId = VarIntUtil.readVarInt(packetData);
        // LOGGER.debug("[{}] Handshake Packet ID: 0x{}", channelId, Integer.toHexString(packetId)); // Removed DEBUG

        if (packetId != 0x00) {
            LOGGER.warn("[{}] Invalid Handshake Packet ID: 0x{}. Closing connection.", channelId, Integer.toHexString(packetId));
            ctx.close();
            return;
        }

        int protocolVersion = VarIntUtil.readVarInt(packetData);
        ctx.channel().attr(NettyChannelAttributes.PROTOCOL_VERSION_KEY).set(protocolVersion);
        // LOGGER.debug("[{}] Protocol Version: {}", channelId, protocolVersion); // Removed DEBUG

        int serverAddressLength = VarIntUtil.readVarInt(packetData);
        String serverAddress = packetData.readCharSequence(serverAddressLength, StandardCharsets.UTF_8).toString();
        // LOGGER.debug("[{}] Server Address: {} (length {})", channelId, serverAddress, serverAddressLength); // Removed DEBUG

        int serverPort = packetData.readUnsignedShort();
        // LOGGER.debug("[{}] Server Port: {}", channelId, serverPort); // Removed DEBUG

        int nextStateValue = VarIntUtil.readVarInt(packetData);
        // LOGGER.debug("[{}] Next State: {}", channelId, nextStateValue); // Removed DEBUG

        if (nextStateValue == 1) { // Status
            ctx.channel().attr(NettyChannelAttributes.CONNECTION_STATE_KEY).set(ConnectionState.STATUS);
            LOGGER.info("[{}] Connection ({}) transitioned to STATUS state.", channelId, ctx.channel().remoteAddress());
        } else if (nextStateValue == 2) { // Login
            ctx.channel().attr(NettyChannelAttributes.CONNECTION_STATE_KEY).set(ConnectionState.LOGIN);
            LOGGER.info("[{}] Connection ({}) transitioned to LOGIN state. Replacing ServerListPingHandler with InitialLoginHandler.", channelId, ctx.channel().remoteAddress());
            ctx.pipeline().replace(this, "initialLoginHandler", new InitialLoginHandler(this.proxyConfig, this.listenerConfig));
        } else {
            LOGGER.warn("[{}] Invalid Next State value: {}. Closing connection.", channelId, nextStateValue);
            ctx.close();
        }
        // LOGGER.debug("[{}] Finished HANDSHAKE. Remaining readable bytes: {}", channelId, packetData.readableBytes()); // Removed DEBUG
    }

    private void handleStatus(ChannelHandlerContext ctx, ByteBuf packetData) {
        String channelId = ctx.channel().id().asShortText(); // Keep for error/warn logs
        // LOGGER.debug("[{}] Handling STATUS. Initial readable bytes: {}", channelId, packetData.readableBytes()); // Removed DEBUG

        int packetId = VarIntUtil.readVarInt(packetData);
        // LOGGER.debug("[{}] Status Packet ID: 0x{}", channelId, Integer.toHexString(packetId)); // Removed DEBUG

        if (packetId == 0x00) { // Status Request
            LOGGER.info("[{}] Received Status Request from {}. Sending response.", channelId, ctx.channel().remoteAddress());
            sendServerStatusResponse(ctx);
        } else if (packetId == 0x01) { // Ping Request
            LOGGER.info("[{}] Received Ping Request from {}. Sending pong.", channelId, ctx.channel().remoteAddress());
            sendPongResponse(ctx, packetData);
        } else {
            LOGGER.warn("[{}] Unknown Packet ID in STATUS state: 0x{}. Closing connection.", channelId, Integer.toHexString(packetId));
            ctx.close();
        }
        // LOGGER.debug("[{}] Finished STATUS. Remaining readable bytes: {}", channelId, packetData.readableBytes()); // Removed DEBUG
    }

    private void sendServerStatusResponse(ChannelHandlerContext ctx) {
        String channelId = ctx.channel().id().asShortText();

        if (listenerConfig == null) {
            LOGGER.error("[{}] Cannot send server status response, ListenerConfig is null.", channelId);
            ctx.close(); // Or send a generic error response if possible
            return;
        }

        JsonObject responseJson = new JsonObject();

        // Version
        JsonObject versionJson = new JsonObject();
        // TODO: Potentially make the "1.20.4" part of SERVER_VERSION_NAME_PREFIX configurable or dynamic
        versionJson.addProperty("name", SERVER_VERSION_NAME_PREFIX + "1.20.4");
        versionJson.addProperty("protocol", MINECRAFT_PROTOCOL_VERSION);
        responseJson.add("version", versionJson);

        // Players
        JsonObject playersJson = new JsonObject();
        playersJson.addProperty("max", listenerConfig.getMax_players());
        playersJson.addProperty("online", 0); // Static online count for now, replace with actual count later
        // playersJson.add("sample", new JsonArray()); // Optional: add sample players (requires player data)
        responseJson.add("players", playersJson);

        // Description (MOTD)
        Component motdComponent = Component.text(listenerConfig.getMotd());
        responseJson.add("description", GsonComponentSerializer.gson().serializeToTree(motdComponent));

        // Favicon (Optional)
        // String favicon = proxyConfig.getFavicon(); // Assuming ProxyConfig could hold a base64 favicon string
        // if (favicon != null && !favicon.isEmpty()) {
        //    responseJson.addProperty("favicon", favicon);
        // }

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
