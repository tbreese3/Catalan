package org.engine;

public final class History {
	private final int[] table = new int[2 * 64 * 64];

	private static final int GRAVITY = 16384;
	
	private static final int MAX_HISTORY = 16384;

	private static int index(boolean white, int from, int to) {
		int s = white ? 0 : 1;
		return (s << 12) | (from << 6) | to;
	}

	public void clear() {
		for (int i = 0; i < table.length; i++) table[i] = 0;
	}

	public int score(boolean white, int move) {
		int from = MoveFactory.GetFrom(move);
		int to = MoveFactory.GetTo(move);
		return table[index(white, from, to)];
	}

	public void update(boolean white, int move, int depth, boolean good) {
		int from = MoveFactory.GetFrom(move);
		int to = MoveFactory.GetTo(move);
		int idx = index(white, from, to);
		int bonus = depth * depth;
		if (!good) {
			bonus = -bonus;
		}
	
		int current = table[idx];
		current += bonus - current * Math.abs(bonus) / GRAVITY;
		current = Math.max(-MAX_HISTORY, Math.min(MAX_HISTORY, current));
		
		table[idx] = current;
	}
	
	public void onQuietFailHigh(boolean white, int move, int depth) {
		update(white, move, depth, true);
	}
}


