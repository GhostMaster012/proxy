package com.oblivion.neoproxy;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class NeoProxyApplication {

    private static final int PROXY_PORT = 25565;

    public static void main(String[] args) {
        SpringApplication.run(NeoProxyApplication.class, args);
    }

    @Bean
    public CommandLineRunner nettyServerRunner() {
        return args -> {
            EventLoopGroup bossGroup = new NioEventLoopGroup(1);
            EventLoopGroup workerGroup = new NioEventLoopGroup();

            try {
                ServerBootstrap b = new ServerBootstrap();
                b.group(bossGroup, workerGroup)
                 .channel(NioServerSocketChannel.class)
                 .childHandler(new ChannelInitializer<SocketChannel>() {
                     @Override
                     public void initChannel(SocketChannel ch) throws Exception {
                         // Pipeline for handling Minecraft protocol
                         ch.pipeline().addLast("packetDecoder", new com.oblivion.neoproxy.protocol.PacketDecoder());
                         ch.pipeline().addLast("packetEncoder", new com.oblivion.neoproxy.protocol.PacketEncoder());
                         // Add the ServerListPingHandler to handle handshake and status
                         ch.pipeline().addLast("serverListPingHandler", new com.oblivion.neoproxy.network.handler.ServerListPingHandler());
                         System.out.println("Client connected: " + ch.remoteAddress() + " with pipeline configured.");
                     }
                 });

                ChannelFuture f = b.bind(PROXY_PORT).sync();
                System.out.println("NeoProxy started on port " + PROXY_PORT);
                f.channel().closeFuture().sync();
            } finally {
                workerGroup.shutdownGracefully();
                bossGroup.shutdownGracefully();
            }
        };
    }
}
