package org.engine;

public final class History {

	private final int[][][] table = new int[2][64][64];
	
	private static final int MAX_HISTORY = 16384;
	private static final int MIN_HISTORY = -16384;
	
	public void clear() {
		for (int color = 0; color < 2; color++) {
			for (int from = 0; from < 64; from++) {
				for (int to = 0; to < 64; to++) {
					table[color][from][to] = 0;
				}
			}
		}
	}
	
	public int score(boolean white, int move) {
		int from = MoveFactory.GetFrom(move);
		int to = MoveFactory.GetTo(move);
		int color = white ? 0 : 1;
		return table[color][from][to];
	}
	
	public void onQuietFailHigh(boolean white, int move, int depth) {
		int from = MoveFactory.GetFrom(move);
		int to = MoveFactory.GetTo(move);
		int color = white ? 0 : 1;
		
		int bonus = Math.min(depth * depth, 400);
		
		int current = table[color][from][to];
		current += bonus - current * Math.abs(bonus) / MAX_HISTORY;
		
		if (current > MAX_HISTORY) current = MAX_HISTORY;
		if (current < MIN_HISTORY) current = MIN_HISTORY;
		
		table[color][from][to] = current;
	}
}


