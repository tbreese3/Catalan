package org.engine;

import java.util.ArrayList;
import java.util.List;

public final class Search {

	private final Eval.NNUEState nnueState = new Eval.NNUEState();

	public static final int MAX_PLY = 256;
	private static final int MAX_MOVES = 256;
	private static final int INFTY = 1_000_000;
	private static final int MATE_VALUE = 32000;
	private static final int SCORE_NONE = 123456789;

	public static final class Limits {
		public int depth = -1;
		public long softMs = 0L;
		public long hardMs = 0L;
	}

	public static final class Result {
		public int bestMove;
		public int scoreCp;
		public List<Integer> pv = new ArrayList<>();
	}

	private enum NodeType { rootNode, pvNode, nonPVNode }

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

	@FunctionalInterface
	public interface InfoHandler {
		void onInfo(int depth, int seldepth, long nodes, long nps, int hashfull, int scoreCp, long timeMs, List<Integer> pv);
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

	private static final int HISTORY_SIZE = 2 * 64 * 64;
	private static final int HISTORY_DECAY_SHIFT = 8;
	private final int[] history = new int[HISTORY_SIZE];

	private static final int LMR_MAX_DEPTH = 64;
	private static final int LMR_MAX_MOVES = 64;
	private final int[][] lmrTable = new int[LMR_MAX_DEPTH + 1][LMR_MAX_MOVES + 1];

	private final int lmpMaxDepth;
	private final int lmpBaseThreshold;
	private final int lmpPerDepth;
	private final int lmpMarginPerDepth;

	private final int iirMinPVDepth;
	private final int iirMinCutDepth;

	private final double lmrBase;
	private final double lmrDivisor;
	private final int reverseFutilityMaxDepth;
	private final int reverseFutilityMarginPerDepth;
	private final int qsSeeMargin;
	private final int nmpBase;
	private final double nmpDepthScale;
	private final int nmpEvalMargin;
	private final int nmpEvalMax;

	public Search(SPSA spsa) {
		if (spsa == null) spsa = new SPSA();
		this.lmrBase = spsa.lmrBase;
		this.lmrDivisor = spsa.lmrDivisor;
		this.reverseFutilityMaxDepth = Math.max(0, spsa.reverseFutilityMaxDepth);
		this.reverseFutilityMarginPerDepth = Math.max(0, spsa.reverseFutilityMarginPerDepth);
		this.qsSeeMargin = spsa.qseeMargin;
		this.nmpBase = Math.max(0, spsa.nmpBase);
		this.nmpDepthScale = Math.max(0.0, spsa.nmpDepthScale);
		this.nmpEvalMargin = Math.max(1, spsa.nmpEvalMargin);
		this.nmpEvalMax = Math.max(0, spsa.nmpEvalMax);
		this.lmpMaxDepth = Math.max(0, spsa.lmpMaxDepth);
		this.lmpBaseThreshold = Math.max(0, spsa.lmpBaseThreshold);
		this.lmpPerDepth = Math.max(0, spsa.lmpPerDepth);
		this.lmpMarginPerDepth = Math.max(0, spsa.lmpMarginPerDepth);
		this.iirMinPVDepth = Math.max(0, spsa.iirMinPVDepth);
		this.iirMinCutDepth = Math.max(0, spsa.iirMinCutDepth);
		buildLmrTable();
	}

	private void buildLmrTable() {
		for (int d = 1; d <= LMR_MAX_DEPTH; d++) {
			for (int m = 1; m <= LMR_MAX_MOVES; m++) {
				double r = lmrBase + Math.log(d) * Math.log(m) / lmrDivisor;
				int ir = (int) r;
				if (ir < 0) ir = 0;
				lmrTable[d][m] = ir;
			}
		}
	}


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
		clearHistory();

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

