package org.engine;

/**
 * Simple move orderer that, for now, only prioritizes the transposition table move.
 */
public final class MoveOrderer {

    private MoveOrderer() {}

    public static void AssignNegaMaxScores(int[] moves, int[] scores, int size, int ttMove, int killer, long[] board) {
        if (moves == null || scores == null) return;
        for (int i = 0; i < size; i++) scores[i] = 0;

        final int tt = MoveFactory.intToMove(ttMove);
        final int k = MoveFactory.intToMove(killer);

        // Ensure captures/promotions are searched before killers; TT move first.
        for (int i = 0; i < size; i++) {
            int m = MoveFactory.intToMove(moves[i]);
            if (m == 0) { scores[i] = Integer.MIN_VALUE; continue; }

            if (m == tt && tt != 0) {
                scores[i] = Integer.MAX_VALUE - 10;
                continue;
            }

            int flags = MoveFactory.GetFlags(m);

            // Treat any move that captures a piece on the target square or en-passant as capture
            boolean isCapture = false;
            if (flags == MoveFactory.FLAG_EN_PASSANT) {
                isCapture = true;
            } else {
                int to = MoveFactory.GetTo(m);
                int targetPiece = PositionFactory.pieceAt(board, to);
                isCapture = targetPiece != -1;
            }

            boolean isPromotion = (flags == MoveFactory.FLAG_PROMOTION);
            boolean isCastle = (flags == MoveFactory.FLAG_CASTLE);

            if (isCapture || isPromotion) {
                scores[i] = 1_000_000;
            } else if (!isCastle) {
                scores[i] = (m == k && k != 0) ? 500_000 : 0;
            } else {
                scores[i] = 0;
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


