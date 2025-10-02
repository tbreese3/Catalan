package org.engine;

final class MovePicker {
	private final long[] board;
	private final PositionFactory pos;
	private final MoveGenerator gen;
	private final int[] buffer;
	private final int[] scores;
	private final int[] history;
	private final int ttMove;
	private final int killerMove;
	private final boolean includeQuiets;

	private static final int[] PIECE_VALUES = {100, 320, 330, 500, 900, 20000, 100, 320, 330, 500, 900, 20000};
	private static final int[] PROMO_VALUES = {320, 330, 500, 900};

	    private enum Stage { TT, KILLER, CAPTURES, QUIETS, BAD_CAPTURES, DONE }
    private Stage stage;
	private int index;
	private int count;
	private boolean ttTried;
	private boolean killerTried;

		// Capture partitioning
		private int capGoodCount;
		private int badCount;
		private int badIndex;
		private final int[] badBuffer;
		private final int[] badScores;

	MovePicker(long[] board, PositionFactory pos, MoveGenerator gen, int[] history, int[] moveBuffer, int[] scoreBuffer, int ttMove, int killerMove, boolean includeQuiets) {
		this.board = board;
		this.pos = pos;
		this.gen = gen;
		this.history = history;
		this.buffer = moveBuffer;
		this.scores = scoreBuffer;
        this.ttMove = MoveFactory.intToMove(ttMove);
        this.killerMove = MoveFactory.intToMove(killerMove);
		this.includeQuiets = includeQuiets;
        this.stage = Stage.TT;
		this.index = 0;
		this.count = 0;
		this.ttTried = false;
		this.killerTried = false;
		this.capGoodCount = 0;
		this.badCount = 0;
		this.badIndex = 0;
		this.badBuffer = new int[moveBuffer.length];
		this.badScores = new int[scoreBuffer.length];
	}

	private int scoreCaptureMVVLVA(int mv) {
		int from = MoveFactory.GetFrom(mv);
		int to = MoveFactory.GetTo(mv);
		int flags = MoveFactory.GetFlags(mv);

		int attacker = PositionFactory.pieceAt(board, from);
		if (attacker == -1) attacker = PositionFactory.whiteToMove(board) ? 0 : 6; // fallback to pawn if empty (shouldn't happen)

		int victim;
		if (flags == MoveFactory.FLAG_EN_PASSANT) {
			victim = PositionFactory.whiteToMove(board) ? 6 : 0; // BP or WP
		} else {
			victim = PositionFactory.pieceAt(board, to);
		}

		boolean isCapture = (flags == MoveFactory.FLAG_EN_PASSANT) || (victim != -1);
		int score = 0;
		if (isCapture) {
			int vicVal = PIECE_VALUES[victim == -1 ? 0 : victim];
			int attVal = PIECE_VALUES[attacker];
			// Classic MVV-LVA: prefer higher victim, lower attacker
			score = (vicVal << 4) - attVal; // multiply victim by 16 for separation
		}

		if (flags == MoveFactory.FLAG_PROMOTION) {
			int promo = MoveFactory.GetPromotion(mv);
			int promVal = PROMO_VALUES[promo & 3];
			// Strongly prefer promotions over regular captures; add big bonus
			score += 1_000_000 + promVal;
		}

		return score;
	}

	private void scorecaptures(int size) {
		for (int i = 0; i < size; i++) {
			scores[i] = scoreCaptureMVVLVA(buffer[i]);
		}
	}

	private void partitionCapturesBySEE(int size) {
			capGoodCount = 0;
			badCount = 0;
			badIndex = 0;
			for (int i = 0; i < size; i++) {
				int mv = buffer[i];
				int sc = scores[i];
				int flags = MoveFactory.GetFlags(mv);
				boolean good;
				if (flags == MoveFactory.FLAG_PROMOTION) {
					good = true;
				} else {
					int see = SEE.see(board, mv);
					good = see >= 0;
				}
				if (good) {
					buffer[capGoodCount] = mv;
					scores[capGoodCount] = sc;
					capGoodCount++;
				} else {
					badBuffer[badCount] = mv;
					badScores[badCount] = sc;
					badCount++;
				}
			}
		}

	private static int historyIndex(boolean white, int move) {
		int from = MoveFactory.GetFrom(move);
		int to = MoveFactory.GetTo(move);
		int side = white ? 0 : 1;
		return (side << 12) | (from << 6) | to;
	}

	private void scorequiets(int size) {
		boolean white = PositionFactory.whiteToMove(board);
		for (int i = 0; i < size; i++) {
			int m = buffer[i];
			int idx = historyIndex(white, m);
			int score = (history != null && idx >= 0 && idx < history.length) ? history[idx] : 0;
			scores[i] = score;
		}
	}

	// Reusable selection helper: picks best-scored move in [listIndex, size) and swaps into listIndex
	private int getnextmove(int[] moves, int[] scores, int size, int listIndex) {
		int max = Integer.MIN_VALUE;
		int maxIndex = listIndex;
		for (int i = listIndex; i < size; i++) {
			int s = scores[i];
			if (s > max) { max = s; maxIndex = i; }
		}
		int tmpM = moves[maxIndex];
		moves[maxIndex] = moves[listIndex];
		moves[listIndex] = tmpM;
		int tmpS = scores[maxIndex];
		scores[maxIndex] = scores[listIndex];
		scores[listIndex] = tmpS;
		return moves[listIndex];
	}

	int next() {
        for (;;) {
            switch (stage) {
                case TT: {
                    stage = Stage.KILLER;
                    if (!ttTried && !MoveFactory.isNone(ttMove)) {
						ttTried = true;
						if (pos.isPseudoLegalMove(board, ttMove, gen)) return ttMove;
					}
					break;
				}
                case KILLER: {
                    stage = Stage.CAPTURES;
					if (!includeQuiets) break;
                    if (!killerTried && !MoveFactory.isNone(killerMove) && killerMove != ttMove) {
						killerTried = true;
                        if (pos.isPseudoLegalMove(board, killerMove, gen)) return killerMove;
					}
					break;
				}
				case CAPTURES: {
					if (count == 0) {
						index = 0;
						count = gen.generateCaptures(board, buffer, 0);
						scorecaptures(count);
						partitionCapturesBySEE(count);
					}
					while (index < capGoodCount) {
						int m = getnextmove(buffer, scores, capGoodCount, index++);
						m = MoveFactory.intToMove(m);
						if (m == ttMove || m == killerMove) continue;
						return m;
					}
					count = 0;
					stage = includeQuiets ? Stage.QUIETS : Stage.BAD_CAPTURES;
					break;
				}
                case QUIETS: {
					if (count == 0) {
						index = 0;
						count = gen.generateQuiets(board, buffer, 0);
						scorequiets(count);
					}
					while (index < count) {
						int m = getnextmove(buffer, scores, count, index++);
                        m = MoveFactory.intToMove(m);
						if (m == ttMove || m == killerMove) continue;
						return m;
					}
					stage = Stage.BAD_CAPTURES;
					break;
				}
				case BAD_CAPTURES: {
					while (badIndex < badCount) {
						int m = getnextmove(badBuffer, badScores, badCount, badIndex++);
						m = MoveFactory.intToMove(m);
						if (m == ttMove || m == killerMove) continue;
						return m;
					}
					stage = Stage.DONE;
					break;
				}
                default:
					return 0;
			}
		}
	}
}