					delta += (delta + 1) >> 1;
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
        boolean tableHit = pr.hit;
		int tableScore = 0;
		int tableEval = TranspositionTable.SCORE_VOID;
        int tableDepth = -1;
        int tableBound = TranspositionTable.BOUND_NONE;
        boolean tableWasPv = false;
        if (tableHit) {
			tableScore = entry.getScore(ply);
            tableDepth = entry.getDepth();
            tableBound = entry.getBound();
            tableEval = entry.getStaticEval();
            tableWasPv = entry.wasPV();
            if (nodeType == NodeType.nonPVNode && tableScore != TranspositionTable.SCORE_VOID && tableDepth >= depth) {
                boolean boundAllows = (tableScore >= beta)
                        ? ((tableBound & TranspositionTable.BOUND_LOWER) != 0)
                        : ((tableBound & TranspositionTable.BOUND_UPPER) != 0);
                if (boundAllows) return tableScore;
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
            int rawEval = (tableHit && tableEval != TranspositionTable.SCORE_VOID) ? tableEval : evaluate(board);
            if (!tableHit) {
                boolean isPVHere = (nodeType != NodeType.nonPVNode);
                boolean pvBitEval = isPVHere || tableWasPv;
                entry.store(pos.zobrist(board), TranspositionTable.BOUND_NONE, 0, 0, TranspositionTable.SCORE_VOID, rawEval, pvBitEval, ply);
            }
            se.staticEval = rawEval;
        }

		if (!inCheck && nodeType == NodeType.nonPVNode && depth <= reverseFutilityMaxDepth) {
			if (pos.hasNonPawnMaterialForSTM(board)) {
				int eval = se.staticEval;
				int margin = reverseFutilityMarginPerDepth * depth;
				if (Math.abs(beta) < MATE_VALUE && eval - margin >= beta) {
					return eval - margin;
				}
			}
		}

		if (!inCheck && nodeType == NodeType.nonPVNode && depth >= 3) {
			if (pos.hasNonPawnMaterialForSTM(board)) {
				int evalBonus = 0;
				if (Math.abs(beta) < MATE_VALUE) {
					int diff = se.staticEval - beta;
					if (diff > 0) {
						evalBonus = Math.min(nmpEvalMax, diff / Math.max(1, nmpEvalMargin));
					}
				}
				int depthBonus = (int) Math.floor(depth * nmpDepthScale);
				int R = Math.max(1, nmpBase + depthBonus + evalBonus);
				pos.makeNullMoveInPlace(board);
				int score = -negamax(board, depth - 1 - R, ply + 1, -beta, -beta + 1, NodeType.nonPVNode);
				pos.undoNullMoveInPlace(board);
				if (score >= beta) {
					return score;
				}
			}
		}

		if (!inCheck && nodeType != NodeType.rootNode) {
			boolean isPVNode = (nodeType != NodeType.nonPVNode);
			boolean cutNode = (!isPVNode) && (beta == alpha + 1);
			int ttPackedMove = tableHit ? (entry.getPackedMove() & 0xFFFF) : 0;
			boolean hasHashMove = tableHit && ttPackedMove != 0;
			int pvThreshold = Math.max(0, iirMinPVDepth);
			int cutThreshold = Math.max(0, iirMinCutDepth);
			if (!hasHashMove && ((isPVNode && depth >= pvThreshold) || (cutNode && depth >= cutThreshold))) {
				depth--;
			}
		}

		int[] moves = moveBuffers[ply];
		int ttMoveForNode = tableHit ? MoveFactory.intToMove(entry.getPackedMove()) : MoveFactory.MOVE_NONE;
		int killer = stack[ply].searchKiller;
		MovePicker picker = new MovePicker(board, pos, moveGen, history, moves, moveScores[ply], ttMoveForNode, killer, /*includeQuiets=*/true);

		boolean movePlayed = false;
		int originalAlpha = alpha;
		int bestScore = -INFTY;
		int i = 0;
		int quietsTried = 0;
		for (int move; !MoveFactory.isNone(move = picker.next()); i++) {
			if (stopCheck()) break;

			boolean isQuiet = PositionFactory.isQuiet(board, move);
			if (nodeType == NodeType.nonPVNode && !se.inCheck && isQuiet && depth <= lmpMaxDepth && move != ttMoveForNode && move != killer) {
				int threshold = lmpBaseThreshold + lmpPerDepth * depth;
				int eval = se.staticEval;
				if (eval != SCORE_NONE) {
					int margin = lmpMarginPerDepth * depth;
					if (quietsTried >= threshold && eval + margin <= alpha) {
						quietsTried++;
						continue;
					}
				}
			}

			int searchDepthChild = depth - 1;
			int appliedReduction = 0;
			boolean parentIsPV = (nodeType != NodeType.nonPVNode);
			boolean childPv = parentIsPV && i == 0;
			if (!se.inCheck && !childPv && isQuiet && depth >= 3 && i >= 1 && move != ttMoveForNode && move != killer) {
				int dIdx = Math.min(depth, LMR_MAX_DEPTH);
				int mIdx = Math.min(i + 1, LMR_MAX_MOVES);
				int r = lmrTable[dIdx][mIdx];

				boolean whiteSTM = PositionFactory.whiteToMove(board);
				int hVal = historyScore(whiteSTM, move);
				if (hVal > 4096) r = Math.max(0, r - 2);
				else if (hVal > 1024) r = Math.max(0, r - 1);

				if (hVal < -4096) r += 1;
				if (r > 0) {
					appliedReduction = Math.min(r, depth - 1);
					searchDepthChild = Math.max(1, depth - 1 - appliedReduction);
					se.reduction = appliedReduction;
				}
			}

			Eval.doMoveAccumulator(nnueState, board, move);
			if (!pos.makeMoveInPlace(board, move, moveGen)) { Eval.undoMoveAccumulator(nnueState); continue; }
			movePlayed = true;

			int score;
			if (childPv) {
				score = -negamax(board, searchDepthChild, ply + 1, -beta, -alpha, NodeType.pvNode);
			} else {
				score = -negamax(board, searchDepthChild, ply + 1, -alpha - 1, -alpha, NodeType.nonPVNode);

				if (appliedReduction > 0 && score > alpha) {
					score = -negamax(board, depth - 1, ply + 1, -alpha - 1, -alpha, NodeType.nonPVNode);
				}
				
				if (parentIsPV && score > alpha && score < beta) {
					score = -negamax(board, depth - 1, ply + 1, -beta, -alpha, NodeType.pvNode);
				}
			}

			pos.undoMoveInPlace(board);
			Eval.undoMoveAccumulator(nnueState);

			if (isQuiet) quietsTried++;
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
				if (isQuiet) {
					int m = MoveFactory.intToMove(move);
					if (m != 0) stack[ply].searchKiller = m;

					boolean white = PositionFactory.whiteToMove(board);
					onQuietFailHigh(white, move, Math.max(1, depth));
				}
				break;
			}
		}

