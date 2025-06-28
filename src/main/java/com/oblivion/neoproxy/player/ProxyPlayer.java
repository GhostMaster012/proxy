package com.oblivion.neoproxy.player;

import io.netty.channel.ChannelHandlerContext;
import java.util.UUID;

public class ProxyPlayer {

    private final ChannelHandlerContext clientCtx; // Connection to the client
    private final String username;
    private final UUID uuid;
    private PlayerSession session; // Link to the player's session
    private String currentServerName; // Name of the backend server the player is currently on

    public ProxyPlayer(ChannelHandlerContext clientCtx, String username, UUID uuid) {
        this.clientCtx = clientCtx;
        this.username = username;
        this.uuid = uuid;
    }

    public ChannelHandlerContext getClientCtx() {
        return clientCtx;
    }

    public String getUsername() {
        return username;
    }

    public UUID getUuid() {
        return uuid;
    }

    public PlayerSession getSession() {
        return session;
    }

    public void setSession(PlayerSession session) {
        this.session = session;
    }

    public String getCurrentServerName() {
        return currentServerName;
    }

    public void setCurrentServerName(String currentServerName) {
        this.currentServerName = currentServerName;
    }

    public void sendMessage(String message) {
        // TODO: Implement sending a chat message to the player
        // This will involve creating a Chat Packet and sending it via clientCtx
        // For now, can log or be a placeholder
        clientCtx.channel().attr(com.oblivion.neoproxy.network.handler.NettyChannelAttributes.CONNECTION_STATE_KEY).get(); // Example usage
        System.out.println("Placeholder: Message to " + username + ": " + message);
    }

    public void disconnect(String reason) {
        // TODO: Implement disconnecting the player with a reason
        // This will involve creating a Disconnect Packet and sending it
        System.out.println("Placeholder: Disconnecting " + username + " for reason: " + reason);
        if (clientCtx.channel().isActive()) {
            // Send disconnect packet here before closing
            clientCtx.close();
        }
    }

    @Override
    public String toString() {
        return "ProxyPlayer{" +
               "username='" + username + '\'' +
               ", uuid=" + uuid +
               ", currentServerName='" + currentServerName + '\'' +
               ", clientCtxId=" + (clientCtx != null ? clientCtx.channel().id().asShortText() : "null") +
               '}';
    }
}
