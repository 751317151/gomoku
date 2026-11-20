package com.gomoku.game;

import com.gomoku.server.AccountController;
import com.gomoku.server.CheckInController;
import com.gomoku.server.CoachController;
import com.gomoku.server.GameApiController;
import com.gomoku.server.GuestController;
import com.gomoku.server.NettyWebSocketServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Spring 上下文冒烟：真实装配所有 Bean，堵住「单测全绿、启动即挂」的盲区。
 *
 * 背景：SeasonService / AccountService 都曾因为「新增第二个构造器后忘记 @Autowired」
 * 导致 Spring 启动失败，而纯单元测试不加载上下文发现不了。
 * 这里用 RANDOM_PORT + netty.port=0 让 Netty 绑定临时端口，避免与本地服务冲突。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"netty.port=0"})
class ContextSmokeTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void coreServicesAndControllersAreWired() {
        // 服务层：赛季、签到、账号、身份、记录、成长、房间
        assertNotNull(context.getBean(SeasonService.class));
        assertNotNull(context.getBean(CheckInService.class));
        assertNotNull(context.getBean(AccountService.class));
        assertNotNull(context.getBean(GuestIdentityService.class));
        assertNotNull(context.getBean(GameRecordService.class));
        assertNotNull(context.getBean(PlayerProgressService.class));
        assertNotNull(context.getBean(EloService.class));
        assertNotNull(context.getBean(RoomManager.class));
        // 组队：服务与 REST 入口都要能装配（RoomManager 依赖 PartyService）
        assertNotNull(context.getBean(PartyService.class));
        assertNotNull(context.getBean(com.gomoku.server.PartyController.class));
        assertNotNull(context.getBean(DuelService.class));

        // 控制器层：REST 入口真实存在且依赖注入成功
        assertNotNull(context.getBean(GameApiController.class));
        assertNotNull(context.getBean(GuestController.class));
        assertNotNull(context.getBean(AccountController.class));
        assertNotNull(context.getBean(CheckInController.class));
        assertNotNull(context.getBean(CoachController.class));

        // Netty 服务器：CommandLineRunner 已随上下文实例化（端口为 0）
        assertNotNull(context.getBean(NettyWebSocketServer.class));
    }
}
