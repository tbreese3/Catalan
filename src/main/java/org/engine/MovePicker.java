package org.engine;

final class MovePicker {
	private final long[] board;
	private final PositionFactory pos;
	private final MoveGenerator gen;
	private final int[] buffer;
	private final int[] scores;
	private final int[][][] history;
	private final int[][][][] contHistory;
	private final int prevMove;
	private final int ttMove;
	private final int killerMove;
	private final boolean includeQuiets;

	private static final int[] PIECE_VALUES = {100, 320, 330, 500, 900, 20000, 100, 320, 330, 500, 900, 20000};
	private static final int[] PROMO_VALUES = {320, 330, 500, 900};

    private enum Stage { TT, KILLER, CAPTURES, QUIETS, DONE }
    private Stage stage;
	private int index;
	private int count;
	private boolean ttTried;
	private boolean killerTried;

	MovePicker(long[] board, PositionFactory pos, MoveGenerator gen, int[][][] history, int[][][][] contHistory, int prevMove, int[] moveBuffer, int[] scoreBuffer, int ttMove, int killerMove, boolean includeQuiets) {
		this.board = board;
		this.pos = pos;
		this.gen = gen;
		this.history = history;
		this.contHistory = contHistory;
		this.prevMove = prevMove;
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

	private void scorequiets(int size) {
		boolean white = PositionFactory.whiteToMove(board);
		int side = white ? 0 : 1;
		int prevTo = MoveFactory.isNone(prevMove) ? -1 : MoveFactory.GetTo(prevMove);
		for (int i = 0; i < size; i++) {
			int m = buffer[i];
			int from = MoveFactory.GetFrom(m);
			int to = MoveFactory.GetTo(m);
			int base = (history != null) ? history[side][from][to] : 0;
			int cont = 0;
			if (contHistory != null && prevTo >= 0 && prevTo < 64) {
				cont = contHistory[side][prevTo][from][to];
			}
			int score = base + cont;
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
					}
					while (index < count) {
						int m = getnextmove(buffer, scores, count, index++);
                        m = MoveFactory.intToMove(m);
						if (m == ttMove || m == killerMove) continue;
						return m;
					}
					count = 0;
                    stage = includeQuiets ? Stage.QUIETS : Stage.DONE;
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
                    stage = Stage.DONE;
					break;
				}
                default:
					return 0;
			}
		}
	}
}
