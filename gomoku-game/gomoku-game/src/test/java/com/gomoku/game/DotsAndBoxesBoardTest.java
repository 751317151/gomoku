package com.gomoku.game;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class DotsAndBoxesBoardTest {
    private DotsAndBoxesBoard game(int count) { DotsAndBoxesBoard g=new DotsAndBoxesBoard();g.configurePlayers(count);g.reset();return g; }
    private void draw(DotsAndBoxesBoard g,int r,int c) { assertTrue(g.placeStone(r,c,g.getCurrentTurn())); }
    private Map<?,?> view(DotsAndBoxesBoard g) { return (Map<?,?>)g.getViewState(0); }
    @Test void metadataAndFortyPlayableEdges() {
        DotsAndBoxesBoard g=game(4);
        assertEquals(GameType.DOTS_BOXES,g.type());assertEquals(9,g.getRows());assertEquals(9,g.getCols());
        assertEquals(30,g.getStepTimeSeconds());assertEquals(600,g.getTotalTimeSeconds());
        assertEquals(40,g.legalMoves().size());assertEquals(16,view(g).get("remainingBoxes"));
        assertEquals(4,((List<?>)view(g).get("players")).size());assertNull(g.getWinner());assertNull(g.getScoreSummary());
        assertThrows(IllegalArgumentException.class,()->g.configurePlayers(1));
        assertThrows(IllegalArgumentException.class,()->g.configurePlayers(5));
        assertEquals(2,GameType.DOTS_BOXES.resolvePlayerCount(0));
    }
    @Test void invalidMovesDoNotChangePositionOrEffects() {
        DotsAndBoxesBoard g=game(2);draw(g,0,1);
        int[][] before=g.getBoardSnapshot();List<int[]> effects=g.getLastMoveEffects();
        assertFalse(g.placeStone(0,1,2));assertFalse(g.placeStone(0,3,1));
        assertFalse(g.placeStone(0,0,2));assertFalse(g.placeStone(1,1,2));
        assertFalse(g.placeStone(-1,2,2));assertFalse(g.placeStone(8,9,2));assertFalse(g.placeStone(2,3,0));
        assertFalse(g.applyMove(null,2));assertFalse(g.applyMove(MoveCommand.action(0),2));
        assertFalse(g.applyMove(MoveCommand.attack(0,3),2));
        assertFalse(g.applyMove(MoveCommand.from(0,1,0,3),2));assertFalse(g.pass(2));
        assertTrue(Arrays.deepEquals(before,g.getBoardSnapshot()));
        assertArrayEquals(effects.get(0),g.getLastMoveEffects().get(0));assertEquals(1,g.getMoveSeq());
    }
    @Test void playersCycleInSeatOrderUntilSomebodyClosesBox() {
        DotsAndBoxesBoard g=game(4);
        for(int side=1;side<=4;side++) {assertEquals(side,g.getCurrentTurn());draw(g,0,side*2-1);}
        assertEquals(1,g.getCurrentTurn());assertEquals(0,g.getScore(1));
    }
    @Test void fourthEdgeOwnsBoxRegardlessOfWhoDrewEarlierEdgesAndKeepsTurn() {
        DotsAndBoxesBoard g=game(2);draw(g,0,1);draw(g,1,0);draw(g,2,1);draw(g,1,2);
        assertEquals(2,g.getBoardSnapshot()[1][1]);assertEquals(1,g.getScore(2));assertEquals(2,g.getCurrentTurn());
        assertEquals(true,view(g).get("continued"));assertEquals(1,view(g).get("lastCaptured"));
        assertEquals(2,g.getLastMoveEffects().size());assertArrayEquals(new int[]{1,2,2,1,1},g.getMoveHistory().get(3));
        draw(g,0,3);assertEquals(1,g.getCurrentTurn());assertEquals(false,view(g).get("continued"));
        assertEquals(1,g.getLastMoveEffects().size());
    }
    @Test void sharedEdgeCanClaimTwoBoxesAtOnce() {
        DotsAndBoxesBoard g=game(3);
        for(int[] edge:new int[][]{{0,1},{1,0},{2,1},{0,3},{1,4},{2,3}}) draw(g,edge[0],edge[1]);
        int actor=g.getCurrentTurn();draw(g,1,2);
        assertEquals(2,g.getScore(actor));assertEquals(actor,g.getCurrentTurn());assertEquals(3,g.getLastMoveEffects().size());
        assertEquals(actor,g.getBoardSnapshot()[1][1]);assertEquals(actor,g.getBoardSnapshot()[1][3]);
    }
    @Test void capturesCanContinueAcrossAWholeChain() {
        DotsAndBoxesBoard g=game(2);
        for(int c=1;c<9;c+=2) draw(g,0,c);
        for(int c=1;c<9;c+=2) draw(g,2,c);
        draw(g,1,0);draw(g,1,8);int actor=g.getCurrentTurn();
        draw(g,1,2);assertEquals(actor,g.getCurrentTurn());draw(g,1,4);assertEquals(actor,g.getCurrentTurn());draw(g,1,6);
        assertEquals(4,g.getScore(actor));assertEquals(actor,g.getCurrentTurn());assertEquals(2,view(g).get("lastCaptured"));
    }
    @Test void fullBoardHasExactlySixteenOwnedBoxesAndTiedLeaders() {
        DotsAndBoxesBoard g=game(2);
        while(!g.isOver()) {int[] m=g.legalMoves().get(0);draw(g,m[0],m[1]);}
        assertEquals(40,g.getMoveCount());assertEquals(8,g.getScore(1));assertEquals(8,g.getScore(2));
        assertNull(g.getWinner());assertTrue(g.getWinningSides().isEmpty());
        assertEquals(0,view(g).get("remainingBoxes"));assertEquals(0,view(g).get("remainingEdges"));
        assertEquals(false,view(g).get("continued"));assertTrue(g.legalMoves().isEmpty());
        assertFalse(g.placeStone(0,1,1));assertFalse(g.forfeit(1));assertTrue(g.getScoreSummary().contains("8 格"));
    }
    @Test void randomFullGamesKeepEffectsScoresAndHistoryConsistentAtEverySeatCount() {
        for(int count=2;count<=4;count++) for(int seed=0;seed<8;seed++) {
            DotsAndBoxesBoard g=game(count);List<int[]> edges=g.legalMoves();Collections.shuffle(edges,new Random(seed));
            int[][] reconstructed=new int[9][9];int completed=0;
            for(int[] edge:edges) {
                int actor=g.getCurrentTurn();int before=g.getScore(actor);draw(g,edge[0],edge[1]);
                int gained=g.getScore(actor)-before;completed+=gained;
                assertTrue(gained>=0 && gained<=2);
                for(int[] e:g.getLastMoveEffects()) reconstructed[e[0]][e[1]]=e[2];
                assertTrue(Arrays.deepEquals(reconstructed,g.getBoardSnapshot()));
                if(!g.isOver()) assertEquals(gained>0?actor:actor%count+1,g.getCurrentTurn());
            }
            assertEquals(16,completed);assertEquals(40,g.getMoveHistory().size());
            int max=0;for(int side=1;side<=count;side++) max=Math.max(max,g.getScore(side));
            for(int side:g.getWinningSides()) assertEquals(max,g.getScore(side));
            assertEquals(g.getWinningSides().size()==1?g.getWinningSides().get(0):null,g.getWinner());
        }
    }
    @Test void departingCurrentOrOtherSeatSkipsOnlyThatSeatAndPreservesMarks() {
        DotsAndBoxesBoard g=game(4);draw(g,0,1);
        assertTrue(g.forfeit(2));assertEquals(3,g.getCurrentTurn());assertEquals(1,g.getBoardSnapshot()[0][1]);
        assertTrue(g.forfeit(1));assertEquals(3,g.getCurrentTurn());draw(g,0,3);assertEquals(4,g.getCurrentTurn());
        assertFalse(g.placeStone(0,5,1));assertFalse(g.forfeit(1));assertTrue(g.forfeit(4));
        assertTrue(g.isOver());assertEquals(3,g.getWinner());assertEquals(List.of(3),g.getWinningSides());
    }
    @Test void departedLeaderKeepsTheirBoxesButCannotWinFinalRanking() {
        DotsAndBoxesBoard g=game(3);
        for(int c=1;c<9;c+=2) draw(g,0,c);
        for(int c=1;c<9;c+=2) draw(g,2,c);
        draw(g,1,0);draw(g,1,8);int leader=g.getCurrentTurn();draw(g,1,2);draw(g,1,4);draw(g,1,6);
        assertEquals(4,g.getScore(leader));assertTrue(g.forfeit(leader));
        assertEquals(leader,g.getBoardSnapshot()[1][1]);
        while(!g.isOver()) {int[] edge=g.legalMoves().get(0);draw(g,edge[0],edge[1]);}
        assertFalse(g.getWinningSides().contains(leader));int total=0;for(int side=1;side<=3;side++) total+=g.getScore(side);assertEquals(16,total);
    }
    @Test void snapshotsSearchCopiesAndResetDoNotMutateLiveGame() {
        DotsAndBoxesBoard g=game(4);g.setPlayerName(1,"甲");draw(g,0,1);
        g.getBoardSnapshot()[0][1]=4;g.getMoveHistory().get(0)[2]=4;g.getLastMoveEffects().get(0)[2]=4;
        DotsAndBoxesBoard copy=g.copyForSearch();draw(copy,0,3);
        assertEquals(1,g.getMoveSeq());assertEquals(1,g.getBoardSnapshot()[0][1]);
        assertArrayEquals(new int[]{0,1,1,0,0},g.getMoveHistory().get(0));assertEquals(0,g.getScore(2));
        g.forfeit(3);g.reset();assertEquals(40,g.legalMoves().size());assertTrue(g.isActive(3));
        assertEquals(0,g.getMoveSeq());assertTrue(g.getMoveHistory().isEmpty());assertTrue(g.getLastMoveEffects().isEmpty());
        assertEquals("甲",((Map<?,?>)((List<?>)view(g).get("players")).get(0)).get("name"));
    }
    @Test void mediumAndHardAiTakeAnAvailableBoxAndDoNotMutateRealPosition() {
        for(int difficulty:new int[]{4,6}) {
            DotsAndBoxesBoard g=game(2);draw(g,0,1);draw(g,1,0);draw(g,2,1);
            int[][] before=g.getBoardSnapshot();int[] move=DotsAndBoxesAI.INSTANCE.getBestMove(g,2,difficulty);
            assertArrayEquals(new int[]{1,2},Arrays.copyOf(move,2));assertTrue(Arrays.deepEquals(before,g.getBoardSnapshot()));
        }
    }
    @Test void mediumAvoidsGivingAwayThirdSideWhenSafeEdgesRemain() {
        DotsAndBoxesBoard g=game(2);draw(g,0,1);draw(g,1,0);
        int[] move=DotsAndBoxesAI.INSTANCE.getBestMove(g,1,4);
        assertEquals(0,g.adjacentWithSides(move[0],move[1],2));
        assertNull(DotsAndBoxesAI.INSTANCE.getBestMove(g,2,6));
    }
    @Test void allAiDifficultiesFinishLegalTwoThreeAndFourSeatGames() {
        for(int difficulty:new int[]{2,4,6}) for(int count=2;count<=4;count++) {
            DotsAndBoxesBoard g=game(count);
            while(!g.isOver()) {
                int side=g.getCurrentTurn();int[] move=DotsAndBoxesAI.INSTANCE.getBestMove(g,side,difficulty);
                assertNotNull(move);assertTrue(g.placeStone(move[0],move[1],side));assertTrue(g.getMoveCount()<=40);
            }
            assertEquals(40,g.getMoveCount());assertNull(DotsAndBoxesAI.INSTANCE.getBestMove(g,1,difficulty));
        }
    }
}
