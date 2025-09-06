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
    private static final int SCORE_NONE = 123456789; // sentinel, unused but kept for parity

    public static final class Limits {
        public int depth = -1; // fixed depth if > 0, otherwise time-based
        public long softMs = 0L; // preferred stop after finishing an iteration
        public long hardMs = 0L; // absolute cutoff
    }

    public static final class Result {
        public Move bestMove;
        public int scoreCp;
        public List<Move> pv = new ArrayList<>();
    }

    @FunctionalInterface
    public interface InfoHandler {
        void onInfo(int depth, int seldepth, long nodes, long nps, int hashfull,
                    int scoreCp, long timeMs, List<Move> pv);
    }

    private static final class StackEntry {
        Move[] pv;
        int pvLength;
        boolean inCheck;
        Move move;
        Move excludedMove;
        Move searchKiller;
        int staticEval;
        int reduction;

        StackEntry() {
            this.pv = new Move[MAX_PLY];
            this.pvLength = 0;
            this.inCheck = false;
            this.move = null;
            this.excludedMove = null;
            this.searchKiller = null;
            this.staticEval = SCORE_NONE;
            this.reduction = 0;
        }
    }

    private volatile boolean stopRequested = false;
    private long startTimeMs;
    private long softStopTimeMs;
    private long hardStopTimeMs;
    private long nodes;
    private int selDepth;
    private StackEntry[] stack;

    public void stop() {
        stopRequested = true;
    }

    public Result search(Board root, Limits limits, InfoHandler infoHandler) {
        stopRequested = false;
        startTimeMs = System.currentTimeMillis();
        nodes = 0L;
        selDepth = 0;
        softStopTimeMs = limits.softMs > 0 ? startTimeMs + limits.softMs : Long.MAX_VALUE;
        hardStopTimeMs = limits.hardMs > 0 ? startTimeMs + limits.hardMs : Long.MAX_VALUE;

        stack = new StackEntry[MAX_PLY + 5];
        for (int i = 0; i < stack.length; i++) stack[i] = new StackEntry();

        Result result = new Result();
        List<Move> rootMoves = MoveGenerator.generateLegalMoves(root);
        if (rootMoves.isEmpty()) {
            result.bestMove = null;
            result.scoreCp = root.isMated() ? -MATE_VALUE : 0;
            result.pv = new ArrayList<>();
            return result;
        }

        Move previousBest = null;
        int previousScore = 0;
        int maxDepth = limits.depth > 0 ? limits.depth : 64;

        for (int depth = 1; depth <= maxDepth; depth++) {
            if (stopRequested || System.currentTimeMillis() >= hardStopTimeMs) break;

            int score;

            for (int i = 0; i < stack.length; i++) {
                StackEntry e = stack[i];
                e.pvLength = 0;
                e.inCheck = false;
                e.move = null;
                e.excludedMove = null;
                e.searchKiller = null;
                e.staticEval = SCORE_NONE;
                e.reduction = 0;
            }

            final int rootDepth = depth;

            if (depth <= 3) {
                score = negamax(root, depth, 0, -INFTY, INFTY, true);
            } else {
                int delta = 12;
                int alpha = Math.max(-INFTY, previousScore - delta);
                int beta  = Math.min( INFTY, previousScore + delta);

                int searchDepth = depth;

                while (true) {
                    score = negamax(root, searchDepth, 0, alpha, beta, true);
                    if (stopRequested || System.currentTimeMillis() >= hardStopTimeMs) break;

                    if (score <= alpha) {
                        beta  = (alpha + beta) / 2;
                        alpha = Math.max(-INFTY, score - delta);
                        searchDepth = rootDepth;
                    } else if (score >= beta) {
                        beta = Math.min( INFTY, score + delta);
                        searchDepth = Math.max(searchDepth - 1, 1);
                    } else {
                        // inside window
                        break;
                    }

                    delta = (int) Math.round(delta * 1.5);
                }
            }

            if (stopRequested || System.currentTimeMillis() >= hardStopTimeMs) break;

            List<Move> pv = extractPV(0);
            previousBest = pv.isEmpty() ? rootMoves.get(0) : pv.get(0);
            previousScore = score;

            result.bestMove = previousBest;
            result.scoreCp = score;
            result.pv = pv;

            long now = System.currentTimeMillis();
            long elapsed = Math.max(1, now - startTimeMs);
            long nps = (nodes * 1000L) / elapsed;
            if (infoHandler != null) {
                infoHandler.onInfo(depth, selDepth, nodes, nps, 0, score, elapsed, pv);
            }

            if (now >= softStopTimeMs) break;
        }

        return result;
    }

    private int negamax(Board board, int depth, int ply, int alpha, int beta, boolean pvNode) {
        if (stopCheck()) return 0;
        nodes++;
        selDepth = Math.max(selDepth, ply);

        StackEntry se = stack[ply];
        se.pvLength = 0;

        if (board.isDraw()) {
            return 0;
        }

        boolean inCheck = board.isKingAttacked();
        se.inCheck = inCheck;

        if (depth <= 0) {
            return quiescence(board, ply, alpha, beta, pvNode);
        }

        List<Move> moves = MoveGenerator.generateLegalMoves(board);

        boolean movePlayed = false;
        int bestScore = -INFTY;
        for (int i = 0; i < moves.size(); i++) {
            if (stopCheck()) break;
            Move m = moves.get(i);
            if (!board.doMove(m)) continue;
            movePlayed = true;
            int score;
            boolean childPv = pvNode && i == 0;
            if (childPv) {
                // First move at PV node: full window
                score = -negamax(board, depth - 1, ply + 1, -beta, -alpha, true);
            } else {
                // PVS: zero-window search
                score = -negamax(board, depth - 1, ply + 1, -alpha - 1, -alpha, false);
                if (pvNode && score > alpha && score < beta) {
                    // Re-search with full window
                    score = -negamax(board, depth - 1, ply + 1, -beta, -alpha, true);
                }
            }
            board.undoMove();

            if (score > bestScore) {
                bestScore = score;
                if (score > alpha) {
                    alpha = score;
                    se.pv[0] = m;
                    int childLen = stack[ply + 1].pvLength;
                    System.arraycopy(stack[ply + 1].pv, 0, se.pv, 1, childLen);
                    se.pvLength = childLen + 1;
                }
            }

            if (alpha >= beta) {
                break;
            }
        }

        if (!movePlayed) {
            if (inCheck) {
                return -MATE_VALUE + ply;
            }
            return 0;
        }

        return bestScore;
    }

    private int quiescence(Board board, int ply, int alpha, int beta, boolean pvNode) {
        if (stopCheck()) return 0;
        nodes++;

        if (board.isRepetition() || board.getHalfMoveCounter() >= 100)
        {
            return 0;
        }
        
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

        StackEntry se = stack[ply];
        se.pvLength = 0;

        boolean movePlayed = false;
        int bestScore = standPat;
        for (int i = 0; i < moves.size(); i++) {
            if (stopCheck()) break;
            Move m = moves.get(i);
            if (!board.doMove(m)) continue;
            movePlayed = true;
            int score = -quiescence(board, ply + 1, -beta, -alpha, pvNode);
            board.undoMove();

            if (score > bestScore) {
                bestScore = score;
                if (score > alpha) {
                    alpha = score;
                    se.pv[0] = m;
                    int childLen = stack[ply + 1].pvLength;
                    System.arraycopy(stack[ply + 1].pv, 0, se.pv, 1, childLen);
                    se.pvLength = childLen + 1;
                }
            }

            if (alpha >= beta) {
                break;
            }
        }

        if(!movePlayed)
        {
            if (inCheck) {
                return -MATE_VALUE + ply;
            }
        }

        return bestScore;
    }

    private int evaluate(Board board) {
        Eval.NNUEState state = new Eval.NNUEState();
        Eval.refreshAccumulator(state, board);
        return Eval.evaluate(state, board);
    }

    private boolean stopCheck() {
        if (stopRequested) return true;
        if ((nodes & 2047L) == 0L) {
            long now = System.currentTimeMillis();
            if (now >= hardStopTimeMs) {
                stopRequested = true;
                return true;
            }
        }
        return false;
    }

    private int indexOfMove(List<Move> moves, Move target) {
        for (int i = 0; i < moves.size(); i++) {
            if (equalsMove(moves.get(i), target)) return i;
        }
        return -1;
    }

    private boolean equalsMove(Move a, Move b) {
        if (a == null || b == null) return false;
        return a.getFrom() == b.getFrom() && a.getTo() == b.getTo() && a.getPromotion() == b.getPromotion();
    }

    private List<Move> extractPV(int ply) {
        StackEntry se = stack[ply];
        List<Move> pv = new ArrayList<>(se.pvLength);
        for (int i = 0; i < se.pvLength; i++) {
            Move m = se.pv[i];
            if (m == null) break;
            pv.add(m);
        }
        return pv;
    }
}


