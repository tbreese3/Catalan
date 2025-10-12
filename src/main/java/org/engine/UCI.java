package org.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

/**
 * Minimal UCI implementation supporting: uci, isready, ucinewgame, position, go depth N, stop, quit.
 */
public class UCI {

    private final PositionFactory pos = new PositionFactory();
    private final long[] board = pos.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"); // replaced on position commands
    private final SPSA spsa = new SPSA();
    private Search search = new Search(spsa);
    private final TimeManager timeManager = new TimeManager();
    private Thread searchThread;

    public static void main(String[] args) throws Exception {
        Eval.initializeEval();
        TranspositionTable.TT.init(8);
        new UCI().loop();
    }

    private void loop() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.equals("uci")) {
                System.out.println("id name Catalan");
                System.out.println("id author Tyler Breese");
                if (Main.SPSA_TUNE_MODE) {
                    System.out.println("option name NMPBase type spin default " + spsa.nmpBase + " min 0 max 10");
                    System.out.println("option name NMPDepthScale100 type spin default " + (int)Math.round(spsa.nmpDepthScale * 100) + " min 0 max 200");
                    System.out.println("option name NMPEvalMargin type spin default " + spsa.nmpEvalMargin + " min 1 max 4000");
                    System.out.println("option name NMPEvalMax type spin default " + spsa.nmpEvalMax + " min 0 max 10");
                    System.out.println("option name LMRBase100 type spin default " + (int)Math.round(spsa.lmrBase * 100) + " min 0 max 300");
                    System.out.println("option name LMRDivisor100 type spin default " + (int)Math.round(spsa.lmrDivisor * 100) + " min 1 max 1000");
                    System.out.println("option name ReverseFUTMaxDepth type spin default " + spsa.reverseFutilityMaxDepth + " min 0 max 8");
                    System.out.println("option name ReverseFUTMarginPerDepth type spin default " + spsa.reverseFutilityMarginPerDepth + " min 0 max 1024");
                    System.out.println("option name FUTMaxDepth type spin default " + spsa.futilityMaxDepth + " min 0 max 8");
                    System.out.println("option name FUTMarginPerDepth type spin default " + spsa.futilityMarginPerDepth + " min 0 max 1024");
                    System.out.println("option name QSeeMargin type spin default " + spsa.qseeMargin + " min -1024 max 1024");
                    System.out.println("option name LMPMaxDepth type spin default " + spsa.lmpMaxDepth + " min 0 max 8");
                    System.out.println("option name LMPBaseThreshold type spin default " + spsa.lmpBaseThreshold + " min 0 max 64");
                    System.out.println("option name LMPPerDepth type spin default " + spsa.lmpPerDepth + " min 0 max 16");
                    System.out.println("option name LMPMarginPerDepth type spin default " + spsa.lmpMarginPerDepth + " min 0 max 1024");
                    System.out.println("option name IIRMinPVDepth type spin default " + spsa.iirMinPVDepth + " min 0 max 16");
                    System.out.println("option name IIRMinCutDepth type spin default " + spsa.iirMinCutDepth + " min 0 max 16");
                    System.out.println("option name SingularMinDepth type spin default " + spsa.singularMinDepth + " min 0 max 32");
                    System.out.println("option name SingularMarginPerDepth type spin default " + spsa.singularMarginPerDepth + " min 0 max 8");
                    System.out.println("option name TMHeuristicsMinDepth type spin default " + spsa.tmHeuristicsMinDepth + " min 0 max 32");
                    System.out.println("option name TMMaxExtensionFactor100 type spin default " + (int)Math.round(spsa.tmMaxExtensionFactor * 100) + " min 100 max 1000");
                    System.out.println("option name TMInstabilityScoreWeight10000 type spin default " + (int)Math.round(spsa.tmInstabilityScoreWeight * 10000) + " min 0 max 1000");
                    System.out.println("option name QFutMargin type spin default " + spsa.qfutMargin + " min -1024 max 2048");
                }
                System.out.println("uciok");
            } else if (line.equals("isready")) {
                System.out.println("readyok");
            } else if (line.startsWith("setoption")) {
                handleSetOption(line);
            } else if (line.equals("ucinewgame")) {
                long[] fresh = pos.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
                System.arraycopy(fresh, 0, board, 0, fresh.length);
                TranspositionTable.TT.clear();
                search = new Search(spsa);
            } else if (line.startsWith("position")) {
                handlePosition(line);
            } else if (line.startsWith("go")) {
                handleGo(line);
            } else if (line.equals("stop")) {
                stopSearch();
            } else if (line.equals("quit")) {
                stopSearch();
                break;
            }
        }
    }

    private void handleSetOption(String cmd) {
        String name = null;
        String value = null;
        StringTokenizer st = new StringTokenizer(cmd);
        st.nextToken(); // setoption
        while (st.hasMoreTokens()) {
            String t = st.nextToken();
            if ("name".equals(t) && st.hasMoreTokens()) {
                StringBuilder nb = new StringBuilder();
                while (st.hasMoreTokens()) {
                    String peek = st.nextToken();
                    if ("value".equals(peek)) {
                        break;
                    }
                    if (nb.length() > 0) nb.append(' ');
                    nb.append(peek);
                }
                name = nb.toString();
                if (name.endsWith(" value")) {
                    name = name.substring(0, name.length() - 6).trim();
                }
            }
            if ("value".equals(t) && st.hasMoreTokens()) {
                value = st.nextToken("");
                if (value != null) value = value.trim();
                break;
            }
        }

        if (name == null || value == null) return;
        try {
            int intVal = Integer.parseInt(value.trim());
            spsa.setByName(name, intVal);
        } catch (Exception ignored) {
            // non-integer values are ignored for these options
        }
    }

    private void handlePosition(String cmd) {
        StringTokenizer st = new StringTokenizer(cmd);
        st.nextToken();
        if (!st.hasMoreTokens()) return;
        String token = st.nextToken();
        long[] tmp;
        if ("startpos".equals(token)) {
            tmp = pos.fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        } else if ("fen".equals(token)) {
            StringBuilder fen = new StringBuilder();
            int parts = 0;
            while (st.hasMoreTokens() && parts < 6) {
                String t = st.nextToken();
                if ("moves".equals(t)) {
                    break;
                }
                if (fen.length() > 0) fen.append(' ');
                fen.append(t);
                parts++;
            }
            tmp = pos.fromFen(fen.toString());
        } else {
            return;
        }
        System.arraycopy(tmp, 0, board, 0, tmp.length);

        List<String> rest = new ArrayList<>();
        while (st.hasMoreTokens()) rest.add(st.nextToken());
        applyMoves(rest);
    }

    private void applyMoves(List<String> tokens) {
        if (tokens.isEmpty()) return;
        int idx = 0;
        if (idx < tokens.size() && "moves".equals(tokens.get(idx))) idx++;
        for (; idx < tokens.size(); idx++) {
            String mv = tokens.get(idx);
            int move = uciToMove(mv);
            if (move != 0) pos.makeMoveInPlace(board, move, new org.engine.MoveGenerator());
        }
    }

    private int uciToMove(String uci) {
        if (uci == null || uci.length() < 4) return 0;
        int from = (uci.charAt(0) - 'a') + 8 * (uci.charAt(1) - '1');
        int to   = (uci.charAt(2) - 'a') + 8 * (uci.charAt(3) - '1');

        int flags = MoveFactory.FLAG_NORMAL;
        int promo = 0;

        if (uci.length() >= 5) {
            char p = Character.toLowerCase(uci.charAt(4));
            if (p == 'n') promo = 0;
            else if (p == 'b') promo = 1;
            else if (p == 'r') promo = 2;
            else if (p == 'q') promo = 3;
            flags = MoveFactory.FLAG_PROMOTION;
        }

        // Determine moving piece from current board to disambiguate special moves
        int mover = PositionFactory.pieceAt(board, from);

        // Castling (expressed as e1g1, e1c1, e8g8, e8c8 in UCI)
        if (flags == MoveFactory.FLAG_NORMAL) {
            if (mover == PositionFactory.WK && from == 4 && (to == 6 || to == 2)) {
                flags = MoveFactory.FLAG_CASTLE;
            } else if (mover == PositionFactory.BK && from == 60 && (to == 62 || to == 58)) {
                flags = MoveFactory.FLAG_CASTLE;
            } else if (mover == PositionFactory.WP || mover == PositionFactory.BP) {
                // En passant: diagonal pawn move to empty square that matches EP square in meta
                int df = Math.abs((to & 7) - (from & 7));
                int dr = Math.abs((to >>> 3) - (from >>> 3));
                if (df == 1 && dr == 1 && PositionFactory.pieceAt(board, to) == -1) {
                    int epRaw = (int) ((board[PositionFactory.META] & PositionFactory.EP_MASK) >>> PositionFactory.EP_SHIFT);
                    if (epRaw != PositionFactory.EP_NONE && epRaw == to) {
                        flags = MoveFactory.FLAG_EN_PASSANT;
                    }
                }
            }
        }

        return MoveFactory.Create(from, to, flags, promo);
    }

    private void handleGo(String cmd) {
        // Support: go depth N | wtime T btime T winc I binc I movestogo M | movetime X
        int depth = -1;
        int wtime = -1, btime = -1, winc = 0, binc = 0, movestogo = 0, movetime = 0;
        boolean ponder = false;
        boolean infinite = false;
        StringTokenizer st = new StringTokenizer(cmd);
        st.nextToken(); // go
        while (st.hasMoreTokens()) {
            String t = st.nextToken();
            try {
                if ("depth".equals(t) && st.hasMoreTokens()) {
                    depth = Integer.parseInt(st.nextToken());
                } else if ("wtime".equals(t) && st.hasMoreTokens()) {
                    wtime = Integer.parseInt(st.nextToken());
                } else if ("btime".equals(t) && st.hasMoreTokens()) {
                    btime = Integer.parseInt(st.nextToken());
                } else if ("winc".equals(t) && st.hasMoreTokens()) {
                    winc = Integer.parseInt(st.nextToken());
                } else if ("binc".equals(t) && st.hasMoreTokens()) {
                    binc = Integer.parseInt(st.nextToken());
                } else if ("movestogo".equals(t) && st.hasMoreTokens()) {
                    movestogo = Integer.parseInt(st.nextToken());
                } else if ("movetime".equals(t) && st.hasMoreTokens()) {
                    movetime = Integer.parseInt(st.nextToken());
                } else if ("ponder".equals(t)) {
                    ponder = true;
                } else if ("infinite".equals(t)) {
                    infinite = true;
                }
            } catch (Exception ignored) {}
        }

        stopSearch();

        final boolean hasTiming = movetime > 0 || wtime >= 0 || btime >= 0 || ponder || infinite;
        if (!hasTiming && depth <= 0) depth = 3; // default fixed depth when no timing is given

        final int fDepth = depth;
        final int fwtime = Math.max(0, wtime);
        final int fbtime = Math.max(0, btime);
        final int fwinc = Math.max(0, winc);
        final int fbinc = Math.max(0, binc);
        final int fmovestogo = Math.max(0, movestogo);
        final int fmovetime = Math.max(0, movetime);
        final boolean fPonder = ponder;
        final boolean fInfinite = infinite;

        searchThread = new Thread(() -> {
            Search.Limits limits = new Search.Limits();
            if (hasTiming) {
                boolean whiteToMove = (board[12] & 1L) == 0L;
                TimeManager.TimeAllocation alloc;
                if (fInfinite || fPonder) {
                    alloc = new TimeManager.TimeAllocation(Long.MAX_VALUE, Long.MAX_VALUE);
                } else {
                    alloc = timeManager.allocate(whiteToMove, fwtime, fbtime, fwinc, fbinc, fmovestogo, fmovetime);
                }
                limits.softMs = alloc.soft();
                limits.hardMs = alloc.maximum();
            } else {
                limits.depth = fDepth;
            }
            Search.Result res = search.search(board, limits, (depthInfo, seldepth, nodes, nps, hashfull, scoreCp, timeMs, pv) -> {
                StringBuilder sb = new StringBuilder();
                sb.append("info depth ").append(depthInfo)
                        .append(" seldepth ").append(seldepth)
                        .append(" nodes ").append(nodes)
                        .append(" nps ").append(nps)
                        .append(" hashfull ").append(hashfull);

                int abs = Math.abs(scoreCp);
                final int MATE_VAL = 32000;
                if (abs > MATE_VAL - 1000) {
                    int plies = MATE_VAL - abs;
                    int movesToMate = (plies + 1) / 2;
                    int mateOut = scoreCp > 0 ? movesToMate : -movesToMate;
                    sb.append(" score mate ").append(mateOut);
                } else {
                    sb.append(" score cp ").append(scoreCp);
                }

                sb.append(" wdl ").append("0 0 0")
                        .append(" time ").append(timeMs)
                        .append(" pv");
                for (int m : pv) sb.append(' ').append(org.engine.MoveFactory.moveToUci(m));
                System.out.println(sb.toString());
            });
            int best = res.bestMove;
            
            if (best == 0) {
                MoveGenerator mg = new MoveGenerator();
                best = mg.getFirstLegalMove(board);
            }

            System.out.println("bestmove " + org.engine.MoveFactory.moveToUci(best));
        }, "search-thread");
        searchThread.setDaemon(true);
        searchThread.start();
    }

    private void stopSearch() {
        if (searchThread != null && searchThread.isAlive()) {
            search.stop();
            try {
                searchThread.join(50);
            } catch (InterruptedException ignored) {}
        }
    }
}


