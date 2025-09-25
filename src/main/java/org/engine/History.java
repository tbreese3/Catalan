package org.engine;

/**
 * Simple History Heuristic implementation responsible for scoring quiet moves
 * based on how often they cause beta cutoffs. Scores are indexed by side and
 * move (from -> to). On a quiet fail-high, we add a depth-scaled bonus to the
 * corresponding entry with exponential decay to keep values bounded.
 */
public final class History {

	// Indexing: side (0 = white, 1 = black) x from(64) x to(64)
	private final int[] table = new int[2 * 64 * 64];

	// Controls the decay strength when adding bonuses
	private static final int DECAY_SHIFT = 8; // 1/256 decay per update

	private static int index(boolean white, int from, int to) {
		int s = white ? 0 : 1;
		return (s << 12) | (from << 6) | to; // s*4096 + from*64 + to
	}

	public void clear() {
		for (int i = 0; i < table.length; i++) table[i] = 0;
	}

	/**
	 * Score a quiet move for ordering.
	 */
	public int score(boolean white, int move) {
		int from = MoveFactory.GetFrom(move);
		int to = MoveFactory.GetTo(move);
		return table[index(white, from, to)];
	}

	/**
	 * Update history on a quiet beta cutoff. Uses depth^2 bonus with decay to
	 * avoid runaway growth.
	 */
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


