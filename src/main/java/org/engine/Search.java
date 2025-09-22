package org.engine;

import java.util.ArrayList;
import java.util.List;

public final class Search {

	private final Eval.NNUEState nnueState = new Eval.NNUEState();

	public static final int MAX_PLY = 128;
	private static final int MAX_MOVES = 256;
	private static final int INFTY = 1_000_000;
	private static final int MATE_VALUE = 32000;
	private static final int SCORE_NONE = 123456789; // sentinel

	public static final class Limits {
		public int depth = -1; // fixed depth if > 0, otherwise time-based
		public long softMs = 0L; // preferred stop after finishing an iteration
		public long hardMs = 0L; // absolute cutoff
	}

	public static final class Result {
		public int bestMove;
		public int scoreCp;
		public List<Integer> pv = new ArrayList<>();
	}

	private enum NodeType { rootNode, pvNode, nonPVNode }

	@FunctionalInterface
	public interface InfoHandler {
		void onInfo(int depth, int seldepth, long nodes, long nps, int hashfull,
		            int scoreCp, long timeMs, List<Integer> pv);
	}

	private static final class StackEntry {
		int[] pv;
		int pvLength;
		boolean inCheck;
		int move;
		int excludedMove;
		int searchKiller;
		int staticEval;
		int reduction;

