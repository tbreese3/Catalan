package org.engine;

public final class History {
	private final int[] psTable = new int[12 * 64];

	private static final int DECAY_SHIFT = 8;
	private static final int HIST_MAX = 1 << 16; 

	private static int indexPieceTo(int piece, int to) {
		return (piece << 6) | to;
	}

	public void clear() {
		for (int i = 0; i < psTable.length; i++) psTable[i] = 0;
	}

	public int score(long[] board, int move) {
		int from = MoveFactory.GetFrom(move);
		int to = MoveFactory.GetTo(move);
		int piece = PositionFactory.pieceAt(board, from);
		if (piece == -1) return 0;
		return psTable[indexPieceTo(piece, to)];
	}

	public void onQuietFailHigh(long[] board, int move, int depth) {
		int from = MoveFactory.GetFrom(move);
		int to = MoveFactory.GetTo(move);
		int piece = PositionFactory.pieceAt(board, from);
		if (piece == -1) return;
		int idx = indexPieceTo(piece, to);
		int bonus = depth * depth;
		int current = psTable[idx];
		current -= (current >> DECAY_SHIFT);
		current += bonus;
		if (current > HIST_MAX) current = HIST_MAX;
		if (current < -HIST_MAX) current = -HIST_MAX;
		psTable[idx] = current;
	}
}


