package org.engine;

public final class TimeManager {
	public static record TimeAllocation(long soft, long maximum) {}
	public TimeAllocation allocate(boolean whiteToMove, int wtime, int btime, int winc, int binc, int movesToGo, int moveTime) {
        if (moveTime > 0) {
            long time = Math.max(1, (long) moveTime - 30L);
            return new TimeAllocation(time, time);
        }
        long playerTime = whiteToMove ? wtime : btime;
        long playerInc = whiteToMove ? winc : binc;
        if (playerTime <= 0) {
            return new TimeAllocation(1, 2);
        }
        playerTime = Math.max(1, playerTime - 30L);
        int mtg = movesToGo > 0 ? movesToGo : 50;
        long idealTime = (playerTime / Math.max(1, mtg)) + Math.max(0, playerInc);
        long softTimeMs = idealTime;
        long hardTimeMs = softTimeMs * 5L;
        hardTimeMs = Math.min(hardTimeMs, playerTime / 8L + Math.max(0, playerInc));
        hardTimeMs = Math.min(hardTimeMs, Math.max(1, playerTime - 50L));
        if (hardTimeMs <= 0) hardTimeMs = 2;
        if (softTimeMs > hardTimeMs) softTimeMs = Math.max(1, hardTimeMs - 10);
        return new TimeAllocation(Math.max(1, softTimeMs), Math.max(2, hardTimeMs));
	}
}


