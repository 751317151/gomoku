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
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;

@Component
public class NettyWebSocketServer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(NettyWebSocketServer.class);
    private static final int MAX_FRAME_PAYLOAD_LENGTH = 65536; // 64KB

    @Value("${netty.port:8887}")
    private int port;

    @Value("${server.port:8080}")
    private int httpPort;

    @Value("${netty.idle-timeout:120}")
    private int idleTimeout;

    private final GameServerHandler gameServerHandler;
    private final BinaryMessageEncoder binaryEncoder;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel channel;

    @Autowired
    public NettyWebSocketServer(GameServerHandler gameServerHandler) {
        this.gameServerHandler = gameServerHandler;
        this.binaryEncoder = new BinaryMessageEncoder();
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
                            ch.pipeline().addLast(new HttpObjectAggregator(MAX_FRAME_PAYLOAD_LENGTH));
                            // 心跳检测：读空闲超时
                            ch.pipeline().addLast(new IdleStateHandler(
                                    idleTimeout, 0, 0, TimeUnit.SECONDS));
                            // WebSocket 协议升级（路径 "/" 支持 JSON 和 Binary）
                            ch.pipeline().addLast(new WebSocketServerProtocolHandler(
                                    "/", null, true, MAX_FRAME_PAYLOAD_LENGTH));
                            // 帧路由器：Text帧走JSON，Binary帧走二进制解码
                            ch.pipeline().addLast(new WebSocketFrameRouter());
                            // 二进制编码器（出站：GameMessage → BinaryWebSocketFrame）
                            ch.pipeline().addLast(binaryEncoder);
                            // 业务处理器（入站：GameMessage 或 TextWebSocketFrame）
                            ch.pipeline().addLast(gameServerHandler);
                        }
                    });

            ChannelFuture f = b.bind(port).sync();
            channel = f.channel();

            logger.info("========================================");
            logger.info(" 五子棋 Netty WebSocket 已启动 (端口: {})", port);
            logger.info(" Spring Boot Web 已启动 (端口: {})", httpPort);
            logger.info(" 前端页面: http://localhost:{}", httpPort);
            logger.info(" 心跳超时: {}s", idleTimeout);
            logger.info(" 协议: JSON(Text) + Binary(ByteBuf)");
            logger.info("========================================");
        } catch (Exception e) {
            logger.error("Netty启动失败: {}", e.getMessage());
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
