package org.core;

import org.chesslib.Board;
import org.chesslib.move.Move;
import org.chesslib.move.MoveGenerator;

import java.util.ArrayList;
import java.util.List;

public final class Search {

    public static final int MAX_PLY = 128;
    private static final int INFTY = 1_000_000;
    private static final int MATE_VALUE = 32000;
    private static final int SCORE_NONE = 123456789; // sentinel

    // Reference-style aspiration parameters
    private static final int ASP_START_DEPTH = 4; // AspWindowStartDepth
    private static final int ASP_START_DELTA = 6; // AspWindowStartDelta

    public static final class Limits {
        public int depth = -1;     // fixed depth if > 0
        public long softMs = 0L;   // preferred stop after finishing an iteration
        public long hardMs = 0L;   // absolute cutoff
        public int multiPV = 1;    // number of PVs to report
    }

    public static final class Result {
        public Move bestMove;
        public int scoreCp;
        public List<Move> pv = new ArrayList<>();
    }

    @FunctionalInterface
    public interface InfoHandler {
        // multipv is 1-based (like UCI)
        void onInfo(int depth, int seldepth, long nodes, long nps, int hashfull,
                    int scoreCp, long timeMs, int multipv, List<Move> pv);
    }

    private static final class StackEntry {
        final Move[] pv = new Move[MAX_PLY];
        int pvLength = 0;
        boolean inCheck = false;
    }

    private static final class RootMove {
        final Move move;
        int score = -INFTY;
        int averageScore = SCORE_NONE;
        long nodes = 0L;

        final Move[] pv = new Move[MAX_PLY];
        int pvLength = 0;

        RootMove(Move m) { this.move = m; }
    }

    // Runtime state
    private volatile boolean stopRequested = false;
    private long startTimeMs;
    private long softStopTimeMs;
    private long hardStopTimeMs;
    private long nodes;
    private int selDepth;
    private StackEntry[] stack;

    private final List<RootMove> rootMoves = new ArrayList<>();
    private int currentPvIdx = 0;

    public void stop() { stopRequested = true; }

    public Result search(Board root, Limits limits, InfoHandler infoHandler) {
        stopRequested = false;
        startTimeMs = nowMs();
        nodes = 0L;
        selDepth = 0;
        softStopTimeMs = limits.softMs > 0 ? startTimeMs + limits.softMs : Long.MAX_VALUE;
        hardStopTimeMs = limits.hardMs > 0 ? startTimeMs + limits.hardMs : Long.MAX_VALUE;

        stack = new StackEntry[MAX_PLY + 6];
        for (int i = 0; i < stack.length; i++) stack[i] = new StackEntry();

        Result result = new Result();

        // Build root move list (no internal move ordering elsewhere)
        List<Move> legals = MoveGenerator.generateLegalMoves(root);
        if (legals.isEmpty()) {
            result.bestMove = null;
            result.scoreCp = root.isMated() ? -MATE_VALUE : 0;
            result.pv = new ArrayList<>();
            return result;
        }
        rootMoves.clear();
        for (Move m : legals) rootMoves.add(new RootMove(m));

        final int requestedMultiPV = Math.max(1, limits.multiPV);
        final int maxDepth = limits.depth > 0 ? limits.depth : 64;

        // Iterative deepening
        for (int depth = 1; depth <= maxDepth; depth++) {
            if (stopRequested || nowMs() >= hardStopTimeMs) break;

            // Prefer last iteration's best first
            sortRootMovesFrom(0);

            int multiPV = Math.min(requestedMultiPV, rootMoves.size());
            for (currentPvIdx = 0; currentPvIdx < multiPV; currentPvIdx++) {
                if (stopRequested || nowMs() >= hardStopTimeMs) break;

                // Window size based on the top move's average (like reference)
                int avgScore = rootMoves.get(0).averageScore == SCORE_NONE ? 0 : rootMoves.get(0).averageScore;
                int window = ASP_START_DELTA + (avgScore * avgScore) / 13000;

                // Center around the current PV move's last score
                RootMove centerRM = rootMoves.get(currentPvIdx);
                int center = (centerRM.score == -INFTY) ? 0 : centerRM.score;

                int alpha = -INFTY, beta = INFTY;
                if (depth >= ASP_START_DEPTH) {
                    alpha = Math.max(-INFTY, center - window);
                    beta  = Math.min( INFTY, center + window);
                }

                int failHighCount = 0;

                while (true) {
                    // reset PV stacks (belt-and-suspenders)
                    for (int i = 0; i < stack.length; i++) {
                        stack[i].pvLength = 0;
                        stack[i].inCheck = false;
                    }

                    int adjustedDepth = Math.max(1, depth - failHighCount);
                    int score = rootNegamax(root, adjustedDepth, alpha, beta);

                    // if stopped, bail out immediately; do not reorder based on partial writes
                    if (stopRequested || nowMs() >= hardStopTimeMs) break;

                    // rootNegamax updated the current move's PV/score; keep list ordered from pvIdx onward
                    sortRootMovesFrom(currentPvIdx);

                    if (score <= alpha) {
                        beta  = (alpha + beta) / 2;
                        alpha = Math.max(-INFTY, score - window);
                        failHighCount = 0;
                    } else if (score >= beta) {
                        beta = Math.min(INFTY, score + window);
                        if (score < 2000) failHighCount++;
                    } else {
                        break; // inside window
                    }

                    window += window / 3;
                }
            }

            // Stream info lines for each PV at this depth
            if (infoHandler != null) {
                long elapsed = Math.max(1, nowMs() - startTimeMs);
                long nps = (nodes * 1000L) / elapsed;
                for (int i = 0; i < Math.min(requestedMultiPV, rootMoves.size()); i++) {
                    RootMove rm = rootMoves.get(i);
                    List<Move> pvList = new ArrayList<>(rm.pvLength);
                    for (int k = 0; k < rm.pvLength; k++) pvList.add(rm.pv[k]);
                    infoHandler.onInfo(depth, selDepth, nodes, nps, 0, rm.score, elapsed, i + 1, pvList);
                }
            }

            // Update final snapshot
            RootMove best = rootMoves.get(0);
            result.bestMove = best.move;
            result.scoreCp = best.score;
            result.pv = new ArrayList<>(best.pvLength);
            for (int i = 0; i < best.pvLength; i++) result.pv.add(best.pv[i]);

            if (nowMs() >= softStopTimeMs) break;
        }

        return result;
    }

