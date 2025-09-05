package org.core;

import org.chesslib.Board;
import org.chesslib.Piece;
import org.chesslib.Side;
import org.chesslib.Square;
import org.chesslib.move.Move;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple material-only evaluation.
 * Positive scores favor White, negative favor Black.
 */
public final class Eval {
    private final static int[] screluPreCalc = new int[Short.MAX_VALUE - Short.MIN_VALUE + 1];

    static final String networkPath = "/net/network.bin";

    public static final int INPUT_SIZE = 768;
    public static final int HL_SIZE = 2048;
    static final int OUTPUT_BUCKETS = 8;
    private static final int DIVISOR = (32 + OUTPUT_BUCKETS - 1) / OUTPUT_BUCKETS;
    private static final int QA = 255;
    private static final int QB = 64;
    private static final int QAB = QA * QB;
    private static final int FV_SCALE = 400;
    private static final int COLOR = 384;
    private static final int PIECE = 64;

    private static final short[][] L1_WEIGHTS = new short[INPUT_SIZE][HL_SIZE];
    private static final short[] L1_BIASES = new short[HL_SIZE];
    private static final short[][][] L2_WEIGHTS = new short[OUTPUT_BUCKETS][2][HL_SIZE];
    private static final short[] L2_BIASES= new short[OUTPUT_BUCKETS];

    public static class NNUEState {
        public final short[] whiteAcc;
        public final short[] blackAcc;

        public NNUEState() {
            this.whiteAcc = new short[HL_SIZE];
            this.blackAcc = new short[HL_SIZE];
        }
    }

    public static void initializeEval()
    {
        for (int i = Short.MIN_VALUE; i <= Short.MAX_VALUE; i++) {
            screluPreCalc[i - (int) Short.MIN_VALUE] = screlu((short) (i));
        }

        try (InputStream is = Eval.class.getResourceAsStream(networkPath)) {
            try (DataInputStream dis = new DataInputStream(is)) {
                for (int i = 0; i < INPUT_SIZE; i++) {
                    for (int j = 0; j < HL_SIZE; j++) {
                        L1_WEIGHTS[i][j] = Short.reverseBytes(dis.readShort());
                    }
                }
                for (int i = 0; i < HL_SIZE; i++) {
                    L1_BIASES[i] = Short.reverseBytes(dis.readShort());
                }

                for (int k = 0; k < OUTPUT_BUCKETS; k++) {
                    // STM half
                    for (int j = 0; j < HL_SIZE; j++) {
                        L2_WEIGHTS[k][0][j] = Short.reverseBytes(dis.readShort());
                    }
                    // NTM half
                    for (int j = 0; j < HL_SIZE; j++) {
                        L2_WEIGHTS[k][1][j] = Short.reverseBytes(dis.readShort());
                    }
                }

                for (int i = 0; i < OUTPUT_BUCKETS; i++)
                {
                    L2_BIASES[i] = Short.reverseBytes(dis.readShort());
                }

            } catch (IOException e) {
                System.err.println("Failed to open NNUE file");
            }
        } catch (IOException e) {
            System.err.println("Failed to open NNUE file");
        }
    }

    public static void refreshAccumulator(NNUEState nnueState, Board board)
    {
        System.arraycopy(L1_BIASES, 0, nnueState.whiteAcc, 0, HL_SIZE);
        System.arraycopy(L1_BIASES, 0, nnueState.blackAcc, 0, HL_SIZE);

        for (Square sq : Square.values())
        {
            if (!board.getPiece(sq).equals(Piece.NONE))
            {
                for(int i = 0; i < HL_SIZE; i++)
                {
                    nnueState.whiteAcc[i] += L1_WEIGHTS[getIndexWhite(sq, board.getPiece(sq))][i];
                    nnueState.blackAcc[i] += L1_WEIGHTS[getIndexBlack(sq, board.getPiece(sq))][i];
                }
            }
        }
    }

    public static int evaluate(NNUEState nnueState, Board board) {
        boolean whiteToMove = board.getSideToMove() == Side.WHITE;
        int outputBucket = chooseOutputBucket(board);

        short[] stmAcc = whiteToMove ? nnueState.whiteAcc : nnueState.blackAcc;
        short[] oppAcc = whiteToMove ? nnueState.blackAcc : nnueState.whiteAcc;

        short[] stmWeights = L2_WEIGHTS[outputBucket][0];
        short[] oppWeights = L2_WEIGHTS[outputBucket][1];

        long output = 0;
        for (int i = 0; i < HL_SIZE; i++) {
            output += screluPreCalc[stmAcc[i] - (int) Short.MIN_VALUE] * stmWeights[i];
            output += screluPreCalc[oppAcc[i] - (int) Short.MIN_VALUE] * oppWeights[i];
        }

        output /= QA;
        output += L2_BIASES[outputBucket];

        output *= FV_SCALE;
        output /= QAB;

        return (int) output;
    }

    private static int getIndexWhite(Square square, Piece piece)
    {
        return piece.getPieceSide().ordinal() * COLOR + piece.getPieceType().ordinal() * PIECE + square.ordinal();
    }

    private static int getIndexBlack(Square square, Piece piece)
    {
        return (piece.getPieceSide().ordinal() ^ 1) * COLOR+ piece.getPieceType().ordinal() * PIECE + (square.ordinal() ^ 0b111000);
    }

    private static int screlu(short v) {
        int vCalc = Math.max(0, Math.min(v, QA));
        return vCalc * vCalc;
    }

    public static int chooseOutputBucket(Board board)
    {
        return (Long.bitCount(board.getBitboard())- 2) / DIVISOR;
    }
}


