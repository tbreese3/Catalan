package org.core;

import org.chesslib.Board;
import org.chesslib.Piece;
import org.chesslib.PieceType;
import org.chesslib.move.Move;
import org.chesslib.move.MoveGenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Barebones negamax alpha-beta search with:
 * - Iterative deepening
 * - Aspiration windows
 * - Captures-only quiescence (all moves if in check)
 * - PV management
 *
 * No pruning techniques (no LMR, NMP, futility, SEE filters, etc.).
 */
public final class Search {

    private static final int SsOffset = 6;

    public static final class Limits {
        public int depth = -1;          // Fixed depth if > 0, otherwise time-based
        public long softMs = 0L;        // Preferred max time for completing current iteration
        public long hardMs = 0L;        // Hard cutoff time (absolute)
        public long nodes = Long.MAX_VALUE; // Optional node limit (unused by UCI)
    }

    public static final class Result {
        public Move bestMove;
        public int scoreCp;
        public Move[] pv;
    }

    public interface InfoListener {
        void onInfo(int depth, int seldepth, long nodes, long nps, int hashfull, int scoreCp, long timeMs, Move[] pv);
    }

    private static final int MAX_PLY = 128;
    private static final int INFINITE = 1_000_000;
    private static final int MATE_SCORE = 32000; // Used by UCI output
    private static final int ASPIRATION_DELTA_START = 16;

    private volatile boolean stop;

    private long nodes;
    private long startTimeMs;
    private int selDepth;

    private static final class SearchInfo {
        int staticEval;
        Move playedMove;
        boolean playedCap;
        Move killerMove;
        final Move[] pv = new Move[MAX_PLY];
        int pvLength;
        int seenMoves;
    }

    private final SearchInfo[] ss = new SearchInfo[MAX_PLY + SsOffset];

    private final int[] pieceValue = new int[Piece.values().length];

    public Search() {
        // Simple MVV values for ordering (only used for a light root ordering)
        for (Piece p : Piece.values()) {
            if (p == null || p.getPieceType() == null) {
                pieceValue[p != null ? p.ordinal() : 0] = 0;
                continue;
            }
            int v = switch (p.getPieceType()) {
                case PAWN -> 100;
                case KNIGHT -> 320;
                case BISHOP -> 330;
                case ROOK -> 500;
                case QUEEN -> 900;
                case KING -> 20000;
                default -> 0;
            };
            pieceValue[p.ordinal()] = v;
        }
        for (int i = 0; i < ss.length; i++) ss[i] = new SearchInfo();
    }

    public void stop() {
        stop = true;
    }

    public Result search(Board rootBoard, Limits limits, InfoListener info) {
        stop = false;
        nodes = 0;
        selDepth = 0;
        startTimeMs = System.currentTimeMillis();

        // Work on a clone to avoid mutating the caller's board state
        Board board = rootBoard.clone();

        // NNUE state per search; we refresh fully for simplicity (no incremental updates here)
        Eval.NNUEState nnueState = new Eval.NNUEState();
        Eval.refreshAccumulator(nnueState, board);

        Result out = new Result();
        out.bestMove = null;
        out.scoreCp = 0;
        out.pv = new Move[0];

        int targetDepth = limits.depth > 0 ? limits.depth : MAX_PLY - 2;
        int lastScore = 0;

        for (int depth = 1; depth <= targetDepth; depth++) {
            if (shouldSoftStop(limits)) break;

            // Aspiration window
            int delta = depth >= 3 ? ASPIRATION_DELTA_START : INFINITE;
            int alpha = -INFINITE;
            int beta = INFINITE;
            if (depth >= 3) {
                alpha = Math.max(-INFINITE, lastScore - delta);
                beta = Math.min(INFINITE, lastScore + delta);
            }

            int score;
            while (true) {
                resetPvStack();
                score = negamax(board, nnueState, depth, 0, alpha, beta, true, SsOffset);

                if (stop || reachedHardStop(limits)) break;

                if (score <= alpha) {
                    // Fail-low: widen window downwards
                    delta *= 2;
                    alpha = Math.max(-INFINITE, score - delta);
                } else if (score >= beta) {
                    // Fail-high: widen window upwards
                    delta *= 2;
                    beta = Math.min(INFINITE, score + delta);
                } else {
                    break; // within window
                }
            }

            if (stop) break;

            lastScore = score;

            // Extract PV from root stack entry
            Move[] pv = extractPvFromStack();
            out.pv = pv;
            out.scoreCp = score;
            out.bestMove = pv.length > 0 ? pv[0] : pickFallbackMove(board);

            // Info callback
            long timeMs = Math.max(1L, System.currentTimeMillis() - startTimeMs);
            long nps = (nodes * 1000L) / timeMs;
            int hashfull = 0; // no TT in barebones base
            if (info != null) info.onInfo(depth, selDepth, nodes, nps, hashfull, score, timeMs, pv);

            if (reachedHardStop(limits)) break;
        }

        if (out.bestMove == null) {
            out.bestMove = pickFallbackMove(board);
        }
        return out;
    }

