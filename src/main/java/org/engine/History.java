package org.engine;

public final class History {
	private final int[] table = new int[12 * 64 * 64];

	private static final int DAMPING_SHIFT = 8;
	private static final int SCALE_SHIFT = 4;
	private static final int MAX_ABS = 1_000_000;

	private static int index(int piece, int from, int to) {
		return (piece << 12) | (from << 6) | to;
	}

	public void clear() {
		for (int i = 0; i < table.length; i++) table[i] = 0;
	}

	public int score(int piece, int move) {
		int from = MoveFactory.GetFrom(move);
		int to = MoveFactory.GetTo(move);
		return table[index(piece, from, to)];
	}

	public void onQuietFailHigh(int piece, int move, int depth) {
		update(piece, move, depth * depth);
	}

	public void onQuietImprove(int piece, int move, int depth) {
		update(piece, move, Math.max(1, depth));
	}

	public void onQuietFailLow(int piece, int move, int depth) {
		update(piece, move, -(depth * depth));
	}

	private void update(int piece, int move, int bonus) {
		int from = MoveFactory.GetFrom(move);
		int to = MoveFactory.GetTo(move);
		int idx = index(piece, from, to);
		int current = table[idx];
		int ab = Math.abs(bonus);
		current -= (current * ab) >> DAMPING_SHIFT;
		current += (bonus << SCALE_SHIFT);
		if (current > MAX_ABS) current = MAX_ABS;
		else if (current < -MAX_ABS) current = -MAX_ABS;
		table[idx] = current;
	}
}


