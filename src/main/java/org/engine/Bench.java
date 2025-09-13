package org.engine;

public final class Bench {

	private static final String[] BENCH_FENS = new String[]{
			"r3k2r/2pb1ppp/2pp1q2/p7/1nP1B3/1P2P3/P2N1PPP/R2QK2R w KQkq a6 0 14",
			"4rrk1/2p1b1p1/p1p3q1/4p3/2P2n1p/1P1NR2P/PB3PP1/3R1QK1 b - - 2 24",
			"r3qbrk/6p1/2b2pPp/p3pP1Q/PpPpP2P/3P1B2/2PB3K/R5R1 w - - 16 42",
			"6k1/1R3p2/6p1/2Bp3p/3P2q1/P7/1P2rQ1K/5R2 b - - 4 44",
			"8/8/1p2k1p1/3p3p/1p1P1P1P/1P2PK2/8/8 w - - 3 54",
			"7r/2p3k1/1p1p1qp1/1P1Bp3/p1P2r1P/P7/4R3/Q4RK1 w - - 0 36",
			"r1bq1rk1/pp2b1pp/n1pp1n2/3P1p2/2P1p3/2N1P2N/PP2BPPP/R1BQ1RK1 b - - 2 10",
			"3r3k/2r4p/1p1b3q/p4P2/P2Pp3/1B2P3/3BQ1RP/6K1 w - - 3 87",
			"2r4r/1p4k1/1Pnp4/3Qb1pq/8/4BpPp/5P2/2RR1BK1 w - - 0 42",
			"4q1bk/6b1/7p/p1p4p/PNPpP2P/KN4P1/3Q4/4R3 b - - 0 37"
	};

	private Bench() {}

	public static void run(int depth) {
		long totalNodes = 0L;
		long totalTimeMs = 0L;

		for (String fen : BENCH_FENS) {
			long[] board = PositionFactory.fromFen(fen);
			long t0 = System.nanoTime();
			long nodes = perft(board, depth);
			long ms = (System.nanoTime() - t0) / 1_000_000L;
			totalNodes += nodes;
			totalTimeMs += ms;
		}

		long totalNps = totalTimeMs > 0 ? (1000L * totalNodes) / totalTimeMs : 0L;
		System.out.printf("Nodes searched: %d%n", totalNodes);
		System.out.printf("nps: %d%n", totalNps);
		System.out.println("benchok");
	}

	private static long perft(long[] board, int depth) {
		if (depth == 0) return 1L;
		long nodes = 0L;
		int[] moves = new int[256];
		int n = MoveGenerator.generateCaptures(board, moves, 0);
		n = MoveGenerator.generateQuiets(board, moves, n);
		for (int i = 0; i < n; i++) {
			int mv = moves[i];
			if (!PositionFactory.makeMoveInPlace(board, mv)) continue;
			nodes += perft(board, depth - 1);
			PositionFactory.undoMoveInPlace(board);
		}
		return nodes;
	}
}


