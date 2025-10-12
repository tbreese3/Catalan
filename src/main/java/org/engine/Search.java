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
    // Replaced by tunables in SPSA

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
	private static final int HISTORY_MAX = 16384;
	private final int[] history = new int[HISTORY_SIZE];
	private final int[] counterMoves = new int[HISTORY_SIZE];

	private static final int LMR_MAX_DEPTH = 64;
	private static final int LMR_MAX_MOVES = 64;
	private final int[][] lmrTable = new int[LMR_MAX_DEPTH + 1][LMR_MAX_MOVES + 1];
	private final int[][] quietBuffers = new int[MAX_PLY + 5][MAX_MOVES];

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
	private final int futilityMaxDepth;
	private final int futilityMarginPerDepth;
	private final int qsSeeMargin;
	private final int nmpBase;
	private final double nmpDepthScale;
	private final int nmpEvalMargin;
	private final int nmpEvalMax;
	private final int razorMaxDepth;
	private final int razorMarginPerDepth;
	private final int qfutMargin;
    private final int singularMinDepth;
	private final int singularMarginPerDepth;

	private final int tmHeuristicsMinDepth;
	private final double tmMaxExtensionFactor;
	private final double tmInstabilityScoreWeight;
	private final List<Integer> iterationScores = new ArrayList<>();
	private int completedDepth = 0;
	private int lastScore = 0;

	public Search(SPSA spsa) {
		if (spsa == null) spsa = new SPSA();
		this.lmrBase = spsa.lmrBase;
		this.lmrDivisor = spsa.lmrDivisor;
		this.reverseFutilityMaxDepth = Math.max(0, spsa.reverseFutilityMaxDepth);
		this.reverseFutilityMarginPerDepth = Math.max(0, spsa.reverseFutilityMarginPerDepth);
		this.futilityMaxDepth = Math.max(0, spsa.futilityMaxDepth);
		this.futilityMarginPerDepth = Math.max(0, spsa.futilityMarginPerDepth);
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
        this.singularMinDepth = Math.max(0, spsa.singularMinDepth);
        this.singularMarginPerDepth = Math.max(0, spsa.singularMarginPerDepth);
		this.tmHeuristicsMinDepth = Math.max(0, spsa.tmHeuristicsMinDepth);
		this.tmMaxExtensionFactor = Math.max(1.0, spsa.tmMaxExtensionFactor);
		this.tmInstabilityScoreWeight = Math.max(0.0, spsa.tmInstabilityScoreWeight);
		this.razorMaxDepth = Math.max(0, spsa.razorMaxDepth);
		this.razorMarginPerDepth = Math.max(0, spsa.razorMarginPerDepth);
		this.qfutMargin = spsa.qfutMargin;
		buildLmrTable();
	}

	private void buildLmrTable() {
		for (int d = 1; d <= LMR_MAX_DEPTH; d++) {
			for (int m = 1; m <= LMR_MAX_MOVES; m++) {
				double base = lmrBase;
				double denom = lmrDivisor;
				double r = base + (Math.log(d) * Math.log(m)) / denom;
				int ir = (int) Math.round(r);
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
		iterationScores.clear();
		completedDepth = 0;
		lastScore = 0;

		Eval.refreshAccumulator(nnueState, root);

        // Age the TT for this new search
        TranspositionTable.TT.nextSearch();

		stack = new StackEntry[MAX_PLY + 5];
		for (int i = 0; i < stack.length; i++) stack[i] = new StackEntry();
		clearHistory();
		clearCounterMoves();

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

			iterationScores.add(previousScore);
			completedDepth = depth;
			lastScore = previousScore;
			if (softTimeUp(startTimeMs, limits.softMs)) break;
		}

		return result;
	}

	private boolean softTimeUp(long searchStartMs, long softTimeLimit) {
		if (softTimeLimit >= Long.MAX_VALUE / 2) {
			return false;
		}

		long now = System.currentTimeMillis();
		long currentElapsed = now - searchStartMs;

		if (now >= hardStopTimeMs) {
			return true;
		}

		if (completedDepth < tmHeuristicsMinDepth) {
			return currentElapsed >= softTimeLimit;
		}

		double instability = 0.0;
		if (iterationScores.size() >= 2) {
			int prevScore = iterationScores.get(iterationScores.size() - 2);
			int scoreDifference = Math.abs(lastScore - prevScore);
			instability += scoreDifference * tmInstabilityScoreWeight;
		}

		double extensionFactor = 1.0 + instability;
		extensionFactor = Math.min(extensionFactor, tmMaxExtensionFactor);

		long extendedSoftTime = (long) (softTimeLimit * extensionFactor);
		return currentElapsed >= extendedSoftTime;
	}

	private int negamax(long[] board, int depth, int ply, int alpha, int beta, NodeType nodeType) {
		StackEntry se = stack[ply];
		se.pvLength = 0;
		if (stopCheck()) return 0;
		nodes++;
		selDepth = Math.max(selDepth, ply);

		if (pos.isDraw(board)) return 0;

		if (nodeType != NodeType.rootNode && depth > 0) {
			int alphaMate = -MATE_VALUE + ply;
			int betaMate = MATE_VALUE - ply - 1;
			if (alpha < alphaMate) alpha = alphaMate;
			if (beta > betaMate) beta = betaMate;
			if (alpha >= beta) return alpha;
		}

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
			boolean excludedHere = stack[ply].excludedMove != MoveFactory.MOVE_NONE;
			if (!excludedHere && nodeType == NodeType.nonPVNode && tableScore != TranspositionTable.SCORE_VOID && tableDepth >= depth) {
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
			boolean excludedHere = stack[ply].excludedMove != MoveFactory.MOVE_NONE;
			if (!tableHit && !excludedHere) {
                boolean isPVHere = (nodeType != NodeType.nonPVNode);
                boolean pvBitEval = isPVHere || tableWasPv;
                entry.store(pos.zobrist(board), TranspositionTable.BOUND_NONE, 0, 0, TranspositionTable.SCORE_VOID, rawEval, pvBitEval, ply);
            }
            se.staticEval = rawEval;
        }

		if (!inCheck && nodeType == NodeType.nonPVNode && depth <= razorMaxDepth) {
			int eval = se.staticEval;
			if (eval != SCORE_NONE && pos.hasNonPawnMaterialForSTM(board)) {
				if (Math.abs(alpha) < MATE_VALUE && Math.abs(beta) < MATE_VALUE) {
					int margin = razorMarginPerDepth * Math.max(1, depth);
					if (eval + margin <= alpha) {
						int score = quiescence(board, ply, alpha - 1, alpha, NodeType.nonPVNode);
						if (score <= alpha) {
							return score;
						}
					}
				}
			}
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

		boolean prevWasNull = (ply > 0) && (stack[ply - 1].move == MoveFactory.MOVE_NONE);
		if (!inCheck && nodeType == NodeType.nonPVNode && depth >= 3 && !prevWasNull) {
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
				stack[ply].move = MoveFactory.MOVE_NONE;
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
		int killer = MoveFactory.MOVE_NONE;
		int counterToPass = MoveFactory.MOVE_NONE;
		if (!inCheck && ply > 0) {
			int prev = stack[ply - 1].move;
			if (!MoveFactory.isNone(prev)) {
				boolean prevWhite = !PositionFactory.whiteToMove(board);
				int cIdx = historyIndex(prevWhite, prev);
				if (cIdx >= 0 && cIdx < counterMoves.length) counterToPass = counterMoves[cIdx];
			}
			killer = stack[ply].searchKiller;
		}
		MovePicker picker = new MovePicker(board, pos, moveGen, history, moves, moveScores[ply], ttMoveForNode, killer, true, counterToPass);

		boolean movePlayed = false;
		int originalAlpha = alpha;
		int bestScore = -INFTY;
		int i = 0;
		int quietsTried = 0;
		int[] quietList = quietBuffers[ply];
		int quietCount = 0;
		for (int move; !MoveFactory.isNone(move = picker.next()); i++) {
			if (stopCheck()) break;

			if (move == se.excludedMove) {
				continue;
			}

			boolean isQuiet = PositionFactory.isQuiet(board, move);

			if (nodeType == NodeType.nonPVNode && !se.inCheck && isQuiet && move != ttMoveForNode && move != killer) {
				int eval = se.staticEval;
				if (eval != SCORE_NONE && Math.abs(alpha) < MATE_VALUE && Math.abs(beta) < MATE_VALUE && pos.hasNonPawnMaterialForSTM(board)) {
					int dIdxF = Math.min(depth, LMR_MAX_DEPTH);
					int mIdxF = Math.min(i + 1, LMR_MAX_MOVES);
					int lmrR = lmrTable[dIdxF][mIdxF];
					int lmrDepth = Math.max(0, depth - lmrR);
					if (lmrDepth <= futilityMaxDepth) {
						int margin = futilityMarginPerDepth * Math.max(1, lmrDepth);
						if (eval + margin <= alpha) {
							boolean givesCheck = pos.givesCheck(board, move, moveGen);
							if (!givesCheck) {
								quietsTried++;
								continue;
							}
						}
					}
				}
			}
			
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
			int extension = 0;

		if (!se.inCheck && depth >= singularMinDepth && tableHit && move == ttMoveForNode) {
				boolean ttIsLower = (tableBound & TranspositionTable.BOUND_LOWER) != 0;
				if (ttIsLower && tableScore != TranspositionTable.SCORE_VOID && Math.abs(tableScore) < MATE_VALUE && tableDepth >= depth - 3) {
					int singularBeta = tableScore - Math.max(1, singularMarginPerDepth) * depth;
					int singularDepth = Math.max(1, (depth - 1) / 2);

					int savedPVLen = se.pvLength;
					int savedMove = se.move;
					int savedExcluded = se.excludedMove;
					int savedKiller = se.searchKiller;
					int savedStaticEval = se.staticEval;
					int savedReduction = se.reduction;

					se.excludedMove = move;
					int singularValue = negamax(board, singularDepth, ply, singularBeta - 1, singularBeta, NodeType.nonPVNode);
					se.excludedMove = savedExcluded;

					se.pvLength = savedPVLen;
					se.move = savedMove;
					se.searchKiller = savedKiller;
					se.staticEval = savedStaticEval;
					se.reduction = savedReduction;

					if (singularValue < singularBeta) {
						extension = nodeType != NodeType.pvNode && MoveFactory.GetFlags(move) != MoveFactory.FLAG_PROMOTION ? 2 : 1;
					} else if (singularBeta >= beta) {
						return singularBeta;
					}
				}
			}

			if (extension > 0) {
				searchDepthChild = Math.max(1, searchDepthChild + extension);
			}
			int appliedReduction = 0;
			boolean parentIsPV = (nodeType != NodeType.nonPVNode);
			boolean childPv = parentIsPV && i == 0;
			if (!se.inCheck && !childPv && isQuiet && depth >= 3 && i >= 1) {
				if (move == ttMoveForNode) {
					appliedReduction = 0;
				} else {
				int dIdx = Math.min(depth, LMR_MAX_DEPTH);
				int mIdx = Math.min(i + 1, LMR_MAX_MOVES);
				int r = lmrTable[dIdx][mIdx];
				if (parentIsPV) r = Math.max(0, r - 1);
				if (move == killer) r = Math.max(0, r - 1);
				boolean whiteSTM = PositionFactory.whiteToMove(board);
				int hVal = historyScore(whiteSTM, move);
				if (hVal > (HISTORY_MAX >> 1)) r = Math.max(0, r - 1);
				else if (hVal < -(HISTORY_MAX >> 1)) r = r + 1;
				if (r > 0) {
					appliedReduction = Math.min(r, depth - 1);
					searchDepthChild = Math.max(1, depth - 1 - appliedReduction);
					se.reduction = appliedReduction;
				}
				}
			}

			Eval.doMoveAccumulator(nnueState, board, move);
			if (!pos.makeMoveInPlace(board, move, moveGen)) { Eval.undoMoveAccumulator(nnueState); continue; }
			movePlayed = true;
			if (isQuiet && quietCount < MAX_MOVES) quietList[quietCount++] = move;

			stack[ply].move = move;
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
					applyHistoryUpdatesForCutoff(white, move, Math.max(1, depth), quietList, quietCount);
					if (ply > 0) {
						int prev = stack[ply - 1].move;
						if (!MoveFactory.isNone(prev)) {
							boolean prevWhite = !PositionFactory.whiteToMove(board);
							int cIdx = historyIndex(prevWhite, prev);
							if (cIdx >= 0 && cIdx < counterMoves.length) counterMoves[cIdx] = MoveFactory.intToMove(move);
						}
					}
				}
				break;
			}
		}

		if (!movePlayed) {
			if (inCheck) return -MATE_VALUE + ply;
			int anyLegal = moveGen.getFirstLegalMove(board);
			return anyLegal != 0 ? alpha : 0;
		}

		int resultBound = bestScore >= beta ? TranspositionTable.BOUND_LOWER : (bestScore > originalAlpha ? TranspositionTable.BOUND_EXACT : TranspositionTable.BOUND_UPPER);

		int bestMove = se.pvLength > 0 ? se.pv[0] : MoveFactory.MOVE_NONE;
		int rawEval = (se.staticEval != SCORE_NONE) ? se.staticEval : 0;
		boolean isPV = (nodeType != NodeType.nonPVNode);
		boolean pvBit = isPV || tableWasPv;
		boolean excludedHere = stack[ply].excludedMove != MoveFactory.MOVE_NONE;
		if (!excludedHere) {
			entry.store(pos.zobrist(board), resultBound, depth, MoveFactory.intToMove(bestMove), bestScore, rawEval, pvBit, ply);
		}

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
		boolean excludedHere = stack[ply].excludedMove != MoveFactory.MOVE_NONE;
        if (ttHit) {
            int ttBound = ttEntry.getBound();
            int ttScore = ttEntry.getScore(ply);
            ttStaticEval = ttEntry.getStaticEval();
            ttPV = ttEntry.wasPV();
			if (!excludedHere && nodeType == NodeType.nonPVNode && ttScore != TranspositionTable.SCORE_VOID) {
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
				if (!ttHit && !excludedHere) {
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

		int[] moves = moveBuffers[ply];
		int ttMoveForQ = ttHit ? MoveFactory.intToMove(ttEntry.getPackedMove()) : MoveFactory.MOVE_NONE;
		MovePicker picker = new MovePicker(board, pos, moveGen, history, moves, moveScores[ply], ttMoveForQ, MoveFactory.MOVE_NONE, inCheck, MoveFactory.MOVE_NONE);

		if (!inCheck && qfutMargin != 0) {
			int maxGain = 0;
			int capCount = moveGen.generateCaptures(board, moves, 0);
			for (int i = 0; i < capCount; i++) {
				int mv = moves[i];
				int see = SEE.see(board, mv);
				if (see > maxGain) maxGain = see;
			}
			int futBound = standPat + maxGain + qfutMargin;
			if (futBound <= alpha && Math.abs(alpha) < MATE_VALUE && Math.abs(beta) < MATE_VALUE) {
				return futBound;
			}
		}

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

	private void clearCounterMoves() {
		for (int i = 0; i < counterMoves.length; i++) counterMoves[i] = MoveFactory.MOVE_NONE;
	}

	private int historyScore(boolean white, int move) {
		return history[historyIndex(white, move)];
	}

	private static int calculateHistoryBonus(int depth) {
		int bonus = Math.max(0, depth * 300 - 300);
		return Math.min(bonus, HISTORY_MAX - 1);
	}

	private void updateHistoryScore(int idx, int delta) {
		int d = Math.max(-HISTORY_MAX, Math.min(HISTORY_MAX, delta));
		int old = history[idx];
		int adj = (int) (((long) Math.abs(d) * (long) old) / HISTORY_MAX);
		history[idx] = old + d - adj;
	}

	private void applyHistoryUpdatesForCutoff(boolean white, int bestMove, int depth, int[] quietMoves, int count) {
		int bonus = calculateHistoryBonus(depth);
		int malus = -bonus;
		int bestIdx = historyIndex(white, bestMove);
		updateHistoryScore(bestIdx, bonus);
		for (int i = 0; i < count; i++) {
			int mv = quietMoves[i];
			if (mv == bestMove) continue;
			int idx = historyIndex(white, mv);
			updateHistoryScore(idx, malus);
		}
	}
}


