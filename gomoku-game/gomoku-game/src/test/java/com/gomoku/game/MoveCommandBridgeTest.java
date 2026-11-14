package com.gomoku.game;

import com.gomoku.dto.GameSyncDto;
import com.gomoku.model.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 通用对战接口的桥接语义测试
 *
 * 重构把 GameEngine 拆成「通用对战骨架 + 棋盘子接口」之后，有两件事必须钉死：
 * 1. 棋类通过 MoveCommand 出招时，行为要和原来的 placeStone 完全一致
 * 2. 坐标指令与行动序号指令不能互相串线（下棋收到技能下标、RPG 收到坐标都必须拒绝）
 * 这两条一旦破了，房间层会把「串线的指令」当成合法操作，缺陷极难定位。
 */
class MoveCommandBridgeTest {

    @Test
    void boardEngineAcceptsCoordinateCommand() {
        GomokuBoard board = new GomokuBoard();
        assertTrue(board.applyMove(MoveCommand.at(7, 7), GomokuBoard.BLACK));
        assertEquals(GomokuBoard.BLACK, board.getBoardSnapshot()[7][7]);
        assertEquals(1, board.getMoveSeq());
        assertEquals(1, board.getMoveCount());
    }

    @Test
    void boardEngineRejectsActionCommand() {
        GomokuBoard board = new GomokuBoard();
        // 棋盘玩法没有「技能」概念，收到行动序号必须拒绝而不是猜一个坐标
        assertFalse(board.applyMove(MoveCommand.action(2), GomokuBoard.BLACK));
        assertEquals(0, board.getMoveSeq(), "被拒的指令不应推进手数");
        assertEquals(0, board.getMoveCount());
    }

    @Test
    void moveCommandWithoutOriginDegradesToPlacement() {
        // 象棋走子用 4 参；落子类游戏传 -1 起点时应退化成普通落子
        MoveCommand cmd = MoveCommand.of(-1, -1, 5, 5);
        assertFalse(cmd.hasOrigin());
        assertEquals(5, cmd.getRow());
        assertEquals(5, cmd.getCol());

        GomokuBoard board = new GomokuBoard();
        assertTrue(board.applyMove(cmd, GomokuBoard.BLACK));
        assertEquals(GomokuBoard.BLACK, board.getBoardSnapshot()[5][5]);
    }

    @Test
    void xiangqiMoveStillWorksThroughCommand() {
        XiangqiBoard board = new XiangqiBoard();
        // 红炮 (7,1) → (4,1)，走法与 placeStone 4 参版本一致
        assertTrue(board.applyMove(MoveCommand.from(7, 1, 4, 1), 1));
        assertEquals(16, board.getBoardSnapshot()[4][1], "红炮应移动到 (4,1)");
        assertEquals(0, board.getBoardSnapshot()[7][1], "起点应清空");
    }

    @Test
    void boardEngineRejectsOriginOnPlacementGame() {
        GomokuBoard board = new GomokuBoard();
        // 五子棋没有走子概念：带起点的指令不能被当成「落在终点」静默执行
        assertFalse(board.applyMove(MoveCommand.from(0, 0, 7, 7), GomokuBoard.BLACK));
        assertEquals(0, board.getBoardSnapshot()[7][7]);
    }

    @Test
    void rpgGameReachesRoomThroughActionCommand() {
        GameRoom room = new GameRoom("rpg-bridge", GameType.RPG);
        Player a = new Player("a", "甲", null);
        Player b = new Player("b", "乙", null);
        room.addPlayer(a);
        room.addPlayer(b);

        assertEquals(GameRoom.State.PLAYING, room.getState());
        // 棋类没有盘面，房间不该去取 getBoardSnapshot（GameEngine 上已经没有这个方法）
        GameSyncDto sync = room.buildSyncDataForReconnect(a);
        assertNull(sync.getBoard(), "RPG 同步包不应带 board");
        assertNotNull(sync.getView(), "RPG 同步包必须带 view，否则前端画不出面板");

        // 甲（先手）用行动序号出招
        assertTrue(room.handleCommand(a, MoveCommand.action(RpgBattleEngine.Action.ATTACK.getIndex()), 1),
                "行动序号应被房间接受");
        assertEquals(1, room.getEngine().getMoveSeq());

        // 乙收到坐标指令应被拒绝（RPG 只认 action）
        assertFalse(room.handleCommand(b, MoveCommand.at(3, 4), 2), "RPG 不应接受坐标指令");
        assertEquals(1, room.getEngine().getMoveSeq());
    }

    @Test
    void rpgRoomSendsFighterNames() {
        GameRoom room = new GameRoom("rpg-name", GameType.RPG);
        Player a = new Player("a", "阿甲", null);
        Player b = new Player("b", "阿乙", null);
        room.addPlayer(a);
        room.addPlayer(b);

        RpgBattleEngine engine = (RpgBattleEngine) room.getEngine();
        assertEquals("阿甲", engine.getFighterName(a.getStone()));
        assertEquals("阿乙", engine.getFighterName(b.getStone()));
    }
}
