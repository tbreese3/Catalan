package org.core;

import org.chesslib.Board;
import org.chesslib.Side;
import org.chesslib.move.Move;

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

    private final Board board = new Board();
    private final Search search = new Search();
    private final TimeManager timeManager = new TimeManager();
    private Thread searchThread;

    public static void main(String[] args) throws Exception {
        Eval.initializeEval();
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
                System.out.println("uciok");
            } else if (line.equals("isready")) {
                System.out.println("readyok");
            } else if (line.startsWith("setoption")) {
                // Ignored for now
            } else if (line.equals("ucinewgame")) {
                board.loadFromFen("startpos".equals("startpos") ? board.getContext().getStartFEN() : board.getContext().getStartFEN());
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

    private void handlePosition(String cmd) {
        // Syntax: position [startpos | fen <FEN>] [moves <move1> ...]
        StringTokenizer st = new StringTokenizer(cmd);
        st.nextToken(); // position
        if (!st.hasMoreTokens()) return;
        String token = st.nextToken();
        if ("startpos".equals(token)) {
            board.loadFromFen(board.getContext().getStartFEN());
        } else if ("fen".equals(token)) {
            StringBuilder fen = new StringBuilder();
            // FEN can have 6 parts; read until we hit 'moves' or tokens end
            int parts = 0;
            while (st.hasMoreTokens() && parts < 6) {
                String t = st.nextToken();
                if ("moves".equals(t)) {
                    // push back by rebuilding tokens list
                    List<String> rest = new ArrayList<>();
                    rest.add("moves");
                    while (st.hasMoreTokens()) rest.add(st.nextToken());
                    applyMoves(rest);
                    board.setSideToMove(board.getSideToMove());
                    return;
                }
                if (fen.length() > 0) fen.append(' ');
                fen.append(t);
                parts++;
            }
            board.loadFromFen(fen.toString());
        }
        // Process optional moves
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
            Move move = new Move(mv, board.getSideToMove());
            board.doMove(move);
        }
    }

    private void handleGo(String cmd) {
        // Support: go depth N | wtime T btime T winc I binc I movestogo M | movetime X
        int depth = -1;
        int wtime = -1, btime = -1, winc = 0, binc = 0, movestogo = 0, movetime = 0;
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
                }
            } catch (Exception ignored) {}
        }

        stopSearch();

        final boolean hasTiming = movetime > 0 || wtime >= 0 || btime >= 0;
        if (!hasTiming && depth <= 0) depth = 3; // default fixed depth when no timing is given

        final int fDepth = depth;
        final int fwtime = Math.max(0, wtime);
        final int fbtime = Math.max(0, btime);
        final int fwinc = Math.max(0, winc);
        final int fbinc = Math.max(0, binc);
        final int fmovestogo = Math.max(0, movestogo);
        final int fmovetime = Math.max(0, movetime);

        searchThread = new Thread(() -> {
            Search.Limits limits = new Search.Limits();
            if (hasTiming) {
                TimeManager.TimeAllocation alloc = timeManager.allocate(
                        board.getSideToMove(), fwtime, fbtime, fwinc, fbinc, fmovestogo, fmovetime);
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

                // Score formatting: cp or mate
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
                for (org.chesslib.move.Move m : pv) sb.append(' ').append(m.toString());
                System.out.println(sb.toString());
            });
            if (res.bestMove != null) {
                System.out.println("bestmove " + res.bestMove.toString());
            } else {
                System.out.println("bestmove 0000");
            }
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