    // Root search in strictly rootMoves order (MultiPV respected), PVS at root, no TT/pruning.
    private int rootNegamax(Board board, int depth, int alpha, int beta) {
        int bestScore = -INFTY;

        // ensure root PV starts clean
        stack[0].pvLength = 0;

        for (int i = currentPvIdx; i < rootMoves.size(); i++) {
            RootMove rm = rootMoves.get(i);
            Move m = rm.move;

            long before = nodes;
            if (!board.doMove(m)) continue;

            int score;
            boolean firstAtRoot = (i == currentPvIdx);
            if (firstAtRoot) {
                score = -negamax(board, depth - 1, 1, -beta, -alpha, true);
            } else {
                score = -negamax(board, depth - 1, 1, -alpha - 1, -alpha, false);
                if (score > alpha && score < beta) {
                    score = -negamax(board, depth - 1, 1, -beta, -alpha, true);
                }
            }
            board.undoMove();

            if (score > bestScore) {
                bestScore = score;

                // stitch PV at root (stack)
                StackEntry cur = stack[0];
                StackEntry child = stack[1];
                cur.pvLength = 0; // clear before writing
                cur.pv[cur.pvLength++] = m;
                for (int k = 0; k < child.pvLength; k++) cur.pv[cur.pvLength + k] = child.pv[k];
                cur.pvLength += child.pvLength;

                if (score > alpha) {
                    alpha = score;

                    // *** only commit to RootMove if we are NOT stopped right now ***
                    if (!stopRequested && nowMs() < hardStopTimeMs) {
                        rm.nodes += (nodes - before);
                        rm.score = score;
                        rm.averageScore = (rm.averageScore == SCORE_NONE) ? score : (rm.averageScore + score) / 2;
                        rm.pvLength = cur.pvLength;
                        for (int k = 0; k < rm.pvLength; k++) rm.pv[k] = cur.pv[k];
                    }
                }
            }

            if (alpha >= beta) break;
            if (stopRequested || nowMs() >= hardStopTimeMs) break; // optional early exit
        }

        return bestScore;
    }

