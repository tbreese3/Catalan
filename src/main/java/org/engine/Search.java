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
		public Integer bestMove;
		public int scoreCp;
		public List<Integer> pv = new ArrayList<>();
	}

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
			this.move = 0;
			this.excludedMove = 0;
			this.searchKiller = 0;
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
		TranspositionTable.TT.updateTableAge();

		stack = new StackEntry[MAX_PLY + 5];
		for (int i = 0; i < stack.length; i++) stack[i] = new StackEntry();

		Result result = new Result();

		int previousBest = 0;
		int previousScore = 0;
		int maxDepth = limits.depth > 0 ? limits.depth : 64;

		for (int depth = 1; depth <= maxDepth; depth++) {
			if (stopRequested || System.currentTimeMillis() >= hardStopTimeMs) break;

			int score;

			for (int i = 0; i < stack.length; i++) {
				StackEntry e = stack[i];
				e.pvLength = 0;
				e.inCheck = false;
				e.move = 0;
				e.excludedMove = 0;
				e.searchKiller = 0;
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
						break;
					}

					delta = (int) Math.round(delta * 1.5);
				}
			}

			if (stopRequested || System.currentTimeMillis() >= hardStopTimeMs) break;

			List<Integer> pv = extractPV(0);
			previousBest = pv.isEmpty() ? null : pv.get(0);
			previousScore = score;

			result.bestMove = previousBest;
			result.scoreCp = score;
			result.pv = pv;

			long now = System.currentTimeMillis();
			long elapsed = Math.max(1, now - startTimeMs);
			long nps = (nodes * 1000L) / elapsed;
			int hashfull = TranspositionTable.TT.getHashfull();
			if (infoHandler != null) {
				infoHandler.onInfo(depth, selDepth, nodes, nps, hashfull, score, elapsed, pv);
			}

			if (now >= softStopTimeMs) break;
		}

		return result;
	}

	private int negamax(long[] board, int depth, int ply, int alpha, int beta, boolean pvNode) {
		if (stopCheck()) return 0;
		nodes++;
		selDepth = Math.max(selDepth, ply);

		StackEntry se = stack[ply];
		se.pvLength = 0;

		if (pos.isDraw(board)) return 0;

		int bucket = TranspositionTable.TT.probeBucket(pos.zobrist(board));
		int slot = bucket >= 0 ? TranspositionTable.TT.findSlotInBucket(bucket, pos.zobrist(board)) : -1;
		boolean ttHit = slot >= 0;
		if (ttHit) {
			int ttDepth = TranspositionTable.TT.readDepth(bucket, slot);
			int ttBound = TranspositionTable.TT.readBound(bucket, slot);
			int ttScore = TranspositionTable.scoreFromTT(TranspositionTable.TT.readScore(bucket, slot), ply);
			int halfMoves = pos.halfmoveClock(board);
			if (!pvNode && ttDepth >= depth && halfMoves < 90) {
				if ((ttBound == TranspositionTable.BOUND_LOWER && ttScore >= beta)
						|| (ttBound == TranspositionTable.BOUND_UPPER && ttScore <= alpha)
						|| (ttBound == TranspositionTable.BOUND_EXACT)) {
					return ttScore;
				}
			}
		}

		boolean inCheck = pos.isInCheck(board);
		se.inCheck = inCheck;

		if (depth <= 0) {
			return quiescence(board, ply, alpha, beta, pvNode);
		}

		int[] moves = moveBuffers[ply];
		int moveCount = moveGen.generateCaptures(board, moves, 0);
		moveCount = moveGen.generateQuiets(board, moves, moveCount);
		int[] scores = moveScores[ply];
		int ttMoveForNode = ttHit ? (TranspositionTable.TT.readPackedMove(bucket, slot) & 0xFFFF) : 0;
		MoveOrderer.AssignNegaMaxScores(moves, scores, moveCount, ttMoveForNode);

		if (se.staticEval == SCORE_NONE) {
			int rawEval = evaluate(board);
			se.staticEval = rawEval;
			TranspositionTable.TT.store(pos.zobrist(board), (short) 0, TranspositionTable.SCORE_NONE_TT, rawEval, TranspositionTable.BOUND_NONE, 0, pvNode, false);
		}

		boolean movePlayed = false;
		int originalAlpha = alpha;
		int bestScore = -INFTY;
		for (int i = 0; i < moveCount; i++) {
			if (stopCheck()) break;
			int move = MoveOrderer.GetNextMove(moves, scores, moveCount, i);

			Eval.doMoveAccumulator(nnueState, board, move);
			if (!pos.makeMoveInPlace(board, move, moveGen)) { Eval.undoMoveAccumulator(nnueState); continue; }
			movePlayed = true;

			int score;
			boolean childPv = pvNode && i == 0;
			if (childPv) {
				score = -negamax(board, depth - 1, ply + 1, -beta, -alpha, true);
			} else {
				score = -negamax(board, depth - 1, ply + 1, -alpha - 1, -alpha, false);
				if (pvNode && score > alpha && score < beta) {
					score = -negamax(board, depth - 1, ply + 1, -beta, -alpha, true);
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

			if (alpha >= beta) break;
		}

		if (!movePlayed) return inCheck ? (-MATE_VALUE + ply) : 0;

		int bound;
		if (bestScore <= originalAlpha) bound = TranspositionTable.BOUND_UPPER;
		else if (bestScore >= beta) bound = TranspositionTable.BOUND_LOWER;
		else bound = TranspositionTable.BOUND_EXACT;

		int bestMove = se.pvLength > 0 ? se.pv[0] : 0;
		int storeScore = TranspositionTable.scoreToTT(bestScore, ply);
		int rawEval = se.staticEval == SCORE_NONE ? evaluate(board) : se.staticEval;
		boolean prevWasPV = ttHit && TranspositionTable.TT.readWasPV(bucket, slot);
		TranspositionTable.TT.store(pos.zobrist(board), (short) (bestMove & 0xFFFF), storeScore, rawEval, bound, depth, pvNode, pvNode || prevWasPV);

		return bestScore;
	}

	private int quiescence(long[] board, int ply, int alpha, int beta, boolean pvNode) {
		if (stopCheck()) return 0;
		nodes++;

		if (pos.isDraw(board)) return 0;

		int bucket = TranspositionTable.TT.probeBucket(pos.zobrist(board));
		int slot = bucket >= 0 ? TranspositionTable.TT.findSlotInBucket(bucket, pos.zobrist(board)) : -1;
		boolean ttHit = slot >= 0;
		if (ttHit) {
			int ttBound = TranspositionTable.TT.readBound(bucket, slot);
			int ttScore = TranspositionTable.scoreFromTT(TranspositionTable.TT.readScore(bucket, slot), ply);
			if (!pvNode) {
				if (ttBound == TranspositionTable.BOUND_LOWER && ttScore >= beta) return ttScore;
				if (ttBound == TranspositionTable.BOUND_UPPER && ttScore <= alpha) return ttScore;
				if (ttBound == TranspositionTable.BOUND_EXACT) return ttScore;
			}
		}

		boolean inCheck = pos.isInCheck(board);

		int standPat;
		if (!inCheck) {
			standPat = evaluate(board);
			if (standPat >= beta) return standPat;
			if (standPat > alpha) alpha = standPat;
		} else {
			standPat = -INFTY;
		}

		int[] moves = moveBuffers[ply];
		int moveCount;
		if (inCheck) {
			moveCount = moveGen.generateCaptures(board, moves, 0);
			moveCount = moveGen.generateQuiets(board, moves, moveCount);
		} else {
			moveCount = moveGen.generateCaptures(board, moves, 0);
		}
		int[] qScores = moveScores[ply];
		int ttMoveForQ = ttHit ? (TranspositionTable.TT.readPackedMove(bucket, slot) & 0xFFFF) : 0;
		MoveOrderer.AssignQSearchScores(moves, qScores, moveCount, ttMoveForQ);

		StackEntry se = stack[ply];
		se.pvLength = 0;

		boolean movePlayed = false;
		int bestScore = standPat;
		for (int i = 0; i < moveCount; i++) {
			if (stopCheck()) break;
			int move = MoveOrderer.GetNextMove(moves, qScores, moveCount, i);

			Eval.doMoveAccumulator(nnueState, board, move);
			if (!pos.makeMoveInPlace(board, move, moveGen)) { Eval.undoMoveAccumulator(nnueState); continue; }
			movePlayed = true;

			int score = -quiescence(board, ply + 1, -beta, -alpha, pvNode);

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

		if(!movePlayed) {
			if (inCheck) {
				return -MATE_VALUE + ply;
			}
		}

		int bound;
		if (bestScore >= beta) bound = TranspositionTable.BOUND_LOWER; else bound = TranspositionTable.BOUND_UPPER;
		int storeScore = TranspositionTable.scoreToTT(bestScore, ply);
		int rawEval = (standPat != -INFTY) ? standPat : evaluate(board);
		int bestMove = se.pvLength > 0 ? se.pv[0] : 0;
		boolean prevWasPV = ttHit && TranspositionTable.TT.readWasPV(bucket, slot);
		TranspositionTable.TT.store(pos.zobrist(board), (short) (bestMove & 0xFFFF), storeScore, rawEval, bound, 0, pvNode, pvNode || prevWasPV);

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
			if (m == 0) break;
			pv.add(m);
		}
		return pv;
	}
}


