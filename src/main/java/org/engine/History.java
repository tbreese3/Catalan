package org.engine;

public final class History {
	private final int[][][] table = new int[2][64][64];

	private static final int HISTORY_MAX = 1 << 16;

	public void clear() {
		for (int s = 0; s < 2; s++)
			for (int from = 0; from < 64; from++)
				for (int to = 0; to < 64; to++)
					table[s][from][to] = 0;
	}

	public int score(boolean white, int move) {
		int from = MoveFactory.GetFrom(move);
		int to = MoveFactory.GetTo(move);
		int s = white ? 0 : 1;
		return table[s][from][to];
	}

	public void onQuietFailHigh(boolean white, int move, int depth) {
		int from = MoveFactory.GetFrom(move);
		int to = MoveFactory.GetTo(move);
		int s = white ? 0 : 1;
		int bonus = depth * depth;
		int current = table[s][from][to];
		current += bonus - (current * bonus) / HISTORY_MAX;
		if (current > HISTORY_MAX) current = HISTORY_MAX;
		if (current < -HISTORY_MAX) current = -HISTORY_MAX;
		table[s][from][to] = current;
	}
}