    // Non-root negamax with basic PVS; NO move ordering, NO TT, NO pruning tricks.
    private int negamax(Board board, int depth, int ply, int alpha, int beta, boolean pvNode) {
        if (stopCheck()) return 0;
        nodes++;
        selDepth = Math.max(selDepth, ply);

        // clear PV at entry to avoid stale tails
        StackEntry cur = stack[ply];
        cur.pvLength = 0;

        if (board.isDraw()) return 0;

        boolean inCheck = board.isKingAttacked();
        if (depth <= 0) {
            return quiescence(board, ply, alpha, beta);
        }

        List<Move> moves = MoveGenerator.generateLegalMoves(board);
        if (moves.isEmpty()) {
            return inCheck ? (-MATE_VALUE + ply) : 0;
        }

        int bestScore = -INFTY;
        int idx = 0;

        for (Move m : moves) {
            if (stopCheck()) break;

            if (!board.doMove(m)) { idx++; continue; }

            int score;
            boolean childPv = pvNode && idx == 0;
            if (childPv) {
                score = -negamax(board, depth - 1, ply + 1, -beta, -alpha, true);
            } else {
                score = -negamax(board, depth - 1, ply + 1, -alpha - 1, -alpha, false);
                if (pvNode && score > alpha && score < beta) {
                    score = -negamax(board, depth - 1, ply + 1, -beta, -alpha, true);
                }
            }

            board.undoMove();

            if (score > bestScore) {
                bestScore = score;

                // stitch PV
                StackEntry child = stack[ply + 1];
                cur.pvLength = 0; // rewrite from scratch on improvement
                cur.pv[cur.pvLength++] = m;
                for (int k = 0; k < child.pvLength; k++) cur.pv[cur.pvLength + k] = child.pv[k];
                cur.pvLength += child.pvLength;

                if (score > alpha) alpha = score;
            }

            idx++;
            if (alpha >= beta) break;
        }

        return bestScore;
    }

    private int quiescence(Board board, int ply, int alpha, int beta) {
        if (stopCheck()) return 0;
        nodes++;

        // clear PV at entry to avoid stale tails
        StackEntry cur = stack[ply];
        cur.pvLength = 0;

        boolean inCheck = board.isKingAttacked();

        int standPat;
        if (!inCheck) {
            standPat = evaluate(board);
            if (standPat >= beta) return standPat;
            if (standPat > alpha) alpha = standPat;
        } else {
            standPat = -INFTY;
        }

        List<Move> moves;
        if (inCheck) {
            moves = MoveGenerator.generateLegalMoves(board);
        } else {
            List<Move> caps = MoveGenerator.generatePseudoLegalCaptures(board);
            moves = new ArrayList<>(caps.size());
            for (Move m : caps) {
                if (board.isMoveLegal(m, false)) moves.add(m);
            }
        }

        if (inCheck && moves.isEmpty()) {
            return -MATE_VALUE + ply;
        }

        int bestScore = standPat;

        for (Move m : moves) {
            if (stopCheck()) break;
            if (!board.doMove(m)) continue;

            int score = -quiescence(board, ply + 1, -beta, -alpha);

            board.undoMove();

            if (score > bestScore) {
                bestScore = score;

                // stitch PV
                StackEntry child = stack[ply + 1];
                cur.pvLength = 0; // rewrite from scratch on improvement
                cur.pv[cur.pvLength++] = m;
                for (int k = 0; k < child.pvLength; k++) cur.pv[cur.pvLength + k] = child.pv[k];
                cur.pvLength += child.pvLength;

                if (score > alpha) alpha = score;
            }

            if (alpha >= beta) break;
        }

        return bestScore;
    }

    private int evaluate(Board board) {
        Eval.NNUEState state = new Eval.NNUEState();
        Eval.refreshAccumulator(state, board);
        return Eval.evaluate(state, board);
    }

    private void sortRootMovesFrom(int offset) {
        // selection sort by descending score starting at offset
        for (int i = offset; i < rootMoves.size(); i++) {
            int best = i;
            for (int j = i + 1; j < rootMoves.size(); j++) {
                if (rootMoves.get(j).score > rootMoves.get(best).score) best = j;
            }
            if (best != i) {
                RootMove tmp = rootMoves.get(i);
                rootMoves.set(i, rootMoves.get(best));
                rootMoves.set(best, tmp);
            }
        }
    }

    private boolean stopCheck() {
        if (stopRequested) return true;
        if ((nodes & 2047L) == 0L) {
            if (nowMs() >= hardStopTimeMs) {
                stopRequested = true;
                return true;
            }
        }
        return false;
    }

    private long nowMs() { return System.currentTimeMillis(); }
}
