package org.engine;

import static org.engine.PositionFactory.*;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Stream;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MoveGeneratorPerftAllTest {

    private static final PositionFactory POS_FACTORY = new PositionFactory();
    private static final MoveGenerator   GEN         = new MoveGenerator();

    private record Vec(String fen,int depth,long expNodes,long expCaps,long expQuiets, long expChecks) {}

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
                        vecs.add(new Vec(
                                p[0].trim(),
                                Integer.parseInt(p[1].replaceAll("\\D","")),
                                Long.parseLong(p[2].replaceAll("\\D","")),
                                Long.parseLong(p[3].replaceAll("\\D","")),
                                Long.parseLong(p[4].replaceAll("\\D","")),
                                Long.parseLong(p[5].replaceAll("\\D",""))));
                    });
        }
        Assertions.assertFalse(vecs.isEmpty(), "qbbAll.txt missing / empty");
    }

    private long totNodes, totCaps, totQuiets, totChecks, timeNs;
    private long expNodesTot, expCapsTot, expQuietsTot, expChecks;

    Stream<Vec> vecStream(){ return vecs.stream(); }

    private static final class C { long nodes,caps,quiets,checks; }

    @ParameterizedTest(name="xPerft {index}")
    @MethodSource("vecStream")
    void perft(Vec v){
        long[] root = POS_FACTORY.fromFen(v.fen());
        C c = new C();

        long t0 = System.nanoTime();
        long got = perft(root, v.depth(), 0, c);
        timeNs += System.nanoTime() - t0;

        totNodes  += got;
        totCaps   += c.caps;
        totQuiets += c.quiets;
        totChecks += c.checks;

        Assertions.assertAll(
                () -> Assertions.assertEquals(v.expNodes(),  got,    "nodes"),
                () -> Assertions.assertEquals(v.expCaps(),  c.caps,    "captures"),
                () -> Assertions.assertEquals(v.expQuiets(),  c.quiets,    "captures"),
                () -> Assertions.assertEquals(v.expChecks(), c.checks, "checks")
        );
        expNodesTot  += v.expNodes();
        expCapsTot   += v.expCaps();
        expQuietsTot += v.expQuiets();
        expChecks += v.expChecks();
    }

    @AfterAll
    void report() {
        double secs = timeNs / 1_000_000_000.0;

        System.out.printf("""
        ── xPERFT SUMMARY ────────────────────────────────────────────
        counter      actual        expected        Δ
        --------------------------------------------------------------
        nodes   : %,15d  / %,15d  (%+d)
        caps    : %,15d  / %,15d  (%+d)
        quiets  : %,15d  / %,15d  (%+d)
        checks  : %,15d  / %,15d  (%+d)
        --------------------------------------------------------------
        time    : %.3f s   → %,d NPS
        ───────────────────────────────────────────────────────────────
        """,
                totNodes,  expNodesTot,  totNodes  - expNodesTot,
                totCaps,   expCapsTot,   totCaps   - expCapsTot,
                totQuiets, expQuietsTot, totQuiets - expQuietsTot,
                totChecks, expChecks, totChecks - expChecks,
                secs, (long)(totNodes / Math.max(1e-9, secs)));
    }

    private static final int MAX_PLY  = 64;
    private static final int LIST_CAP = 500;
    private static final int[][] MOVES = new int[MAX_PLY][LIST_CAP];

    private long perft(long[] bb,int depth,int ply,C cnt){
        if (depth == 0){ return 1; }

        boolean stmWhite = (bb[META] & 1L) == 0;
        boolean inCheck  = GEN.kingAttacked(bb, stmWhite);

        int[] moves = MOVES[ply];

        if(!inCheck)
        {
            int caps = GEN.generateChecks(bb, moves, 0);
            for(int i = 0; i < caps; i++)
            {
                if (!POS_FACTORY.makeMoveInPlace(bb, moves[i], GEN)) continue;
                cnt.checks++;
                POS_FACTORY.undoMoveInPlace(bb);
            }
        }

        int legalCnt;

        int caps     = GEN.generateCaptures(bb, moves, 0);
        int total    = GEN.generateQuiets  (bb, moves, caps);

        legalCnt     = total;

        long nodes = 0;
        for (int i = 0; i < legalCnt; ++i){
            int mv = moves[i];

            if (!POS_FACTORY.makeMoveInPlace(bb, mv, GEN)) continue;
            nodes += perft(bb, depth-1, ply+1, cnt);
            if(i < caps)
            {
                cnt.caps++;
            }
            else
            {
                cnt.quiets++;
            }

            POS_FACTORY.undoMoveInPlace(bb);
        }
        return nodes;
    }

    private static String moveToUci(int mv){
        int from  = MoveFactory.GetFrom(mv);
        int to    = MoveFactory.GetTo(mv);
        int type  = MoveFactory.GetFlags(mv);
        int promo = MoveFactory.GetPromotion(mv);
        String s = sq(from) + sq(to);
        if (type == MoveFactory.FLAG_PROMOTION) s += "nbrq".charAt(3 - promo);
        return s;
    }
    private static String sq(int sq){
        return "" + (char)('a'+(sq&7)) + (char)('1'+(sq>>>3));
    }
}