package org.engine;

/**
 * Simple move orderer that, for now, only prioritizes the transposition table move.
 */
public final class MoveOrderer {

    private MoveOrderer() {}

    public static void AssignNegaMaxScores(int[] moves, int[] scores, int size, int ttMove) {
        if (moves == null || scores == null) return;
        for (int i = 0; i < size; i++) scores[i] = 0;
        if (ttMove == 0) return;
        for (int i = 0; i < size; i++) {
            int m = moves[i];
            if (MoveFactory.intToMove(ttMove) == MoveFactory.intToMove(m)) {
                scores[i] = 1;
                break;
            }
        }
    }

    public static void AssignQSearchScores(int[] moves, int[] scores, int size, int ttMove) {
        if (moves == null || scores == null) return;
        for (int i = 0; i < size; i++) scores[i] = 0;
        if (ttMove == 0) return;
        for (int i = 0; i < size; i++) {
            int m = moves[i];
            if (MoveFactory.intToMove(ttMove) == MoveFactory.intToMove(m)) {
                scores[i] = 1;
                break;
            }
        }
    }

    public static int GetNextMove(int[] moves, int[] scores, int size, int listIndex) {
        if (moves == null || scores == null || size == 0 || listIndex >= size) return 0;
        int max = Integer.MIN_VALUE;
        int maxIndex = listIndex;
        for (int i = listIndex; i < size; i++) {
            if (scores[i] > max) {
                max = scores[i];
                maxIndex = i;
            }
        }
        int tmpScore = scores[maxIndex];
        scores[maxIndex] = scores[listIndex];
        scores[listIndex] = tmpScore;
        int tmpMove = moves[maxIndex];
        moves[maxIndex] = moves[listIndex];
        moves[listIndex] = tmpMove;
        return moves[listIndex];
    }
}


