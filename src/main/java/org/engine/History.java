package org.engine;

public final class History {
	private final int[] table = new int[2 * 64 * 64];

	private static final int DECAY_SHIFT = 8; 

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

	public void onQuietFailHigh(boolean white, int move, int depth) {
		int from = MoveFactory.GetFrom(move);
		int to = MoveFactory.GetTo(move);
		int idx = index(white, from, to);
		int bonus = depth * depth;
		int current = table[idx];
		// Exponential decay: new = old - old/2^k + bonus
		current -= (current >> DECAY_SHIFT);
		current += bonus;
		table[idx] = current;
	}
}


