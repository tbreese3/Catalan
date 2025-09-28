package org.engine;

import static org.engine.PositionFactory.*;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

import org.junit.jupiter.api.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MoveGeneratorPerftAllTest {

	private static final PositionFactory POS_FACTORY = new PositionFactory();
	private static final MoveGenerator   GEN         = new MoveGenerator();

	private record Vec(String fen,int depth,long expNodes,long expCaps,long expQuiets) {}

	private List<Vec> vecs;

	@BeforeAll
	void load() throws Exception {
		vecs = new ArrayList<>();
		try (InputStream in = getClass().getResourceAsStream("/qbbAll.txt");
			 BufferedReader br = new BufferedReader(new InputStreamReader(
					Objects.requireNonNull(in, "qbbAll.txt not on classpath")))) {

			br.lines().map(String::trim)
					.filter(l -> !(l.isEmpty() || l.startsWith("#")))
					.forEach(l -> {
						String[] p = l.split(";");
						// New format: FEN ; depth ; nodes ; captures ; quiets
						if (p.length < 5) return;
						vecs.add(new Vec(
								p[0].trim(),
								Integer.parseInt(p[1].replaceAll("\\D","")),
								Long.parseLong(p[2].replaceAll("\\D","")),
								Long.parseLong(p[3].replaceAll("\\D","")),
								Long.parseLong(p[4].replaceAll("\\D",""))));
					});
		}
		Assertions.assertFalse(vecs.isEmpty(), "qbbAll.txt missing / empty");
	}

	@Test
	void runAllPerftCases() {
		int completed = 0;
		long totalNodes = 0L;
		long totalCaps = 0L;
		long totalQuiets = 0L;
		long totalTimeMs = 0L;

		for (Vec v : vecs) {
			long[] root = POS_FACTORY.fromFen(v.fen());
			Counters c = new Counters();

			System.out.printf("Processing test case %d/%d: %s depth %d%n",
					completed + 1, vecs.size(), v.fen(), v.depth());

			long t0 = System.nanoTime();
			long gotNodes = perftNoEvasions(root, v.depth(), 0, c);
			long dtMs = (System.nanoTime() - t0) / 1_000_000L;

			totalNodes += gotNodes;
			totalCaps  += c.caps;
			totalQuiets += c.quiets;
			totalTimeMs += dtMs;

			long nps = dtMs > 0 ? (gotNodes * 1000L) / dtMs : 0L;
			long runningNps = totalTimeMs > 0 ? (totalNodes * 1000L) / totalTimeMs : 0L;


			completed++;
			System.out.printf(
					"Completed test case %d: nodes=%,d, caps=%,d, quiets=%,d, time=%dms, nps=%,d | Running total: %,d nodes, avg nps=%,d%n",
					completed,
					gotNodes,
					c.caps,
					c.quiets,
					dtMs, nps, totalNodes, runningNps);

			// Immediate fail on any mismatch
			Assertions.assertEquals(v.expNodes(),  gotNodes, "nodes mismatch at case "+completed);
			Assertions.assertEquals(v.expCaps(),   c.caps,   "captures mismatch at case "+completed);
			Assertions.assertEquals(v.expQuiets(), c.quiets,  "quiets mismatch at case "+completed);
		}

		System.out.printf("Summary: %,d cases. Total: %,d nodes in %,dms (avg %,d nps)%n",
				vecs.size(), totalNodes, totalTimeMs,
				totalTimeMs > 0 ? (totalNodes * 1000L) / totalTimeMs : 0L);
	}

	private static final int MAX_PLY  = 64;
	private static final int LIST_CAP = 256;
	private static final int[][] MOVES = new int[MAX_PLY][LIST_CAP];

	private static final class Counters { long caps, quiets; }

	private long perftNoEvasions(long[] bb,int depth,int ply,Counters cnt){
		if (depth == 0){ return 1; }

		boolean stmWhite = (bb[META] & 1L) == 0;

		int[] moves = MOVES[ply];
		int legalCnt;

		int caps   = GEN.generateCaptures(bb, moves, 0);
		int total  = GEN.generateQuiets  (bb, moves, caps);
		int quiets = total - caps;

		legalCnt   = total;
		cnt.caps  += caps;
		cnt.quiets+= quiets;

		long nodes = 0;
		for (int i = 0; i < legalCnt; ++i){
			int mv = moves[i];

			if (!POS_FACTORY.makeMoveInPlace(bb, mv, GEN)) continue;
			nodes += perftNoEvasions(bb, depth-1, ply+1, cnt);
			POS_FACTORY.undoMoveInPlace(bb);
		}
		return nodes;
	}
}