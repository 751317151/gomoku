package com.gomoku.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.logging.Logger;

@Component
public class NettyWebSocketServer implements CommandLineRunner {

    private static final Logger logger = Logger.getLogger(NettyWebSocketServer.class.getName());

    @Value("${netty.port:8887}")
    private int port;

    private final GameServerHandler gameServerHandler;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel channel;

    @Autowired
    public NettyWebSocketServer(GameServerHandler gameServerHandler) {
        this.gameServerHandler = gameServerHandler;
    }

    @Override
    public void run(String... args) throws Exception {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
             .channel(NioServerSocketChannel.class)
             .option(ChannelOption.SO_BACKLOG, 128)
             .childOption(ChannelOption.SO_KEEPALIVE, true)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ch.pipeline().addLast(new HttpServerCodec());
                     ch.pipeline().addLast(new ChunkedWriteHandler());
                     ch.pipeline().addLast(new HttpObjectAggregator(8192));
                     ch.pipeline().addLast(new WebSocketServerProtocolHandler("/"));
                     ch.pipeline().addLast(gameServerHandler);
                 }
             });

            ChannelFuture f = b.bind(port).sync();
            channel = f.channel();

            logger.info("========================================");
            logger.info("  五子棋 Netty WebSocket 已启动 (端口: " + port + ")");
            logger.info("  Spring Boot Web 已启动 (端口: 8080)");
            logger.info("  前端页面: http://localhost:8080");
            logger.info("========================================");
        } catch (Exception e) {
            logger.severe("Netty启动失败: " + e.getMessage());
            destroy();
        }
    }

    @PreDestroy
    public void destroy() {
        if (channel != null) {
            channel.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        logger.info("Netty WebSocket 服务器已关闭");
    }
}
