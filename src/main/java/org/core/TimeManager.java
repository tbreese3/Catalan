// C:/dev/Helios/src/main/java/core/impl/TimeManagerImpl.java
package org.core;

import org.chesslib.Board;
import org.chesslib.Side;

public final class TimeManager {
	public static record TimeAllocation(long soft, long maximum) {}

	public static final int TIMER_BUFFER_MS = 200; // Buffer to avoid flagging on time controls
	public static final int MOVE_TIME_BUFFER_MS = 5; // Smaller buffer for explicit movetime
	public static final int MIN_SEARCH_TIME_MS = 200; // Minimum when movetime is used
	public static final int DEFAULT_MOVES_TO_GO = 40;

	/**
	 * Allocate soft/hard time limits (milliseconds) based on UCI parameters.
	 * soft limit is used to stop after finishing the current iteration; hard limit is an absolute cutoff.
	 */
	public TimeAllocation allocate(Side toMove,
	                              int wtime, int btime,
	                              int winc, int binc,
	                              int movesToGo,
	                              int moveTime) {
		final boolean hasMoveTime = moveTime > 0;
		final int buffer = hasMoveTime ? MOVE_TIME_BUFFER_MS : TIMER_BUFFER_MS;
		if (hasMoveTime) {
			int timeMs = Math.max(moveTime, MIN_SEARCH_TIME_MS);
			long hard = Math.max(1, timeMs - buffer);
			long soft = Math.max(1, timeMs - buffer);
			return new TimeAllocation(soft, hard);
		}

		int playerTime = toMove == Side.WHITE ? wtime : btime;
		int playerInc = toMove == Side.WHITE ? winc : binc;
		if (playerTime < 0) playerTime = 0;
		if (playerInc < 0) playerInc = 0;
		int mtg = movesToGo > 0 ? movesToGo : DEFAULT_MOVES_TO_GO;

		int baseSlice = Math.max(playerTime / 2, playerTime / mtg);
		int newSearchTime = playerInc + baseSlice;
		newSearchTime = Math.min(newSearchTime, Math.max(playerTime - buffer, 0));

		double softCalc = 0.65 * ((playerTime / (double) mtg) + (playerInc * 0.75));
		long softMs = (long) Math.floor(softCalc);
		softMs = Math.min(softMs, newSearchTime);
		softMs = Math.max(1, softMs - buffer);

		long hardMs = Math.max(1, newSearchTime);

		return new TimeAllocation(softMs, hardMs);
	}

	// Legacy placeholder to satisfy existing references (unused in UCI integration).
	public TimeAllocation calculate(Board board) {
		return new TimeAllocation(0L, 0L);
	}
}