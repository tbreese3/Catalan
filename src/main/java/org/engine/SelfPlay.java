package org.engine;

import java.io.IOException;
import java.util.Random;

public final class SelfPlay {

    private SelfPlay() {}

    private static final int HASH_SIZE = 8;
    private static final int MIN_OPENING_PLY = 8;
    private static final int MAX_OPENING_PLY = 9;
    private static final int DEPTH_LIMIT_DEFAULT = 24;
    private static final int WRITABLE_DATA_LIMIT = 512;
    private static final int ADJUDICATE_SCORE = 3000;
    private static final int ADJUDICATE_MOVES = 4;
    private static final int MAX_FILTERING_SCORE = 6000;
    private static final int MAX_OPENING_SCORE = 1200;

    public static void run(long gamesToRun, int depthLimit, String outPath, double lrL1, double lrL2) throws IOException {
        PositionFactory pf = new PositionFactory();
        MoveGenerator mg = new MoveGenerator();
        Search search = new Search();

        Random rnd = new Random(1234567);

        long totalPositions = 0L;
        long totalUpdated = 0L;
        long sinceLastReport = 0L;
        long startNs = System.nanoTime();

        for (long g = 0; g < gamesToRun; g++) {
            TranspositionTable.TT.clear();
            long[] board = pf.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");

            // Random opening plies in [MIN_OPENING_PLY, MAX_OPENING_PLY]
            int randMoveCount = MIN_OPENING_PLY + rnd.nextInt(MAX_OPENING_PLY - MIN_OPENING_PLY + 1);
            boolean restartGame = false;
            for (int i = 0; i < randMoveCount; i++) {
                int mv = pickRandomLegal(rnd, board, mg, pf);
                if (mv == 0) { restartGame = true; break; }
                pf.makeMoveInPlace(board, mv, mg);
            }
            if (restartGame) { g--; continue; }

            if (countLegal(board, mg, pf) == 0) { g--; continue; }

            {
                Search.Limits pre = new Search.Limits();
                int d = (depthLimit > 0 ? depthLimit : DEPTH_LIMIT_DEFAULT);
                pre.depth = Math.max(8, Math.min(10, d));
                Search.Result pr = search.search(board, pre, null);
                int sc = pr.scoreCp;
                if (!PositionFactory.whiteToMove(board)) sc = -sc;
                if (Math.abs(sc) >= MAX_OPENING_SCORE) { g--; continue; }
            }

            int plies = 0;
            int adjudicationCounter = 0;
            int updatedInGame = 0;

            while (plies < 512) {
                if (pf.isDraw(board)) break;
                int legal = countLegal(board, mg, pf);
                if (legal == 0) break;

                Search.Limits limits = new Search.Limits();
                limits.depth = (depthLimit > 0) ? depthLimit : DEPTH_LIMIT_DEFAULT;
                Search.Result res = search.search(board, limits, null);
                int target = res.scoreCp;

                boolean inCheck = pf.isInCheck(board);
                int best = res.bestMove;
                if (best == 0) best = mg.getFirstLegalMove(board);
                int flags = MoveFactory.GetFlags(best);
                boolean isCastle = (flags == MoveFactory.FLAG_CASTLE);
                boolean isEnPassant = (flags == MoveFactory.FLAG_EN_PASSANT);
                int to = MoveFactory.GetTo(best);
                int targetPiece = (best != 0) ? PositionFactory.pieceAt(board, to) : -1;
                boolean isCapture = (targetPiece != -1 && !isCastle) || isEnPassant;
                boolean badScore = Math.abs(target) > MAX_FILTERING_SCORE;

                if (!inCheck && !isCapture && !badScore) {
                    Eval.trainOnPosition(board, target, lrL1, lrL2);
                    totalUpdated++;
                    updatedInGame++;
                }

                int bestMove = best;
                if (bestMove == 0) bestMove = mg.getFirstLegalMove(board);

                boolean ok = pf.makeMoveInPlace(board, bestMove, mg);
                if (!ok) {
                    bestMove = mg.getFirstLegalMove(board);
                    if (bestMove == 0) break;
                    pf.makeMoveInPlace(board, bestMove, mg);
                }

                plies++;
                totalPositions++;
                sinceLastReport++;

                if (Math.abs(target) >= ADJUDICATE_SCORE) {
                    adjudicationCounter++;
                    if (adjudicationCounter > ADJUDICATE_MOVES) {
                        break;
                    }
                } else {
                    adjudicationCounter = 0;
                }

                if (sinceLastReport >= 100) {
                    sinceLastReport = 0;
                    double elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000.0;
                    double pps = totalUpdated / Math.max(1e-9, elapsedSec);
                    System.out.println(
                            "info string [selfplay] game " + (g + 1) + "/" + gamesToRun +
                            " seen " + totalPositions +
                            " updated " + totalUpdated +
                            " updated/s " + (long) pps);
                }

                if (updatedInGame >= WRITABLE_DATA_LIMIT - 1) {
                    break;
                }
            }
        }

        Eval.saveNetwork(outPath);
        System.out.println("info string [selfplay] Wrote trained network to " + outPath);
    }

    private static int evaluateRaw(long[] board) {
        Eval.NNUEState st = new Eval.NNUEState();
        Eval.refreshAccumulator(st, board);
        return Eval.evaluate(st, board);
    }

    private static int countLegal(long[] bb, MoveGenerator mg, PositionFactory pf) {
        int[] tmp = new int[256];
        boolean inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb));
        int n = 0;
        if (inCheck) n = mg.generateEvasions(bb, tmp, 0);
        else {
            n = mg.generateCaptures(bb, tmp, 0);
            n = mg.generateQuiets(bb, tmp, n);
        }
        int legal = 0;
        for (int i = 0; i < n; i++) {
            int m = tmp[i];
            if (pf.makeMoveInPlace(bb, m, mg)) {
                legal++;
                pf.undoMoveInPlace(bb);
            }
        }
        return legal;
    }

    private static int pickRandomLegal(Random rnd, long[] bb, MoveGenerator mg, PositionFactory pf) {
        int[] tmp = new int[256];
        boolean inCheck = mg.kingAttacked(bb, PositionFactory.whiteToMove(bb));
        int n = 0;
        if (inCheck) n = mg.generateEvasions(bb, tmp, 0);
        else {
            n = mg.generateCaptures(bb, tmp, 0);
            n = mg.generateQuiets(bb, tmp, n);
        }
        if (n == 0) return 0;
        int[] legal = new int[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            int m = tmp[i];
            if (pf.makeMoveInPlace(bb, m, mg)) {
                legal[k++] = m;
                pf.undoMoveInPlace(bb);
            }
        }
        if (k == 0) return 0;
        return legal[rnd.nextInt(k)];
    }
}