    private Move pickFallbackMove(Board board) {
        try {
            List<Move> moves = MoveGenerator.generateLegalMoves(board);
            if (moves.isEmpty()) return null;
            return sortRootMoves(board, moves, null).get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean shouldSoftStop(Limits limits) {
        if (stop) return true;
        if (limits.softMs > 0L) {
            long elapsed = System.currentTimeMillis() - startTimeMs;
            return elapsed >= limits.softMs;
        }
        return false;
    }

    private boolean reachedHardStop(Limits limits) {
        if (stop) return true;
        if (limits.hardMs > 0L) {
            long elapsed = System.currentTimeMillis() - startTimeMs;
            if (elapsed >= limits.hardMs) return true;
        }
        return nodes >= limits.nodes;
    }

    private void resetPvStack() {
        for (int i = 0; i < ss.length; i++) {
            ss[i].pvLength = 0;
            ss[i].playedMove = null;
            ss[i].playedCap = false;
            ss[i].killerMove = null;
            ss[i].seenMoves = 0;
            for (int j = 0; j < MAX_PLY; j++) ss[i].pv[j] = null;
        }
    }

    private Move[] extractPvFromStack() {
        SearchInfo root = ss[SsOffset];
        int len = Math.max(0, root.pvLength);
        List<Move> list = new ArrayList<>();
        for (int i = 0; i < len; i++) {
            Move m = root.pv[i];
            if (m == null) break;
            list.add(m);
        }
        return list.toArray(new Move[0]);
    }

    private void updatePV(int ply, int ssIdx, Move move) {
        SearchInfo cur = ss[ssIdx];
        SearchInfo child = ss[ssIdx + 1];
        cur.pv[0] = move;
        for (int i = 0; i < child.pvLength; i++) {
            cur.pv[i + 1] = child.pv[i];
        }
        cur.pvLength = child.pvLength + 1;
    }

    private int negamax(Board board, Eval.NNUEState nnueState, int depth, int ply, int alpha, int beta, boolean isPV, int ssIdx) {
        if (stop) return 0;
        if (reachedHardStop(new Limits())) return 0; // Defensive; actual hard stop checked by caller

        selDepth = Math.max(selDepth, ply);
        nodes++;

        // Draw detection
        if (board.isDraw()) return 0;

        if (depth == 0) {
            return qsearch(board, nnueState, ply, alpha, beta, isPV, ssIdx);
        }

        // Initialize PV length at PV nodes
        if (isPV) ss[ssIdx].pvLength = ply;

        // Generate legal moves
        List<Move> moves;
        try {
            moves = MoveGenerator.generateLegalMoves(board);
        } catch (Exception e) {
            return 0;
        }

        if (moves.isEmpty()) {
            // Checkmate or stalemate
            if (board.isKingAttacked()) {
                return -MATE_SCORE + ply; // Mate distance scoring
            }
            return 0; // stalemate
        }

        // Light move ordering: PV move first, then captures by MVV-LVA, then others
        Move pvHint = (isPV && ss[ssIdx].pv[ply] != null) ? ss[ssIdx].pv[ply] : null;
        moves = sortRootMoves(board, moves, pvHint);

        int bestScore = -INFINITE;
        Move bestMove = null;

        for (int i = 0; i < moves.size(); i++) {
            Move m = moves.get(i);
            if (!board.doMove(m)) continue;

            int score = -negamax(board, nnueState, depth - 1, ply + 1, -beta, -alpha, isPV, ssIdx + 1);

            board.undoMove();

            if (stop) return 0;

            if (score > bestScore) {
                bestScore = score;
                bestMove = m;

                if (isPV) {
                    updatePV(ply, ssIdx, m);
                }

                if (bestScore > alpha) {
                    alpha = bestScore;
                    if (alpha >= beta) {
                        break; // alpha-beta cutoff
                    }
                }
            }
        }

        return bestScore;
    }

    private int qsearch(Board board, Eval.NNUEState nnueState, int ply, int alpha, int beta, boolean isPV, int ssIdx) {
        if (stop) return 0;
        selDepth = Math.max(selDepth, ply);
        nodes++;

        // Stand pat
        Eval.refreshAccumulator(nnueState, board);
        int standPat = Eval.evaluate(nnueState, board);
        if (standPat >= beta) return standPat;
        if (standPat > alpha) alpha = standPat;

        // If in check, search all legal replies; else only captures
        boolean inCheck = board.isKingAttacked();
        List<Move> moves;
        try {
            if (inCheck) moves = MoveGenerator.generateLegalMoves(board);
            else moves = board.pseudoLegalCaptures();
        } catch (Exception e) {
            moves = Collections.emptyList();
        }

        // Simple capture ordering by MVV-LVA
        if (!inCheck) {
            moves.sort(Comparator.comparingInt((Move m) -> -captureScore(board, m)));
        }

        for (Move m : moves) {
            if (!board.doMove(m)) continue; // filter illegal captures
            int score = -qsearch(board, nnueState, ply + 1, -beta, -alpha, isPV, ssIdx + 1);
            board.undoMove();

            if (score >= beta) return score;
            if (score > alpha) alpha = score;
        }

        return alpha;
    }

    private int captureScore(Board board, Move m) {
        Piece captured = board.getPiece(m.getTo());
        Piece mover = board.getPiece(m.getFrom());
        return pieceValue[captured.ordinal()] * 16 - pieceValue[mover.ordinal()];
    }

    private List<Move> sortRootMoves(Board board, List<Move> moves, Move pvHint) {
        List<Move> list = new ArrayList<>(moves);
        // Move pv hint to front if present
        if (pvHint != null) {
            int idx = list.indexOf(pvHint);
            if (idx > 0) Collections.swap(list, 0, idx);
        }
        // Stable sort captures after pv
        list.subList(pvHint != null ? 1 : 0, list.size())
            .sort(Comparator.comparingInt((Move m) -> -captureScore(board, m)));
        return list;
    }
}


