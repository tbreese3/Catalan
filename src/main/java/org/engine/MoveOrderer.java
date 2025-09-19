package org.engine;


public final class MoveOrderer {
    private static final int SCORE_TT = Integer.MAX_VALUE - 10000;
    private static final int SCORE_CAPTURE_BASE = 1_000_000;
    private static final int[] TYPE_VALUES = {100, 320, 330, 500, 900, 20000};

    public static void AssignNegaMaxScores(int[] moves, int[] scores, int size, int ttMove, int captureCount, long[] board) {
        if (moves == null || scores == null) return;
        for (int i = 0; i < size; i++) scores[i] = 0;
        if (size == 0) return;

        int packedTt = MoveFactory.intToMove(ttMove);
        for (int i = 0; i < size; i++) {
            int m = moves[i] & 0xFFFF;

            if (packedTt != 0 && packedTt == m) {
                scores[i] = SCORE_TT;
                continue;
            }

            scores[i] = (i < captureCount) ? scoreCaptureMVVLVA(board, m) : 0;
        }
    }

    public static void AssignQSearchScores(int[] moves, int[] scores, int size, int ttMove, int captureCount, long[] board) {
        if (moves == null || scores == null) return;
        for (int i = 0; i < size; i++) scores[i] = 0;
        if (size == 0) return;

        int packedTt = MoveFactory.intToMove(ttMove);
        for (int i = 0; i < size; i++) {
            int m = moves[i] & 0xFFFF;

            if (packedTt != 0 && packedTt == m) {
                scores[i] = SCORE_TT;
                continue;
            }

            scores[i] = (i < captureCount) ? scoreCaptureMVVLVA(board, m) : 0;
        }
    }

    private static int scoreCaptureMVVLVA(long[] board, int move) {
        int from = MoveFactory.GetFrom(move);
        int to = MoveFactory.GetTo(move);
        int flags = MoveFactory.GetFlags(move);
        int attackerIdx = PositionFactory.pieceAt(board, from);
        int victimIdx;

        if (flags == MoveFactory.FLAG_EN_PASSANT) {
            boolean attackerIsWhite = attackerIdx >= 0 && attackerIdx < 6;
            victimIdx = attackerIsWhite ? PositionFactory.BP : PositionFactory.WP;
        } else {
            victimIdx = PositionFactory.pieceAt(board, to);
        }

        int victimVal = (victimIdx >= 0) ? TYPE_VALUES[victimIdx % 6] : 0;
        int attackerVal = (attackerIdx >= 0) ? TYPE_VALUES[attackerIdx % 6] : 0;
        return SCORE_CAPTURE_BASE + (victimVal * 16) - attackerVal;
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


