package org.engine;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EvalAccumulatorTest {

    private static final String[] BENCH_FENS = new String[]{
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "r3k2r/2pb1ppp/2pp1q2/p7/1nP1B3/1P2P3/P2N1PPP/R2QK2R w KQkq a6 0 14",
            "4rrk1/2p1b1p1/p1p3q1/4p3/2P2n1p/1P1NR2P/PB3PP1/3R1QK1 b - - 2 24",
            "r3qbrk/6p1/2b2pPp/p3pP1Q/PpPpP2P/3P1B2/2PB3K/R5R1 w - - 16 42",
            "6k1/1R3p2/6p1/2Bp3p/3P2q1/P7/1P2rQ1K/5R2 b - - 4 44",
            "8/8/1p2k1p1/3p3p/1p1P1P1P/1P2PK2/8/8 w - - 3 54",
            "7r/2p3k1/1p1p1qp1/1P1Bp3/p1P2r1P/P7/4R3/Q4RK1 w - - 0 36",
            "r1bq1rk1/pp2b1pp/n1pp1n2/3P1p2/2P1p3/2N1P2N/PP2BPPP/R1BQ1RK1 b - - 2 10",
            "3r3k/2r4p/1p1b3q/p4P2/P2Pp3/1B2P3/3BQ1RP/6K1 w - - 3 87",
            "2r4r/1p4k1/1Pnp4/3Qb1pq/8/4BpPp/5P2/2RR1BK1 w - - 0 42",
            "4q1bk/6b1/7p/p1p4p/PNPpP2P/KN4P1/3Q4/4R3 b - - 0 37"
    };

    @BeforeAll
    static void init() {
        Eval.initializeEval();
    }

    @Test
    void incrementalAccumulatorMatchesRefresh() {
        for (String fen : BENCH_FENS) {
            long[] board = PositionFactory.fromFen(fen);
            Eval.NNUEState inc = new Eval.NNUEState();
            Eval.refreshAccumulator(inc, board);

            // Root equivalence check
            Eval.NNUEState fullRoot = new Eval.NNUEState();
            Eval.refreshAccumulator(fullRoot, board);
            assertEquals(Eval.evaluate(fullRoot, board), Eval.evaluate(inc, board), "Root eval mismatch for FEN: " + fen);

            dfsCheck(fen, board, inc, 3);
        }
    }

    private void dfsCheck(String fen, long[] board, Eval.NNUEState inc, int depth) {
        if (depth == 0) return;

        int[] moves = new int[256];
        int n = MoveGenerator.generateCaptures(board, moves, 0);
        n = MoveGenerator.generateQuiets(board, moves, n);

        for (int i = 0; i < n; i++) {
            int mv = moves[i];

            Eval.doMoveAccumulator(inc, board, mv);
            if (!PositionFactory.makeMoveInPlace(board, mv)) { // illegal
                Eval.undoMoveAccumulator(inc);
                continue;
            }

            // Compare incremental vs full-refresh evaluation at this node
            Eval.NNUEState full = new Eval.NNUEState();
            Eval.refreshAccumulator(full, board);
            int evalInc = Eval.evaluate(inc, board);
            int evalFull = Eval.evaluate(full, board);
            assertEquals(evalFull, evalInc, () -> "Eval mismatch at depth=" + depth + " FEN=" + fen + " move=" + MoveFactory.moveToUci(mv));

            dfsCheck(fen, board, inc, depth - 1);

            PositionFactory.undoMoveInPlace(board);
            Eval.undoMoveAccumulator(inc);
        }
    }
}