		StackEntry() {
			this.pv = new int[MAX_PLY];
			this.pvLength = 0;
			this.inCheck = false;
			this.move = MoveFactory.MOVE_NONE;
			this.excludedMove = MoveFactory.MOVE_NONE;
			this.searchKiller = MoveFactory.MOVE_NONE;
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
	private final int[][] moveScores = new int[MAX_PLY + 5][MAX_MOVES];
	private final int[][] moveBuffers = new int[MAX_PLY + 5][MAX_MOVES];
	private final MoveGenerator moveGen = new MoveGenerator();
	private final PositionFactory pos = new PositionFactory();

	public void stop() {
		stopRequested = true;
	}

	public Result search(long[] root, Limits limits, InfoHandler infoHandler) {
		stopRequested = false;
		startTimeMs = System.currentTimeMillis();
		nodes = 0L;
		selDepth = 0;
		softStopTimeMs = limits.softMs > 0 ? startTimeMs + limits.softMs : Long.MAX_VALUE;
		hardStopTimeMs = limits.hardMs > 0 ? startTimeMs + limits.hardMs : Long.MAX_VALUE;

		Eval.refreshAccumulator(nnueState, root);

        // Age the TT for this new search
        TranspositionTable.TT.nextSearch();

		stack = new StackEntry[MAX_PLY + 5];
		for (int i = 0; i < stack.length; i++) stack[i] = new StackEntry();

		Result result = new Result();

		int previousBest = MoveFactory.MOVE_NONE;
		int previousScore = 0;
		int maxDepth = limits.depth > 0 ? limits.depth : 64;

		for (int depth = 1; depth <= maxDepth; depth++) {
			if (stopRequested || System.currentTimeMillis() >= hardStopTimeMs) break;

			int score;

			for (int i = 0; i < stack.length; i++) {
				StackEntry e = stack[i];
				e.pvLength = 0;
				e.inCheck = false;
				e.move = MoveFactory.MOVE_NONE;
				e.excludedMove = MoveFactory.MOVE_NONE;
				e.searchKiller = MoveFactory.MOVE_NONE;
				e.staticEval = SCORE_NONE;
				e.reduction = 0;
			}


			final int rootDepth = depth;

			if (depth <= 3) {
				score = negamax(root, depth, 0, -INFTY, INFTY, NodeType.rootNode);
			} else {
				int delta = 12;
				int alpha = Math.max(-INFTY, previousScore - delta);
				int beta  = Math.min( INFTY, previousScore + delta);

				int searchDepth = depth;

				while (true) {
					score = negamax(root, searchDepth, 0, alpha, beta, NodeType.rootNode);
					if (stopRequested || System.currentTimeMillis() >= hardStopTimeMs) break;

					if (score <= alpha) {
						beta  = (alpha + beta) / 2;
						alpha = Math.max(-INFTY, score - delta);
						searchDepth = rootDepth;
					} else if (score >= beta) {
						beta = Math.min( INFTY, score + delta);
						searchDepth = Math.max(searchDepth - 1, 1);
					} else {
						break;
					}

					delta = (int) Math.round(delta * 1.5);
				}
			}

			if (stopRequested || System.currentTimeMillis() >= hardStopTimeMs) break;

			List<Integer> pv = extractPV(0);
			previousBest = pv.isEmpty() ? MoveFactory.MOVE_NONE : pv.get(0);
			previousScore = score;

			result.bestMove = previousBest;
			result.scoreCp = score;
			result.pv = pv;

			long now = System.currentTimeMillis();
			long elapsed = Math.max(1, now - startTimeMs);
			long nps = (nodes * 1000L) / elapsed;
            int hashfull = TranspositionTable.TT.hashfull();
			if (infoHandler != null) {
				infoHandler.onInfo(depth, selDepth, nodes, nps, hashfull, score, elapsed, pv);
			}

			if (now >= softStopTimeMs) break;
		}

		return result;
	}

	private int negamax(long[] board, int depth, int ply, int alpha, int beta, NodeType nodeType) {
		StackEntry se = stack[ply];
		se.pvLength = 0;
		if (stopCheck()) return 0;
		nodes++;
		selDepth = Math.max(selDepth, ply);

		if (pos.isDraw(board)) return 0;

        TranspositionTable.ProbeResult pr = TranspositionTable.TT.probe(pos.zobrist(board));
        TranspositionTable.Entry entry = pr.entry;
        boolean hit = pr.hit;
		int cachedScore = 0;
		int cachedEval = TranspositionTable.SCORE_VOID;
        int cachedDepth = -1;
        int cachedBound = TranspositionTable.BOUND_NONE;
        boolean cachedWasPv = false;
        boolean cutCandidate = (nodeType == NodeType.nonPVNode) && (beta == alpha + 1);
        if (hit) {
			cachedScore = entry.getScore(ply);
            cachedDepth = entry.getDepth();
            cachedBound = entry.getBound();
            cachedEval = entry.getStaticEval();
            cachedWasPv = entry.wasPV();
            if (nodeType == NodeType.nonPVNode && cachedScore != TranspositionTable.SCORE_VOID && cachedDepth >= depth + (cachedScore >= beta ? 1 : 0) && (cutCandidate == (cachedScore >= beta)) && TranspositionTable.boundAllowsThreshold(cachedBound, cachedScore, beta)) {
                return cachedScore;
			}
		}

		boolean inCheck = pos.isInCheck(board);
		se.inCheck = inCheck;
		if (inCheck) {
			se.staticEval = SCORE_NONE;
		}

		if (depth <= 0) {
			nodes--;
			return quiescence(board, ply, alpha, beta, nodeType);
		}

		if (ply + 1 < stack.length) stack[ply + 1].searchKiller = MoveFactory.MOVE_NONE;

        if (!inCheck) {
            int rawEval = (hit && cachedEval != TranspositionTable.SCORE_VOID) ? cachedEval : evaluate(board);
            if (!hit) {
                boolean isPVHere = (nodeType != NodeType.nonPVNode);
                boolean pvBitEval = isPVHere || cachedWasPv;
                entry.store(pos.zobrist(board), TranspositionTable.BOUND_NONE, 0, 0, TranspositionTable.SCORE_VOID, rawEval, pvBitEval, ply);
            }
            se.staticEval = rawEval;
        }

		if (!inCheck && nodeType == NodeType.nonPVNode && depth >= 3) {
			if (pos.hasNonPawnMaterialForSTM(board)) {
				int R = (depth >= 6) ? 3 : 2;
				pos.makeNullMoveInPlace(board);
				int score = -negamax(board, depth - 1 - R, ply + 1, -beta, -beta + 1, NodeType.nonPVNode);
				pos.undoNullMoveInPlace(board);
				if (score >= beta) {
					return score;
				}
			}
		}

        int[] moves = moveBuffers[ply];
        int ttMoveForNode = hit ? MoveFactory.intToMove(entry.getPackedMove()) : MoveFactory.MOVE_NONE;
		int killer = stack[ply].searchKiller;
		MovePicker picker = new MovePicker(board, pos, moveGen, moves, moveScores[ply], ttMoveForNode, killer, /*includeQuiets=*/true);

		boolean movePlayed = false;
		int originalAlpha = alpha;
		int bestScore = -INFTY;
		int i = 0;
		for (int move; !MoveFactory.isNone(move = picker.next()); i++) {
			if (stopCheck()) break;

			Eval.doMoveAccumulator(nnueState, board, move);
			if (!pos.makeMoveInPlace(board, move, moveGen)) { Eval.undoMoveAccumulator(nnueState); continue; }
			movePlayed = true;

			int score;
			boolean parentIsPV = (nodeType != NodeType.nonPVNode);
			boolean childPv = parentIsPV && i == 0;
			if (childPv) {
				score = -negamax(board, depth - 1, ply + 1, -beta, -alpha, NodeType.pvNode);
			} else {
				score = -negamax(board, depth - 1, ply + 1, -alpha - 1, -alpha, NodeType.nonPVNode);
				if (parentIsPV && score > alpha && score < beta) {
					score = -negamax(board, depth - 1, ply + 1, -beta, -alpha, NodeType.pvNode);
				}
			}

			pos.undoMoveInPlace(board);
			Eval.undoMoveAccumulator(nnueState);

			if (score > bestScore) {
				bestScore = score;
				if (score > alpha) {
					alpha = score;
					se.pv[0] = move;
					int childLen = stack[ply + 1].pvLength;
					System.arraycopy(stack[ply + 1].pv, 0, se.pv, 1, childLen);
					se.pvLength = childLen + 1;
				}
			}

			if (alpha >= beta) {
				int flags = MoveFactory.GetFlags(move);
				boolean isCapture;
				if (flags == MoveFactory.FLAG_EN_PASSANT) {
					isCapture = true;
				} else {
					int to = MoveFactory.GetTo(move);
					int targetPiece = PositionFactory.pieceAt(board, to);
					isCapture = targetPiece != -1;
				}
				boolean isPromotion = (flags == MoveFactory.FLAG_PROMOTION);
				boolean isCastle = (flags == MoveFactory.FLAG_CASTLE);
				if (!isCapture && !isPromotion && !isCastle) {
					int m = MoveFactory.intToMove(move);
					if (m != 0) stack[ply].searchKiller = m;
				}
				break;
			}
		}

		if (!movePlayed) return inCheck ? (-MATE_VALUE + ply) : 0;

		int resultBound;
		if (bestScore >= beta) resultBound = TranspositionTable.BOUND_LOWER;
		else resultBound = ((nodeType != NodeType.nonPVNode) && (se.pvLength > 0 && !MoveFactory.isNone(se.pv[0]))) ? TranspositionTable.BOUND_EXACT : TranspositionTable.BOUND_UPPER;

        int bestMove = se.pvLength > 0 ? se.pv[0] : MoveFactory.MOVE_NONE;
        int rawEval = (se.staticEval != SCORE_NONE) ? se.staticEval : 0;
        boolean isPV = (nodeType != NodeType.nonPVNode);
        boolean pvBit = isPV || cachedWasPv;
		entry.store(pos.zobrist(board), resultBound, depth, MoveFactory.intToMove(bestMove), bestScore, rawEval, pvBit, ply);

		return bestScore;
	}

	private int quiescence(long[] board, int ply, int alpha, int beta, NodeType nodeType) {
		StackEntry se = stack[ply];
		se.pvLength = 0;
		if (stopCheck()) return 0;
		nodes++;
		selDepth = Math.max(selDepth, ply);

		if (pos.isDraw(board)) return 0;

        TranspositionTable.ProbeResult pr = TranspositionTable.TT.probe(pos.zobrist(board));
        TranspositionTable.Entry ttEntry = pr.entry;
        boolean ttHit = pr.hit;
        int ttStaticEval = TranspositionTable.SCORE_VOID;
        boolean ttPV = false;
        if (ttHit) {
            int ttBound = ttEntry.getBound();
            int ttScore = ttEntry.getScore(ply);
            ttStaticEval = ttEntry.getStaticEval();
            ttPV = ttEntry.wasPV();
            if (nodeType == NodeType.nonPVNode) {
                if (ttBound == TranspositionTable.BOUND_LOWER && ttScore >= beta) return ttScore;
                if (ttBound == TranspositionTable.BOUND_UPPER && ttScore <= alpha) return ttScore;
                if (ttBound == TranspositionTable.BOUND_EXACT) return ttScore;
            }
        }

		boolean inCheck = pos.isInCheck(board);
		int originalAlpha = alpha;

        int standPat;
        if (!inCheck) {
            int rawEval;
            if (ttStaticEval != TranspositionTable.SCORE_VOID) rawEval = ttStaticEval; else rawEval = evaluate(board);
            standPat = rawEval;

            if (ttHit && ttEntry.getScore(ply) != TranspositionTable.SCORE_VOID && TranspositionTable.boundAllowsThreshold(ttEntry.getBound(), ttEntry.getScore(ply), standPat))
                standPat = ttEntry.getScore(ply);

            if (standPat >= beta) {
                if (!ttHit) {
                    ttEntry.store(pos.zobrist(board), TranspositionTable.BOUND_NONE, 0, 0, TranspositionTable.SCORE_VOID, rawEval, ttPV, ply);
                }
                return (standPat + beta) / 2;
            }
            if (standPat > alpha) alpha = standPat;
        } else {
			standPat = -INFTY;
		}

		// Mate distance pruning
		alpha = Math.max(alpha, -MATE_VALUE + ply);
		beta  = Math.min(beta,  MATE_VALUE - (ply + 1));
		if (alpha >= beta) return alpha;

        int[] moves = moveBuffers[ply];
        int ttMoveForQ = ttHit ? MoveFactory.intToMove(ttEntry.getPackedMove()) : MoveFactory.MOVE_NONE;
		MovePicker picker = new MovePicker(board, pos, moveGen, moves, moveScores[ply], ttMoveForQ, MoveFactory.MOVE_NONE, inCheck);

		boolean movePlayed = false;
        int bestScore = standPat;
		for (int move; !MoveFactory.isNone(move = picker.next()); ) {
			if (stopCheck()) break;

			Eval.doMoveAccumulator(nnueState, board, move);
			if (!pos.makeMoveInPlace(board, move, moveGen)) { Eval.undoMoveAccumulator(nnueState); continue; }
			movePlayed = true;

			int score = -quiescence(board, ply + 1, -beta, -alpha, nodeType);

			pos.undoMoveInPlace(board);
			Eval.undoMoveAccumulator(nnueState);

			if (score > bestScore) {
				bestScore = score;
				if (score > alpha) {
					alpha = score;
					se.pv[0] = move;
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
				bestScore = -MATE_VALUE + ply;
			}
		}

		int bound;
		if (bestScore >= beta) bound = TranspositionTable.BOUND_LOWER;
		else if (alpha != originalAlpha) bound = TranspositionTable.BOUND_EXACT;
		else bound = TranspositionTable.BOUND_UPPER;

        int rawEval = (standPat != -INFTY) ? standPat : 0;
        int bestMove = se.pvLength > 0 ? se.pv[0] : MoveFactory.MOVE_NONE;
        ttEntry.store(pos.zobrist(board), bound, inCheck ? 1 : 0, MoveFactory.intToMove(bestMove), bestScore, rawEval, ttPV, ply);

		return bestScore;
	}

	private int evaluate(long[] board) {
		return Eval.evaluate(nnueState, board);
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

	private List<Integer> extractPV(int ply) {
		StackEntry se = stack[ply];
		List<Integer> pv = new ArrayList<>(se.pvLength);
		for (int i = 0; i < se.pvLength; i++) {
			int m = se.pv[i];
			if (MoveFactory.isNone(m)) break;
			pv.add(m);
		}
		return pv;
	}
}


