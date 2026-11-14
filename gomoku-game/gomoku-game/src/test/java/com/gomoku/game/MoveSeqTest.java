package com.gomoku.game;

import com.gomoku.model.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 落子序号（moveSeq）幂等校验测试
 *
 * 背景：客户端自增 moveSeq，服务端按 getMoveSeq()+1 校验。
 * 一旦某步被静默丢弃，序号就会永久错位，导致后续每一步都被拒绝。
 * 这里钉住三件事：
 * 1. 序号匹配时落子成功
 * 2. 序号不匹配时落子被拒，且服务端手数不变（客户端可据此校准）
 * 3. 重复提交同一序号不会造成重复落子（幂等）
 */
class MoveSeqTest {

    private GameRoom newRoom(GameType type) {
        return new GameRoom("ROOM-SEQ", type);
    }

    private Player newPlayer(String id) {
        return new Player(id, "玩家" + id, null);
    }

    @Test
    void correctSeqIsAccepted() {
        GameRoom room = newRoom(GameType.GOMOKU);
        Player a = newPlayer("a");
        Player b = newPlayer("b");
        room.addPlayer(a);
        room.addPlayer(b);

        assertTrue(room.handleMove(a, 7, 7, 1), "首手 seq=1 应被接受");
        assertEquals(1, room.getEngine().getMoveSeq());
    }

    @Test
    void staleSeqIsRejectedWithoutAdvancingBoard() {
        GameRoom room = newRoom(GameType.GOMOKU);
        Player a = newPlayer("a");
        Player b = newPlayer("b");
        room.addPlayer(a);
        room.addPlayer(b);

        assertTrue(room.handleMove(a, 7, 7, 1));
        // 客户端若把 seq 算成 5（例如本地丢了 4 步），服务端应收敛拒绝且盘面不变
        assertFalse(room.handleMove(b, 8, 8, 5), "seq 超前的落子应被拒绝");
        assertEquals(1, room.getEngine().getMoveSeq(), "被拒后服务端手数不应变化");
        assertEquals(0, ((BoardGameEngine) room.getEngine()).getBoardSnapshot()[8][8], "被拒的落子不应留在盘面");

        // 用正确序号重试应成功 —— 证明拒绝是可恢复的，不是永久锁死
        assertTrue(room.handleMove(b, 8, 8, 2), "用正确 seq 重试应成功");
        assertEquals(2, room.getEngine().getMoveSeq());
    }

    @Test
    void duplicatedSeqCannotDoublePlay() {
        GameRoom room = newRoom(GameType.GOMOKU);
        Player a = newPlayer("a");
        Player b = newPlayer("b");
        room.addPlayer(a);
        room.addPlayer(b);

        assertTrue(room.handleMove(a, 7, 7, 1));
        // 同一条消息重发（网络重传）：seq=1 已用过，必须被拒
        assertFalse(room.handleMove(b, 7, 8, 1), "重复序号应被拒绝，避免重复落子");
        assertEquals(1, room.getEngine().getMoveSeq());

        // 即便换成空位也不行 —— 序号校验先于落子
        assertFalse(room.handleMove(b, 0, 0, 1), "重复序号即便位置合法也应拒绝");
        assertEquals(0, ((BoardGameEngine) room.getEngine()).getBoardSnapshot()[0][0]);
    }

    @Test
    void connectFourAcceptsGravityLanding() {
        GameRoom room = newRoom(GameType.CONNECT4);
        Player a = newPlayer("a");
        Player b = newPlayer("b");
        room.addPlayer(a);
        room.addPlayer(b);

        // 同一列连续两次：必须先落 (5,c) 再落 (4,c)
        assertTrue(room.handleMove(a, 5, 3, 1), "首子应落在该列底部");
        assertTrue(room.handleMove(b, 4, 3, 2), "第二子应叠在上方");
        // 跳过底部直接落中间 → 应被引擎拒绝
        assertFalse(room.handleMove(a, 2, 3, 3), "违反重力落点应被拒绝");
        assertEquals(2, room.getEngine().getMoveSeq(), "非法重力落点不应推进手数");
    }

    /**
     * 终局面板要显示每位玩家自己的 +/- 分：胜者为正、败者为负且绝对值相等。
     * 此前只把总变化量放在 data.eloChange，各玩家的 eloChange 恒为 0，面板永远不显示。
     */
    @Test
    void eloChangeIsWrittenBackPerPlayer() throws Exception {
        GameRoom room = newRoom(GameType.GOMOKU);
        // 注入真实 ELO 服务（写到临时目录，不污染仓库）
        room.setEloService(new EloService(
                java.nio.file.Files.createTempDirectory("elo-test").resolve("elo.properties"), null));

        Player a = newPlayer("a");
        Player b = newPlayer("b");
        room.addPlayer(a);
        room.addPlayer(b);

        // 直接认输制造终局（避免走满五连）
        room.surrender(room.getPlayers().get(0));

        int changeA = room.getPlayers().get(0).getLastEloChange();
        int changeB = room.getPlayers().get(1).getLastEloChange();

        assertTrue(changeA != 0 || changeB != 0, "PvP 终局应产生非零 ELO 变化");
        assertEquals(-changeA, changeB, "双方变化量应互为相反数");
        assertTrue(changeA < 0, "认输方应掉分");
    }
}