		if (!movePlayed) return inCheck ? (-MATE_VALUE + ply) : 0;

        int resultBound = bestScore >= beta ? TranspositionTable.BOUND_LOWER : (bestScore > originalAlpha ? TranspositionTable.BOUND_EXACT : TranspositionTable.BOUND_UPPER);

        int bestMove = se.pvLength > 0 ? se.pv[0] : MoveFactory.MOVE_NONE;
        int rawEval = (se.staticEval != SCORE_NONE) ? se.staticEval : 0;
        boolean isPV = (nodeType != NodeType.nonPVNode);
        boolean pvBit = isPV || tableWasPv;
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
            if (nodeType == NodeType.nonPVNode && ttScore != TranspositionTable.SCORE_VOID) {
                boolean boundAllows = (ttScore >= beta)
                        ? ((ttBound & TranspositionTable.BOUND_LOWER) != 0)
                        : ((ttBound & TranspositionTable.BOUND_UPPER) != 0);
                if (boundAllows) return ttScore;
            }
        }

		boolean inCheck = pos.isInCheck(board);
		int originalAlpha = alpha;

        int standPat;
        if (!inCheck) {
            int rawEval;
            if (ttStaticEval != TranspositionTable.SCORE_VOID) rawEval = ttStaticEval; else rawEval = evaluate(board);
            standPat = rawEval;

            if (ttHit) {
                int qttScore = ttEntry.getScore(ply);
                int qttBound = ttEntry.getBound();
                if (qttScore != TranspositionTable.SCORE_VOID) {
                    if (qttBound == TranspositionTable.BOUND_EXACT
                            || (qttBound == TranspositionTable.BOUND_LOWER && qttScore > standPat)
                            || (qttBound == TranspositionTable.BOUND_UPPER && qttScore < standPat)) {
                        standPat = qttScore;
                    }
                }
            }

            if (standPat >= beta) {
                if (!ttHit) {
                    boolean pvHere = (nodeType != NodeType.nonPVNode) || ttPV;
                    ttEntry.store(pos.zobrist(board), TranspositionTable.BOUND_LOWER, 0, 0, standPat, rawEval, pvHere, ply);
                }
                if (Math.abs(standPat) < MATE_VALUE && Math.abs(beta) < MATE_VALUE)
                    return (standPat + beta) / 2;
                return standPat;
            }
            if (standPat > alpha) alpha = standPat;
        } else {
			standPat = -INFTY;
		}

		alpha = Math.max(alpha, -MATE_VALUE + ply);
		beta  = Math.min(beta,  MATE_VALUE - (ply + 1));
		if (alpha >= beta) return alpha;

        int[] moves = moveBuffers[ply];
        int ttMoveForQ = ttHit ? MoveFactory.intToMove(ttEntry.getPackedMove()) : MoveFactory.MOVE_NONE;
        MovePicker picker = new MovePicker(board, pos, moveGen, history, moves, moveScores[ply], ttMoveForQ, MoveFactory.MOVE_NONE, inCheck);

		boolean movePlayed = false;
        int bestScore = standPat;
        for (int move; !MoveFactory.isNone(move = picker.next()); ) {
			if (stopCheck()) break;

			if (!inCheck) {
				if (SEE.see(board, move) < qsSeeMargin) {
					continue;
				}
			}

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
        int storeBound = (bestScore >= beta) ? TranspositionTable.BOUND_LOWER : TranspositionTable.BOUND_UPPER;
        ttEntry.store(pos.zobrist(board), storeBound, inCheck ? 1 : 0, MoveFactory.intToMove(bestMove), bestScore, rawEval, ttPV, ply);

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

	private static int historyIndex(boolean white, int move) {
		int from = MoveFactory.GetFrom(move);
		int to = MoveFactory.GetTo(move);
		int side = white ? 0 : 1;
		return (side << 12) | (from << 6) | to;
	}

	private void clearHistory() {
		for (int i = 0; i < history.length; i++) history[i] = 0;
	}

	private int historyScore(boolean white, int move) {
		return history[historyIndex(white, move)];
	}

	private void onQuietFailHigh(boolean white, int move, int depth) {
		int idx = historyIndex(white, move);
		int bonus = depth * depth;
		int current = history[idx];
		current -= (current >> HISTORY_DECAY_SHIFT);
		current += bonus;
		history[idx] = current;
	}
}


