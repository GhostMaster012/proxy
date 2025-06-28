package com.oblivion.neoproxy.network.handler;

import com.oblivion.neoproxy.protocol.ConnectionState;
import io.netty.util.AttributeKey;

import com.oblivion.neoproxy.player.PlayerSession; // Added import

public class NettyChannelAttributes {
    public static final AttributeKey<ConnectionState> CONNECTION_STATE_KEY = AttributeKey.valueOf("connectionState");
    public static final AttributeKey<Integer> PROTOCOL_VERSION_KEY = AttributeKey.valueOf("protocolVersion");
    public static final AttributeKey<PlayerSession> PLAYER_SESSION_KEY = AttributeKey.valueOf("playerSession");
}
