package org.engine;

public final class History {
	private final int[] table = new int[2 * 64 * 64];

	private static final int MAX_HISTORY = 16384;  // Maximum history value
	private static final int HISTORY_GRAVITY = 324; // Gravity factor for smooth updates 

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
		int bonus = Math.min(depth * depth, HISTORY_GRAVITY);
		int current = table[idx];
		int absBonus = Math.abs(bonus);
		int delta = bonus - current * absBonus / MAX_HISTORY;
		current += delta;
		current = Math.max(-MAX_HISTORY, Math.min(MAX_HISTORY, current));
		
		table[idx] = current;
	}
	
	public void onQuietFailLow(boolean white, int move, int depth) {
		int from = MoveFactory.GetFrom(move);
		int to = MoveFactory.GetTo(move);
		int idx = index(white, from, to);
		int penalty = -Math.min(depth * depth, HISTORY_GRAVITY);
		int current = table[idx];
		int absPenalty = Math.abs(penalty);
		int delta = penalty - current * absPenalty / MAX_HISTORY;
		current += delta;
		current = Math.max(-MAX_HISTORY, Math.min(MAX_HISTORY, current));
		table[idx] = current;
	}
}


