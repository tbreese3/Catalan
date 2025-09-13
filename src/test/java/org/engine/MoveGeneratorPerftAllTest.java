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
    private record Vec(String fen,int depth,long expNodes,long expPseudo,
                       long expCaps,long expQuiets,long expEvas) {}

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
                        if (p.length < 7) return;
                        vecs.add(new Vec(
                                p[0].trim(),
                                Integer.parseInt(p[1].replaceAll("\\D","")),
                                Long.parseLong(p[2].replaceAll("\\D","")),
                                Long.parseLong(p[3].replaceAll("\\D","")),
                                Long.parseLong(p[4].replaceAll("\\D","")),
                                Long.parseLong(p[5].replaceAll("\\D","")),
                                Long.parseLong(p[6].replaceAll("\\D",""))));
                    });
        }
        Assertions.assertFalse(vecs.isEmpty(), "qbbAll.txt missing / empty");
    }

    private long totNodes, totPseudo, totCaps, totQuiets, totEvas, timeNs;
    private long expNodesTot, expPseudoTot, expCapsTot, expQuietsTot, expEvasTot;

    Stream<Vec> vecStream(){ return vecs.stream(); }

    private static final class C { long nodes,pseudo,caps,quiets,evas; }

    @ParameterizedTest(name="xPerft {index}")
    @MethodSource("vecStream")
    void perft(Vec v){
        long[] root = PositionFactory.fromFen(v.fen());
        C c = new C();

        long t0 = System.nanoTime();
        long got = perft(root, v.depth(), 0, c);
        timeNs += System.nanoTime() - t0;

        totNodes  += got;
        totPseudo += c.pseudo;
        totCaps   += c.caps;
        totQuiets += c.quiets;
        totEvas   += c.evas;

        Assertions.assertAll(
                () -> Assertions.assertEquals(v.expNodes(),  got,    "nodes"),
                () -> Assertions.assertEquals(v.expPseudo(), c.pseudo,"pseudo"),
                () -> Assertions.assertEquals(v.expCaps(),   c.caps,  "captures"),
                () -> Assertions.assertEquals(v.expQuiets(), c.quiets,"quiets"),
                () -> Assertions.assertEquals(v.expEvas(),   c.evas,  "evasions")
        );
        expNodesTot  += v.expNodes();
        expPseudoTot += v.expPseudo();
        expCapsTot   += v.expCaps();
        expQuietsTot += v.expQuiets();
        expEvasTot   += v.expEvas();
    }

    @AfterAll
    void report() {
        double secs = timeNs / 1_000_000_000.0;

        System.out.printf("""
        ── xPERFT SUMMARY ────────────────────────────────────────────
        counter      actual        expected        Δ
        --------------------------------------------------------------
        nodes   : %,15d  / %,15d  (%+d)
        pseudo  : %,15d  / %,15d  (%+d)
        caps    : %,15d  / %,15d  (%+d)
        quiets  : %,15d  / %,15d  (%+d)
        evas    : %,15d  / %,15d  (%+d)
        --------------------------------------------------------------
        time    : %.3f s   → %,d NPS
        ───────────────────────────────────────────────────────────────
        """,
                totNodes,  expNodesTot,  totNodes  - expNodesTot,
                totPseudo, expPseudoTot, totPseudo - expPseudoTot,
                totCaps,   expCapsTot,   totCaps   - expCapsTot,
                totQuiets, expQuietsTot, totQuiets - expQuietsTot,
                totEvas,   expEvasTot,   totEvas   - expEvasTot,
                secs, (long)(totNodes / Math.max(1e-9, secs)));
    }

    private static final int MAX_PLY  = 64;
    private static final int LIST_CAP = 256;
    private static final int[][] MOVES = new int[MAX_PLY][LIST_CAP];

    private long perft(long[] bb,int depth,int ply,C cnt){
        if (depth == 0){ return 1; }

        boolean stmWhite = (bb[META] & 1L) == 0;
        boolean inCheck  = MoveGenerator.kingAttacked(bb, stmWhite);

        int[] moves = MOVES[ply];
        int legalCnt;

        if (inCheck){
            legalCnt     = MoveGenerator.generateEvasions(bb, moves, 0);
            cnt.evas    += legalCnt;
            cnt.pseudo  += legalCnt;
        } else {
            int caps     = MoveGenerator.generateCaptures(bb, moves, 0);
            int total    = MoveGenerator.generateQuiets  (bb, moves, caps);
            int quiets   = total - caps;

            legalCnt     = total;
            cnt.caps    += caps;
            cnt.quiets  += quiets;
            cnt.pseudo  += total;
        }

        long nodes = 0;
        for (int i = 0; i < legalCnt; ++i){
            int mv = moves[i];

            if (capturesKing(mv, bb, stmWhite))
                throw new AssertionError("Generated king capture: "
                        + moveToUci(mv) + "  FEN " + PositionFactory.toFen(bb));

            if (!PositionFactory.makeMoveInPlace(bb, mv)) continue;
            nodes += perft(bb, depth-1, ply+1, cnt);
            PositionFactory.undoMoveInPlace(bb);
        }
        return nodes;
    }

    private static boolean capturesKing(int mv,long[] bb,boolean stmWhite){
        int to   = mv & 0x3F;
        int enemyK = stmWhite ? BK : WK;
        return (bb[enemyK] & (1L << to)) != 0;
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


