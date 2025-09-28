package org.engine;

import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorSpecies;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.engine.MoveFactory.*;
import static org.engine.PositionFactory.*;
import static org.engine.Search.MAX_PLY;

public final class Eval {
  private Eval() {}

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
  private static final short[] L2_BIASES = new short[OUTPUT_BUCKETS];

  private static final VectorSpecies<Short> SHORT_SPECIES = ShortVector.SPECIES_PREFERRED;
  private static final int UPPER_BOUND = SHORT_SPECIES.loopBound(HL_SIZE);

  public static final class NNUEState {
    public int currentAccumulator;
    public final short[][] whiteAccumulator;
    public final short[][] blackAccumulator;

    public NNUEState() {
      this.whiteAccumulator = new short[MAX_PLY][HL_SIZE];
      this.blackAccumulator = new short[MAX_PLY][HL_SIZE];
      currentAccumulator = 0;
    }
  }

  public static void initializeEval() {
    for (int i = Short.MIN_VALUE; i <= Short.MAX_VALUE; i++) {
      screluPreCalc[i - (int) Short.MIN_VALUE] = screlu((short) i);
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

        for (int i = 0; i < HL_SIZE * 2; i++) {
          for (int k = 0; k < OUTPUT_BUCKETS; k++) {
            short v = Short.reverseBytes(dis.readShort());
            if (i < HL_SIZE) {
              L2_WEIGHTS[k][0][i] = v; // STM half
            } else {
              L2_WEIGHTS[k][1][i - HL_SIZE] = v; // NTM half
            }
          }
        }

        for (int i = 0; i < OUTPUT_BUCKETS; i++) {
          L2_BIASES[i] = Short.reverseBytes(dis.readShort());
        }
      } catch (IOException e) {
        System.err.println("Failed to open NNUE file");
      }
    } catch (IOException e) {
      System.err.println("Failed to open NNUE file");
    }
  }

  public static void doMoveAccumulator(NNUEState nnueState, long[] bb, int move) {
    int from = MoveFactory.GetFrom(move);
    int to = MoveFactory.GetTo(move);
    int type = MoveFactory.GetFlags(move);
    int promo = MoveFactory.GetPromotion(move);

    int movingPiece = PositionFactory.pieceAt(bb, from);
    boolean white = movingPiece < 6;

    int prevIdx = nnueState.currentAccumulator;
    int nextIdx = prevIdx + 1;

    short[] prevWhite = nnueState.whiteAccumulator[prevIdx];
    short[] prevBlack = nnueState.blackAccumulator[prevIdx];
    short[] nextWhite = nnueState.whiteAccumulator[nextIdx];
    short[] nextBlack = nnueState.blackAccumulator[nextIdx];

    if (type == MoveFactory.FLAG_CASTLE) {
      int rookFrom, rookTo, rookPiece = white ? WR : BR;
      if (white) {
        if (to == 6) { // white O-O
          rookFrom = 7; rookTo = 5;
        } else { // to == 2 white O-O-O
          rookFrom = 0; rookTo = 3;
        }
      } else {
        if (to == 62) { // black O-O
          rookFrom = 63; rookTo = 61;
        } else { // to == 58 black O-O-O
          rookFrom = 56; rookTo = 59;
        }
      }

      addAddSubSubWeights(
          nextWhite,
          prevWhite,
          L1_WEIGHTS[getIndexWhite(to, movingPiece)],
          L1_WEIGHTS[getIndexWhite(rookTo, rookPiece)],
          L1_WEIGHTS[getIndexWhite(from, movingPiece)],
          L1_WEIGHTS[getIndexWhite(rookFrom, rookPiece)]
      );

      addAddSubSubWeights(
          nextBlack,
          prevBlack,
          L1_WEIGHTS[getIndexBlack(to, movingPiece)],
          L1_WEIGHTS[getIndexBlack(rookTo, rookPiece)],
          L1_WEIGHTS[getIndexBlack(from, movingPiece)],
          L1_WEIGHTS[getIndexBlack(rookFrom, rookPiece)]
      );
    } else if (type == MoveFactory.FLAG_PROMOTION) {
      int promoIdx = (white ? WN : BN) + promo; // 0:N 1:B 2:R 3:Q mapping matches engine
      long enemyOcc = white ? (bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK])
                            : (bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK]);
      boolean isCapture = ((enemyOcc >>> to) & 1L) != 0L;
      if (isCapture) {
        int captured = PositionFactory.pieceAt(bb, to);
        addSubSubWeights(
            nextWhite,
            prevWhite,
            L1_WEIGHTS[getIndexWhite(to, promoIdx)],
            L1_WEIGHTS[getIndexWhite(from, movingPiece)],
            L1_WEIGHTS[getIndexWhite(to, captured)]
        );
        addSubSubWeights(
            nextBlack,
            prevBlack,
            L1_WEIGHTS[getIndexBlack(to, promoIdx)],
            L1_WEIGHTS[getIndexBlack(from, movingPiece)],
            L1_WEIGHTS[getIndexBlack(to, captured)]
        );
      } else {
        addSubWeights(
            nextWhite,
            prevWhite,
            L1_WEIGHTS[getIndexWhite(to, promoIdx)],
            L1_WEIGHTS[getIndexWhite(from, movingPiece)]
        );
        addSubWeights(
            nextBlack,
            prevBlack,
            L1_WEIGHTS[getIndexBlack(to, promoIdx)],
            L1_WEIGHTS[getIndexBlack(from, movingPiece)]
        );
      }
    } else if (type == MoveFactory.FLAG_EN_PASSANT) {
      int capturedSq = white ? (to - 8) : (to + 8);
      int captured = white ? BP : WP;
      addSubSubWeights(
          nextWhite,
          prevWhite,
          L1_WEIGHTS[getIndexWhite(to, movingPiece)],
          L1_WEIGHTS[getIndexWhite(from, movingPiece)],
          L1_WEIGHTS[getIndexWhite(capturedSq, captured)]
      );
      addSubSubWeights(
          nextBlack,
          prevBlack,
          L1_WEIGHTS[getIndexBlack(to, movingPiece)],
          L1_WEIGHTS[getIndexBlack(from, movingPiece)],
          L1_WEIGHTS[getIndexBlack(capturedSq, captured)]
      );
    } else {
      long enemyOcc = white ? (bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK])
                            : (bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK]);
      boolean isCapture = ((enemyOcc >>> to) & 1L) != 0L;
      if (isCapture) {
        int captured = PositionFactory.pieceAt(bb, to);
        addSubSubWeights(
            nextWhite,
            prevWhite,
            L1_WEIGHTS[getIndexWhite(to, movingPiece)],
            L1_WEIGHTS[getIndexWhite(from, movingPiece)],
            L1_WEIGHTS[getIndexWhite(to, captured)]
        );
        addSubSubWeights(
            nextBlack,
            prevBlack,
            L1_WEIGHTS[getIndexBlack(to, movingPiece)],
            L1_WEIGHTS[getIndexBlack(from, movingPiece)],
            L1_WEIGHTS[getIndexBlack(to, captured)]
        );
      } else {
        addSubWeights(
            nextWhite,
            prevWhite,
            L1_WEIGHTS[getIndexWhite(to, movingPiece)],
            L1_WEIGHTS[getIndexWhite(from, movingPiece)]
        );
        addSubWeights(
            nextBlack,
            prevBlack,
            L1_WEIGHTS[getIndexBlack(to, movingPiece)],
            L1_WEIGHTS[getIndexBlack(from, movingPiece)]
        );
      }
    }

    nnueState.currentAccumulator = nextIdx;
  }

  public static void undoMoveAccumulator(NNUEState nnueState) {
    nnueState.currentAccumulator--;
  }

  public static void addSubWeights(short[] accumulatorTo, short[] accumulatorFrom, short[] addWeights, short[] subWeights) {
    for (int i = 0; i < UPPER_BOUND; i += SHORT_SPECIES.length()) {
      var a = ShortVector.fromArray(SHORT_SPECIES, accumulatorFrom, i);
      var b = ShortVector.fromArray(SHORT_SPECIES, addWeights, i);
      var c = ShortVector.fromArray(SHORT_SPECIES, subWeights, i);
      a.add(b).sub(c).intoArray(accumulatorTo, i);
    }
  }

  public static void addSubSubWeights(short[] accumulatorTo, short[] accumulatorFrom, short[] addWeights, short[] subWeights, short[] subWeights2) {
    for (int i = 0; i < UPPER_BOUND; i += SHORT_SPECIES.length()) {
      var a = ShortVector.fromArray(SHORT_SPECIES, accumulatorFrom, i);
      var b = ShortVector.fromArray(SHORT_SPECIES, addWeights, i);
      var c = ShortVector.fromArray(SHORT_SPECIES, subWeights, i);
      var d = ShortVector.fromArray(SHORT_SPECIES, subWeights2, i);
      a.add(b).sub(c).sub(d).intoArray(accumulatorTo, i);
    }
  }

  public static void addAddSubSubWeights(short[] accumulatorTo, short[] accumulatorFrom, short[] addWeights, short[] addWeights2, short[] subWeights, short[] subWeights2) {
    for (int i = 0; i < UPPER_BOUND; i += SHORT_SPECIES.length()) {
      var a = ShortVector.fromArray(SHORT_SPECIES, accumulatorFrom, i);
      var b = ShortVector.fromArray(SHORT_SPECIES, addWeights, i);
      var c = ShortVector.fromArray(SHORT_SPECIES, addWeights2, i);
      var d = ShortVector.fromArray(SHORT_SPECIES, subWeights, i);
      var e = ShortVector.fromArray(SHORT_SPECIES, subWeights2, i);
      a.add(b).add(c).sub(d).sub(e).intoArray(accumulatorTo, i);
    }
  }

  public static void refreshAccumulator(NNUEState nnueState, long[] bb) {
    nnueState.currentAccumulator = 0;
    System.arraycopy(L1_BIASES, 0, nnueState.whiteAccumulator[nnueState.currentAccumulator], 0, HL_SIZE);
    System.arraycopy(L1_BIASES, 0, nnueState.blackAccumulator[nnueState.currentAccumulator], 0, HL_SIZE);

    // Iterate all pieces on board and add their feature vectors to both views
    for (int pc = WP; pc <= BK; ++pc) {
      long bits = bb[pc];
      while (bits != 0) {
        int sq = Long.numberOfTrailingZeros(bits);
        bits &= bits - 1;
        int idxW = getIndexWhite(sq, pc);
        int idxB = getIndexBlack(sq, pc);
        short[] w = L1_WEIGHTS[idxW];
        short[] b = L1_WEIGHTS[idxB];

        // Add weight vectors into accumulators
        short[] accW = nnueState.whiteAccumulator[nnueState.currentAccumulator];
        short[] accB = nnueState.blackAccumulator[nnueState.currentAccumulator];
        for (int i = 0; i < HL_SIZE; i++) {
          accW[i] += w[i];
          accB[i] += b[i];
        }
      }
    }
  }

  public static int evaluate(NNUEState nnueState, long[] bb) {
    int i;
    boolean whiteToMove = PositionFactory.whiteToMove(bb);
    int outputBucket = Eval.chooseOutputBucket(bb);
    short[] stmAccumulator = whiteToMove ? nnueState.whiteAccumulator[nnueState.currentAccumulator] : nnueState.blackAccumulator[nnueState.currentAccumulator];
    short[] oppAccumulator = whiteToMove ? nnueState.blackAccumulator[nnueState.currentAccumulator] : nnueState.whiteAccumulator[nnueState.currentAccumulator];
    short[] stmWeights = L2_WEIGHTS[outputBucket][0];
    short[] oppWeights = L2_WEIGHTS[outputBucket][1];
    int output = 0;
    for (i = 0; i < 2048; ++i) {
      output += screluPreCalc[stmAccumulator[i] - Short.MIN_VALUE] * stmWeights[i];
    }
    for (i = 0; i < 2048; ++i) {
      output += screluPreCalc[oppAccumulator[i] - Short.MIN_VALUE] * oppWeights[i];
    }
    output /= 255;
    output += L2_BIASES[outputBucket];
    output *= 400;
    return output /= 16320;
  }

  private static int getIndexWhite(int square, int piece) {
    int side = (piece < 6) ? 0 : 1;
    int type = piece % 6; // P,N,B,R,Q,K -> 0..5
    return side * COLOR + type * PIECE + square;
  }

  private static int getIndexBlack(int square, int piece) {
    int side = (piece < 6) ? 0 : 1;
    int type = piece % 6;
    return (side ^ 1) * COLOR + type * PIECE + (square ^ 0b111000);
  }

  private static int screlu(short v) {
    int vCalc = Math.max(0, Math.min(v, QA));
    return vCalc * vCalc;
  }

  public static int chooseOutputBucket(long[] bb) {
    long occ = 0L;
    for (int pc = WP; pc <= BK; ++pc) occ |= bb[pc];
    int nonKings = Long.bitCount(occ) - 2;
    int b = nonKings / DIVISOR;
    if (b < 0) b = 0;
    if (b >= OUTPUT_BUCKETS) b = OUTPUT_BUCKETS - 1;
    return b;
  }
}


