package com.oblivion.neoproxy;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import com.oblivion.neoproxy.config.ConfigManager;
import com.oblivion.neoproxy.config.ListenerConfig;
import com.oblivion.neoproxy.network.handler.ServerListPingHandler; // Import added

@SpringBootApplication
public class NeoProxyApplication {

    private static final Logger LOGGER = LoggerFactory.getLogger(NeoProxyApplication.class);
    // private static final int PROXY_PORT = 25565; // Will be replaced by config

    @Autowired
    private ConfigManager configManager;

    public static void main(String[] args) {
        SpringApplication.run(NeoProxyApplication.class, args);
    }

    @Bean
    public CommandLineRunner nettyServerRunner() {
        return args -> {
            ListenerConfig listenerConfig = configManager.getDefaultListener();
            if (listenerConfig == null) {
                LOGGER.error("!!! CRITICAL: No listener configuration found. Proxy cannot start. !!!");
                LOGGER.error("!!! Please ensure a valid config.yml is present with at least one listener. !!!");
                return; // Exit if no listener config
            }

            String host = listenerConfig.getHost().split(":")[0];
            int port = Integer.parseInt(listenerConfig.getHost().split(":")[1]);

            LOGGER.info("Attempting to start NeoProxy on {}:{}", host, port);

            EventLoopGroup bossGroup = new NioEventLoopGroup(1);
            EventLoopGroup workerGroup = new NioEventLoopGroup();

            try {
                ServerBootstrap b = new ServerBootstrap();
                b.group(bossGroup, workerGroup)
                 .channel(NioServerSocketChannel.class)
                 .childHandler(new ChannelInitializer<SocketChannel>() {
                     @Override
                     public void initChannel(SocketChannel ch) throws Exception {
                         LOGGER.debug("Initializing channel for client: {}", ch.remoteAddress());
                         // Pipeline for handling Minecraft protocol
                         ch.pipeline().addLast("packetDecoder", new com.oblivion.neoproxy.protocol.PacketDecoder());
                         ch.pipeline().addLast("packetEncoder", new com.oblivion.neoproxy.protocol.PacketEncoder());
                         // Pass ListenerConfig to ServerListPingHandler
                         ch.pipeline().addLast("serverListPingHandler", new ServerListPingHandler(listenerConfig, configManager.getConfiguration()));
                     }
                 });

                ChannelFuture f = b.bind(host, port).sync();
                LOGGER.info("NeoProxy started successfully on {}:{}", host, port);
                f.channel().closeFuture().sync();
            } catch (Exception e) {
                LOGGER.error("Failed to start NeoProxy Netty server:", e);
            }
            finally {
                LOGGER.info("Shutting down Netty worker and boss groups.");
                workerGroup.shutdownGracefully();
                bossGroup.shutdownGracefully();
            }
        };
    }
}
