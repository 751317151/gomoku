package com.gomoku.game;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class GemAuctionCollectionTest {
    private GemAuctionEngine game(int count) {
        GemAuctionEngine game = new GemAuctionEngine(new Random() {
            @Override public int nextInt(int bound) { return 0; }
        });
        game.configurePlayers(count); game.reset(); return game;
    }
    private void act(GemAuctionEngine game, int side, int bid) { assertTrue(game.applyMove(MoveCommand.action(bid), side)); }
    private void round(GemAuctionEngine game, int... bids) { for (int i = 0; i < bids.length; i++) act(game, i + 1, bids[i]); }
    @SuppressWarnings("unchecked")
    private Map<String,Object> view(GemAuctionEngine game, int side) { return (Map<String,Object>) game.getViewState(side); }
    @SuppressWarnings("unchecked")
    private Map<String,Object> player(GemAuctionEngine game, int viewer, int side) {
        return ((List<Map<String,Object>>) view(game, viewer).get("players")).get(side - 1);
    }
    @Test void catalogIsPublicStableAndContainsAllThreeColors() {
        GemAuctionEngine game = game(3);
        assertEquals(view(game, 1).get("lots"), view(game, 0).get("lots"));
        List<?> catalog = (List<?>) view(game, 0).get("lots"); assertEquals(5, catalog.size());
        // 固定随机源给出 蓝、绿、红、红、红。
        int[] expected = {1,2,0,0,0};
        for (int color : expected) { assertEquals(color, game.getCurrentColor()); round(game, 1,0,0); }
        assertEquals(catalog, view(game, 0).get("lots"));
    }
    @Test void threeColorAndPairBonusesStackWithoutBeingPaidTwice() {
        GemAuctionEngine game = game(2);
        round(game, 1,0); round(game, 1,0);
        assertEquals(8, game.getBonusGain(1));
        round(game, 1,0);
        assertEquals(23, game.getGems(1)); // 3+5+7+8
        assertEquals(3, game.getBonusGain(1));
        round(game, 1,0); assertEquals(35, game.getGems(1));
        assertEquals(0, game.getBonusGain(1));
        round(game, 1,0); assertEquals(47, game.getGems(1));
        assertEquals(36, player(game, 0, 1).get("baseScore"));
        assertEquals(11, player(game, 0, 1).get("bonusScore"));
        assertArrayEquals(new int[]{3,1,1}, (int[]) player(game, 0, 1).get("collection"));
        assertEquals(1, game.getWinner()); assertEquals(List.of(1), game.getWinningSides());
    }
    @Test void positiveTiedBidsEachWinButZeroBidsNeverWin() {
        GemAuctionEngine game = game(3); round(game, 2,2,0);
        assertEquals(3, game.getGems(1)); assertEquals(3, game.getGems(2)); assertEquals(0, game.getGems(3));
        round(game, 0,0,0);
        assertEquals(3, game.getGems(1)); assertEquals(30, game.getCoins(1));
        assertArrayEquals(new int[]{0,0,0}, (int[]) player(game, 0, 3).get("collection"));
        assertEquals(false, player(game, 0, 1).get("lastWon"));
    }
    @Test void passingRecoversAtMostTwoCoinsOnlyAfterEveryoneSubmits() {
        GemAuctionEngine game = game(2); round(game, 1,2);
        act(game, 1,0); assertEquals(29, game.getCoins(1));
        assertEquals(1, game.getLastBid(1)); // 弃拍也不能在揭晓前改变公开信息。
        act(game, 2,0);
        assertEquals(30, game.getCoins(1)); assertEquals(30, game.getCoins(2));
        assertEquals(1, player(game, 0, 1).get("lastRecovered"));
        assertEquals(2, player(game, 0, 2).get("lastRecovered"));
    }
    @Test void emptyWalletCanPassThenBidRecoveredCoins() {
        GemAuctionEngine game = game(2);
        round(game, 10,1); round(game, 10,1); round(game, 10,1);
        assertEquals(List.of(0), game.legalActions(1));
        assertFalse(game.applyMove(MoveCommand.action(1), 1));
        round(game, 0,1); assertEquals(2, game.getCoins(1));
        assertEquals(List.of(0,1,2), game.legalActions(1));
        assertFalse(game.applyMove(MoveCommand.action(3), 1));
        round(game, 2,1); assertEquals(0, game.getCoins(1));
    }
    @Test void pendingBidsCoinsAndEffectsCannotLeakIntoOtherViews() {
        GemAuctionEngine game = game(3); round(game, 2,1,0);
        act(game, 1,8);
        assertEquals(8, player(game, 1, 1).get("bid"));
        assertFalse(player(game, 2, 1).containsKey("bid"));
        assertFalse(player(game, 0, 1).containsKey("bid"));
        assertEquals(28, player(game, 0, 1).get("coins"));
        assertEquals(2, player(game, 0, 1).get("lastBid"));
        assertEquals(2, game.getLastBid(1)); assertTrue(game.getLastMoveEffects().isEmpty());
        assertTrue(game.legalActions(1).isEmpty()); assertTrue(game.legalActions(0).isEmpty());
    }
    @Test void invalidAndDuplicateCommandsDoNotMutateRevealOrHistory() {
        GemAuctionEngine game = game(2); round(game, 1,0);
        assertFalse(game.applyMove(null, 1)); assertFalse(game.applyMove(MoveCommand.at(0,0), 1));
        assertFalse(game.applyMove(MoveCommand.action(11), 1));
        assertFalse(game.applyMove(MoveCommand.action(1), 0));
        assertEquals(true, view(game, 0).get("revealed"));
        act(game, 1,1); assertFalse(game.applyMove(MoveCommand.action(0), 1));
        assertEquals(3, game.getMoveCount()); assertEquals(3, game.getMoveSeq());
    }
    @Test void departingPendingPlayerReleasesBarrierAndDoesNotChargeDepartedBid() {
        GemAuctionEngine game = game(3); act(game, 2,2); act(game, 3,1);
        assertTrue(game.forfeit(1)); assertFalse(game.isOver());
        assertEquals(1, game.getActionRound()); assertEquals(3, game.getGems(2));
        assertEquals(30, game.getCoins(1)); assertEquals(2, game.getCurrentTurn());
        assertTrue(game.forfeit(2)); assertEquals(3, game.getWinner());
    }
    @Test void departingSubmittedBidIsExcludedAndNextPendingSeatRemainsPlayable() {
        GemAuctionEngine game = game(4); act(game, 1,10); act(game, 3,2);
        assertTrue(game.forfeit(1)); assertEquals(2, game.getCurrentTurn());
        act(game, 2,1); act(game, 4,0);
        assertEquals(30, game.getCoins(1)); assertEquals(3, game.getGems(3));
        assertEquals(0, game.getGems(1));
    }
    @Test void historiesAreDeepSnapshotsAndEveryAwardAndRecoveryIsRecorded() {
        GemAuctionEngine game = game(2); round(game, 1,0); round(game, 0,1);
        List<int[]> history = game.getMoveHistory();
        assertEquals(4, history.size()); assertTrue(history.stream().allMatch(row -> row.length == 10));
        assertArrayEquals(new int[]{1,0,0,30,3,2,2,0,0,1}, history.get(2));
        history.get(0)[2] = 999; game.getLastMoveEffects().get(0)[2] = 999;
        ((int[]) player(game, 0, 1).get("collection"))[1] = 999;
        assertEquals(3, game.getMoveHistory().get(0)[2]);
        assertEquals(1, ((int[]) player(game, 0, 1).get("collection"))[1]);
        assertEquals(0, game.getLastMoveEffects().get(0)[2]);
        game.reset(); assertEquals(0, game.getMoveCount()); assertEquals(30, game.getCoins(1));
        assertEquals(0, game.getGems(1)); assertFalse(player(game, 0, 1).containsKey("lastBid"));
    }
    @Test void allPassIsAZeroScoreTieAndGameCannotContinueAfterEnd() {
        GemAuctionEngine game = game(3);
        for (int i=0;i<5;i++) round(game, 0,0,0);
        assertNull(game.getWinner()); assertEquals(List.of(1,2,3), game.getWinningSides());
        assertEquals(0, game.getGems(1)); assertEquals(30, game.getCoins(1));
        assertFalse(game.applyMove(MoveCommand.action(1),1)); assertTrue(game.legalActions(1).isEmpty());
    }
    @Test void eightPlayerAiGamesStayLegalAndFinishAtEveryDifficulty() {
        for(int difficulty : new int[]{2,4,6}) {
            GemAuctionEngine game = game(8);
            while(!game.isOver()) {
                int side=game.getCurrentTurn(); MoveCommand move=GemAuctionAI.INSTANCE.decide(game,side,difficulty);
                assertNotNull(move); assertTrue(game.legalActions(side).contains(move.getAction()));
                assertTrue(game.applyMove(move,side)); assertTrue(game.getMoveCount()<=40);
                for(int i=1;i<=8;i++) assertTrue(game.getCoins(i)>=0 && game.getCoins(i)<=30);
            }
            assertEquals(40,game.getMoveCount()); assertFalse(game.getWinningSides().isEmpty());
            for(int side=1;side<=8;side++) {
                final int s=side;
                assertEquals(game.getGems(s),game.getMoveHistory().stream().filter(row->row[0]==s).mapToInt(row->row[2]).sum());
            }
            assertNull(GemAuctionAI.INSTANCE.decide(game,1,difficulty));
        }
    }
}
