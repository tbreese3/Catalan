package org.engine;

import java.util.Arrays;

public final class PositionFactory {
  final static int WP = 0, WN = 1, WB = 2, WR = 3, WQ = 4, WK = 5;
  final static int BP = 6, BN = 7, BB = 8, BR = 9, BQ = 10, BK = 11;
  final static int META = 12;
  final static int DIFF_META = 13;
  final static int DIFF_INFO = 14;

  final static int HASH = 15;
  public static final int MAX_MOVE = 6000;
  final static int COOKIE_SP = 16;
  final static int COOKIE_BASE = 17;
  final static int COOKIE_CAP = MAX_MOVE;
  final static int HIST_SP = COOKIE_BASE + COOKIE_CAP; // number of stored history entries
  final static int HIST_BASE = HIST_SP + 1;            // base index of zobrist history
  final static int HIST_CAP = MAX_MOVE;                // history capacity
  final static int BB_LEN = HIST_BASE + HIST_CAP;

  final static long EP_NONE = 63;
  final static long STM_MASK = 1L;
  final static int CR_SHIFT = 1;
  final static long CR_MASK = 0b1111L << CR_SHIFT;
  final static int EP_SHIFT = 5;
  final static long EP_MASK = 0x3FL << EP_SHIFT;
  final static int HC_SHIFT = 11;
  final static long HC_MASK = 0x7FL << HC_SHIFT;
  final static int FM_SHIFT = 18;
  final static long FM_MASK = 0x1FFL << FM_SHIFT;

  public static final long[][] PIECE_SQUARE = new long[12][64];
  public static final long[]   CASTLING     = new long[16];
  public static final long[]   EP_FILE      = new long[8];
  public static final long     SIDE_TO_MOVE;
  public static final long     LIGHT_SQUARES;
  public static final long     DARK_SQUARES;

  // Reusable buffer for pseudo-legal membership checks to avoid allocations
  private final int[] pseudoBuffer = new int[256];

  private static final short[] CR_MASK_LOST_FROM = new short[64];
  private static final short[] CR_MASK_LOST_TO   = new short[64];
  private static final int CR_BITS = (int) CR_MASK;
  private static final int EP_BITS = (int) EP_MASK;
  private static final int HC_BITS = (int) HC_MASK;

  static {
    Arrays.fill(CR_MASK_LOST_FROM, (short) 0b1111);
    Arrays.fill(CR_MASK_LOST_TO,   (short) 0b1111);

    CR_MASK_LOST_FROM[ 4]  = 0b1100;
    CR_MASK_LOST_FROM[60]  = 0b0011;
    CR_MASK_LOST_FROM[ 7] &= ~0b0001;
    CR_MASK_LOST_FROM[ 0] &= ~0b0010;
    CR_MASK_LOST_FROM[63] &= ~0b0100;
    CR_MASK_LOST_FROM[56] &= ~0b1000;
    CR_MASK_LOST_TO[ 7]  &= ~0b0001;
    CR_MASK_LOST_TO[ 0]  &= ~0b0010;
    CR_MASK_LOST_TO[63]  &= ~0b0100;
    CR_MASK_LOST_TO[56]  &= ~0b1000;

    MersenneTwister32 rnd = new MersenneTwister32(934572);

    for (int p = 0; p < 12; ++p) {
      for (int sq = 0; sq < 64; ++sq) {
        PIECE_SQUARE[p][sq] = rnd.nextLong();
      }
    }

    SIDE_TO_MOVE = rnd.nextLong();

    rnd.nextLong();

    for (int i = 0; i < 16; i++) {
      CASTLING[i] = rnd.nextLong();
    }

    for (int f = 0; f < 8; ++f) {
      EP_FILE[f] = rnd.nextLong();
    }

    long light = 0L;
    for (int sq = 0; sq < 64; ++sq) {
      int r = sq >>> 3, c = sq & 7;
      if (((r + c) & 1) != 0) light |= 1L << sq; // light if (rank+file) is odd (A1 is dark)
    }
    LIGHT_SQUARES = light;
    DARK_SQUARES  = ~light;
  }

  public long[] fromFen(String fen) {
    long[] bb = fenToBitboards(fen);
    bb[COOKIE_SP] = 0;
    bb[DIFF_META] = bb[META];
    bb[DIFF_INFO] = 0;
    bb[HASH] = fullHash(bb);
    bb[HIST_SP] = 1;
    bb[HIST_BASE] = bb[HASH];
    return bb;
  }

  public String toFen(long[] bb)
  {
    StringBuilder sb = new StringBuilder(64);
    for (int rank = 7; rank >= 0; --rank) {
      int empty = 0;
      for (int file = 0; file < 8; ++file) {
        int sq = rank * 8 + file;
        char pc = pieceCharAt(bb, sq);
        if (pc == 0) {
          empty++;
          continue;
        }
        if (empty != 0) {
          sb.append(empty);
          empty = 0;
        }
        sb.append(pc);
      }
      if (empty != 0) sb.append(empty);
      if (rank != 0) sb.append('/');
    }
    sb.append(whiteToMove(bb) ? " w " : " b ");

    int cr = castlingRights(bb);
    sb.append(cr == 0 ? "-" : "")
            .append((cr & 1) != 0 ? "K" : "")
            .append((cr & 2) != 0 ? "Q" : "")
            .append((cr & 4) != 0 ? "k" : "")
            .append((cr & 8) != 0 ? "q" : "");
    sb.append(' ');

    int ep = enPassantSquare(bb);
    if (ep != -1) {
      sb.append((char) ('a' + (ep & 7))).append(1 + (ep >>> 3));
    } else {
      sb.append('-');
    }

    sb.append(' ');
    sb.append(halfmoveClock(bb)).append(' ').append(fullmoveNumber(bb));
    return sb.toString();
  }

  public long zobrist(long[] bb)
  {
    return bb[HASH];
  }

  private char pieceCharAt(long bb[], int sq) {
    for (int i = 0; i < 12; ++i) if ((bb[i] & (1L << sq)) != 0) return "PNBRQKpnbrqk".charAt(i);
    return 0;
  }

  static boolean whiteToMove(long[] bb) {
    return (bb[META] & STM_MASK) == 0;
  }

  public int halfmoveClock(long[] bb) {
    return (int) ((bb[META] & HC_MASK) >>> HC_SHIFT);
  }

  private int fullmoveNumber(long[] bb) {
    return 1 + (int) ((bb[META] & FM_MASK) >>> FM_SHIFT);
  }

  private int castlingRights(long[] bb) {
    return (int) ((bb[META] & CR_MASK) >>> CR_SHIFT);
  }

  private int enPassantSquare(long[] bb) {
    int e = (int) ((bb[META] & EP_MASK) >>> EP_SHIFT);
    return e == EP_NONE ? -1 : e;
  }

  public boolean makeMoveInPlace(long[] bb, int mv, MoveGenerator gen) {
    int from  = MoveFactory.GetFrom(mv);
    int to    = MoveFactory.GetTo(mv);
    int type  = MoveFactory.GetFlags(mv);
    int promo = MoveFactory.GetPromotion(mv);
    int mover = inferMover(bb, from);

    boolean white   = mover < 6;
    long    fromBit = 1L << from;
    long    toBit   = 1L << to;

    if (type == MoveFactory.FLAG_CASTLE && !gen.castleLegal(bb, from, to))
      return false;

    long h        = bb[HASH];
    long oldHash  = h;
    int  metaOld  = (int) bb[META];
    int  oldCR    = (metaOld & CR_BITS) >>> CR_SHIFT;
    int  oldEP    = (metaOld & EP_BITS) >>> EP_SHIFT;

    int sp = (int) bb[COOKIE_SP];
    bb[COOKIE_BASE + sp] =
            (bb[DIFF_META] & 0xFFFF_FFFFL) << 32 |
                    (bb[DIFF_INFO] & 0xFFFF_FFFFL);
    bb[COOKIE_SP] = sp + 1;

    int captured = 15;
    if (type <= MoveFactory.FLAG_PROMOTION) {
      long enemy = white
              ? (bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK])
              : (bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]);
      if ((enemy & toBit) != 0) {
        captured = (bb[white?BP:WP] & toBit)!=0 ? (white?BP:WP) :
                (bb[white?BN:WN] & toBit)!=0 ? (white?BN:WN) :
                        (bb[white?BB:WB] & toBit)!=0 ? (white?BB:WB) :
                                (bb[white?BR:WR] & toBit)!=0 ? (white?BR:WR) :
                                        (bb[white?BQ:WQ] & toBit)!=0 ? (white?BQ:WQ) :
                                                (white?BK:WK);
        bb[captured] &= ~toBit;
        h ^= PIECE_SQUARE[captured][to];
      }
    } else if (type == MoveFactory.FLAG_EN_PASSANT) {
      int capSq   = white ? to - 8 : to + 8;
      captured    = white ? BP : WP;
      bb[captured] &= ~(1L << capSq);
      h ^= PIECE_SQUARE[captured][capSq];
    }

    bb[mover] ^= fromBit;
    h ^= PIECE_SQUARE[mover][from];

    if (type == MoveFactory.FLAG_PROMOTION) {
      int promIdx = (white ? WN : BN) + promo;
      bb[promIdx] |= toBit;
      h ^= PIECE_SQUARE[promIdx][to];
    } else {
      bb[mover]   |= toBit;
      h ^= PIECE_SQUARE[mover][to];
    }

    if (type == MoveFactory.FLAG_CASTLE) switch (to) {
      case  6 -> { bb[WR] ^= (1L<<7)|(1L<<5); h ^= PIECE_SQUARE[WR][7] ^ PIECE_SQUARE[WR][5]; }
      case  2 -> { bb[WR] ^= (1L<<0)|(1L<<3); h ^= PIECE_SQUARE[WR][0] ^ PIECE_SQUARE[WR][3]; }
      case 62 -> { bb[BR] ^= (1L<<63)|(1L<<61);h ^= PIECE_SQUARE[BR][63] ^ PIECE_SQUARE[BR][61];}
      case 58 -> { bb[BR] ^= (1L<<56)|(1L<<59);h ^= PIECE_SQUARE[BR][56] ^ PIECE_SQUARE[BR][59];}
    }

    int meta = metaOld;
    int ep = (int) EP_NONE;

    if ((mover == WP || mover == BP) && ((from ^ to) == 16)) {
      long opponentPawns = white ? bb[BP] : bb[WP];
      boolean canCapture = false;

      if (((to & 7) > 0) && ((opponentPawns & (1L << (to - 1))) != 0)) {
        canCapture = true;
      }

      if (!canCapture && ((to & 7) < 7) && ((opponentPawns & (1L << (to + 1))) != 0)) {
        canCapture = true;
      }

      if (canCapture) {
        ep = white ? from + 8 : from - 8;
      }
    }

    if (ep != oldEP) {
      meta = (meta & ~EP_BITS) | (ep << EP_SHIFT);
      if (oldEP != EP_NONE) h ^= EP_FILE[oldEP & 7];
      if (ep != EP_NONE)    h ^= EP_FILE[ep & 7];
    }

    int cr = oldCR & CR_MASK_LOST_FROM[from] & CR_MASK_LOST_TO[to];
    if (cr != oldCR) {
      meta = (meta & ~CR_BITS) | (cr << CR_SHIFT);
      h ^= CASTLING[oldCR] ^ CASTLING[cr];
    }

    int newHC = ((mover == WP || mover == BP) || captured != 15) ? 0 : ((metaOld & HC_BITS) >>> HC_SHIFT) + 1;
    meta = (meta & ~HC_BITS) | (newHC << HC_SHIFT);

    int fm = (meta >>> FM_SHIFT) & 0x1FF;
    if (!white) fm++;
    meta ^= STM_MASK;
    meta = (meta & ~((int)FM_MASK)) | (fm << FM_SHIFT);
    h ^= SIDE_TO_MOVE;

    bb[DIFF_INFO] = (int) packDiff(from, to, captured, mover, type, promo);
    bb[DIFF_META] = (int) (bb[META] ^ meta);
    bb[META]      = meta;
    bb[HASH]      = h;

    if (gen.kingAttacked(bb, white)) {
      bb[HASH] = oldHash;
      fastUndo(bb);
      bb[COOKIE_SP] = sp;
      long prev = bb[COOKIE_BASE + sp];
      bb[DIFF_INFO] = (int)  prev;
      bb[DIFF_META] = (int) (prev >>> 32);
      return false;
    }
    // push zobrist into history
    int hsp = (int) bb[HIST_SP];
    if (hsp < HIST_CAP) {
      bb[HIST_BASE + hsp] = bb[HASH];
      bb[HIST_SP] = hsp + 1;
    }
    return true;
  }

  public void undoMoveInPlace(long[] bb) {
    long diff   = bb[DIFF_INFO];
    long meta  = bb[DIFF_META];

    long h          = bb[HASH];
    int  metaAfter  = (int) bb[META];
    int  crAfter    = (metaAfter & CR_BITS) >>> CR_SHIFT;
    int  epAfter    = (metaAfter & EP_BITS) >>> EP_SHIFT;

    bb[META] ^= meta;
    int metaBefore = (int) bb[META];
    int crBefore   = (metaBefore & CR_BITS) >>> CR_SHIFT;
    int epBefore   = (metaBefore & EP_BITS) >>> EP_SHIFT;

    h ^= SIDE_TO_MOVE;
    if (crAfter != crBefore) h ^= CASTLING[crAfter] ^ CASTLING[crBefore];

    int from   = dfFrom(diff);
    int to     = dfTo(diff);
    int capIdx = dfCap(diff);
    int mover  = dfMover(diff);
    int type   = dfType(diff);
    int promo  = dfPromo(diff);

    long fromBit = 1L << from;
    long toBit   = 1L << to;

    if (type == MoveFactory.FLAG_PROMOTION) {
      int promIdx = (mover < 6 ? WN : BN) + promo;
      bb[promIdx] ^= toBit;
      bb[mover]   |= fromBit;
      h ^= PIECE_SQUARE[promIdx][to] ^ PIECE_SQUARE[mover][from];
    } else {
      bb[mover] ^= fromBit | toBit;
      h ^= PIECE_SQUARE[mover][to] ^ PIECE_SQUARE[mover][from];
    }

    if (type == MoveFactory.FLAG_CASTLE) { // Castle undo
      switch (to) {
        case  6 -> { bb[WR] ^= (1L<<7)|(1L<<5); h ^= PIECE_SQUARE[WR][7] ^ PIECE_SQUARE[WR][5]; }
        case  2 -> { bb[WR] ^= (1L<<0)|(1L<<3); h ^= PIECE_SQUARE[WR][0] ^ PIECE_SQUARE[WR][3]; }
        case 62 -> { bb[BR] ^= (1L<<63)|(1L<<61);h ^= PIECE_SQUARE[BR][63] ^ PIECE_SQUARE[BR][61];}
        case 58 -> { bb[BR] ^= (1L<<56)|(1L<<59);h ^= PIECE_SQUARE[BR][56] ^ PIECE_SQUARE[BR][59];}
      }
    }

    if (capIdx != 15) {
      int capSq = (type == MoveFactory.FLAG_EN_PASSANT) ? ((mover < 6) ? to - 8 : to + 8) : to;
      bb[capIdx] |= 1L << capSq;
      h ^= PIECE_SQUARE[capIdx][capSq];
    }

    int sp = (int) bb[COOKIE_SP] - 1;
    long ck = bb[COOKIE_BASE + sp];
    bb[COOKIE_SP] = sp;
    bb[DIFF_INFO] = (int)  ck;
    bb[DIFF_META] = (int) (ck >>> 32);

    if (epAfter != epBefore) {
      if (epAfter != EP_NONE)  h ^= EP_FILE[epAfter & 7];
      if (epBefore != EP_NONE) h ^= EP_FILE[epBefore & 7];
    }

    bb[HASH] = h;
    // pop history (keep at least the initial entry)
    int hsp = (int) bb[HIST_SP];
    if (hsp > 1) bb[HIST_SP] = hsp - 1;
  }

  private static int inferMover(long[] bb, int from) {
    int p = pieceAt(bb, from);
    return p != -1 ? p : (whiteToMove(bb) ? WP : BP);
  }

  private static void fastUndo(long[] bb) {
    long diff  = bb[DIFF_INFO];
    long metaΔ = bb[DIFF_META];
    bb[META]  ^= metaΔ;

    int from   = dfFrom(diff);
    int to     = dfTo(diff);
    int capIdx = dfCap(diff);
    int mover  = dfMover(diff);
    int type   = dfType(diff);
    int promo  = dfPromo(diff);

    long fromBit = 1L << from;
    long toBit   = 1L << to;

    if (type == MoveFactory.FLAG_PROMOTION) {
      bb[(mover < 6 ? WN : BN) + promo] ^= toBit;
      bb[(mover < 6) ? WP : BP]        |= fromBit;
    } else {
      bb[mover] ^= fromBit | toBit;
    }

    if (type == MoveFactory.FLAG_CASTLE) switch (to) {
      case  6 -> bb[WR] ^= (1L<<7)  | (1L<<5);
      case  2 -> bb[WR] ^= (1L<<0)  | (1L<<3);
      case 62 -> bb[BR] ^= (1L<<63) | (1L<<61);
      case 58 -> bb[BR] ^= (1L<<56) | (1L<<59);
    }

    if (capIdx != 15) {
      long capMask = (type == MoveFactory.FLAG_EN_PASSANT) ? 1L << ((mover < 6) ? to - 8 : to + 8) : toBit;
      bb[capIdx] |= capMask;
    }
  }

  private static boolean hasEpCaptureStatic(long[] bb, int epSq, boolean whiteToMove) {
    long capturingPawns;

    if (whiteToMove) {
      if ((epSq >>> 3) != 5) return false;
      capturingPawns = bb[WP];
    } else {
      if ((epSq >>> 3) != 2) return false;
      capturingPawns = bb[BP];
    }

    int epFile = epSq & 7;

    if (epFile > 0) {
      int sourceSq = whiteToMove ? (epSq - 9) : (epSq + 7);
      if ((capturingPawns & (1L << sourceSq)) != 0) return true;
    }

    if (epFile < 7) {
      int sourceSq = whiteToMove ? (epSq - 7) : (epSq + 9);
      if ((capturingPawns & (1L << sourceSq)) != 0) return true;
    }

    return false;
  }

  private static long[] fenToBitboards(String fen) {
    long[] bb = new long[BB_LEN];
    String[] parts = fen.trim().split("\\s+");

    String board = parts[0];
    int rank = 7, file = 0;
    for (char c : board.toCharArray()) {
      if (c == '/') {
        rank--;
        file = 0;
        continue;
      }
      if (Character.isDigit(c)) {
        file += c - '0';
        continue;
      }
      int sq = rank * 8 + file++;
      int idx =
              switch (c) {
                case 'P' -> WP; case 'N' -> WN; case 'B' -> WB; case 'R' -> WR; case 'Q' -> WQ; case 'K' -> WK;
                case 'p' -> BP; case 'n' -> BN; case 'b' -> BB; case 'r' -> BR; case 'q' -> BQ; case 'k' -> BK;
                default -> throw new IllegalArgumentException("bad fen piece: " + c);
              };
      bb[idx] |= 1L << sq;
    }

    boolean whiteToMove = parts[1].equals("w");
    long meta = whiteToMove ? 0L : 1L;

    int cr = 0;
    if (parts[2].indexOf('K') >= 0) cr |= 0b0001;
    if (parts[2].indexOf('Q') >= 0) cr |= 0b0010;
    if (parts[2].indexOf('k') >= 0) cr |= 0b0100;
    if (parts[2].indexOf('q') >= 0) cr |= 0b1000;
    meta |= (long) cr << CR_SHIFT;

    int epSq = (int) EP_NONE;
    if (!parts[3].equals("-")) {
      int f = parts[3].charAt(0) - 'a';
      int r = parts[3].charAt(1) - '1';
      int potentialEpSq = r * 8 + f;

      if (hasEpCaptureStatic(bb, potentialEpSq, whiteToMove)) {
        epSq = potentialEpSq;
      }
    }
    meta |= (long) epSq << EP_SHIFT;

    int hc = (parts.length > 4) ? Integer.parseInt(parts[4]) : 0;
    int fm = (parts.length > 5) ? Integer.parseInt(parts[5]) - 1 : 0;
    if (fm < 0) fm = 0;
    meta |= (long) hc << HC_SHIFT;
    meta |= (long) fm << FM_SHIFT;

    bb[META] = meta;
    return bb;
  }

  public long fullHash(long[] bb) {
    long k = 0;
    for (int pc = WP; pc <= BK; ++pc) {
      long bits = bb[pc];
      while (bits != 0) {
        int sq = Long.numberOfTrailingZeros(bits);
        k ^= PIECE_SQUARE[pc][sq];
        bits &= bits - 1;
      }
    }

    if ((bb[META] & STM_MASK) != 0)
      k ^= SIDE_TO_MOVE;

    int cr = (int) ((bb[META] & CR_MASK) >>> CR_SHIFT);
    k ^= CASTLING[cr];

    int ep = (int) ((bb[META] & EP_MASK) >>> EP_SHIFT);
    if (ep != EP_NONE)
      k ^= EP_FILE[ep & 7];

    return k;
  }

  public boolean isDraw(long[] bb) {
    if (isRepetition(bb)) return true;
    if (isInsufficientMaterial(bb)) return true;
    if (halfmoveClock(bb) >= 100) return true;
    return false;
  }

  private boolean isRepetition(long[] bb) { return isRepetition(bb, 3); }

  private boolean isRepetition(long[] bb, int count) {
    int hsp = (int) bb[HIST_SP];
    int i = Math.min(hsp - 1, halfmoveClock(bb));
    if (hsp >= 4) {
      long lastKey = bb[HIST_BASE + hsp - 1];
      int rep = 0;
      for (int x = 4; x <= i; x += 2) {
        long k = bb[HIST_BASE + hsp - x - 1];
        if (k == lastKey && ++rep >= count - 1) return true;
      }
    }
    return false;
  }

  public boolean isInsufficientMaterial(long[] bb) {
    if ( (bb[WQ] | bb[BQ] | bb[WR] | bb[BR]) != 0L ) return false;

    long pawns = bb[WP] | bb[BP];
    if (pawns == 0L) {
      long occ = bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]|bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK];
      long whiteAll = bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK];
      long blackAll = bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK];

      long count = Long.bitCount(occ);
      int whiteCount = Long.bitCount(whiteAll);
      int blackCount = Long.bitCount(blackAll);

      if (count == 4) {
        int whiteBishopCount = Long.bitCount(bb[WB]);
        int blackBishopCount = Long.bitCount(bb[BB]);
        if (whiteCount > 1 && blackCount > 1) {
          boolean wbLight = firstBishopIsLight(bb[WB]);
          boolean bbLight = firstBishopIsLight(bb[BB]);
          return !((whiteBishopCount == 1 && blackBishopCount == 1) && (wbLight != bbLight));
        }
        if (whiteCount == 3 || blackCount == 3) {
          if (whiteBishopCount == 2 && ( (LIGHT_SQUARES & bb[WB]) == 0L || (DARK_SQUARES & bb[WB]) == 0L )) {
            return true;
          } else return blackBishopCount == 2 && ( (LIGHT_SQUARES & bb[BB]) == 0L || (DARK_SQUARES & bb[BB]) == 0L );
        } else {
          return Long.bitCount(bb[WN]) == 2 || Long.bitCount(bb[BN]) == 2;
        }
      } else {
        boolean whiteOnlyKB = ((bb[WK] | bb[WB]) == whiteAll);
        boolean blackOnlyKB = ((bb[BK] | bb[BB]) == blackAll);
        if (whiteOnlyKB && blackOnlyKB) {
          return (((LIGHT_SQUARES & bb[WB]) == 0L) && ((LIGHT_SQUARES & bb[BB]) == 0L))
                  || (((DARK_SQUARES & bb[WB]) == 0L) && ((DARK_SQUARES & bb[BB]) == 0L));
        }
        return count < 4;
      }
    }

    return false;
  }

  private static boolean firstBishopIsLight(long bishops) {
    if (bishops == 0L) return false;
    int sq = Long.numberOfTrailingZeros(bishops);
    int r = sq >>> 3, f = sq & 7;
    return ((r + f) & 1) != 0;
  }

  public boolean isInCheck(long[] bb) {
    MoveGenerator gen = new MoveGenerator();
    return gen.kingAttacked(bb, whiteToMove(bb));
  }

  public boolean hasNonPawnMaterialForSide(long[] bb, boolean white) {
    if (white) {
      return (bb[WN] | bb[WB] | bb[WR] | bb[WQ]) != 0L;
    } else {
      return (bb[BN] | bb[BB] | bb[BR] | bb[BQ]) != 0L;
    }
  }

  public boolean hasNonPawnMaterialForSTM(long[] bb) {
    return hasNonPawnMaterialForSide(bb, whiteToMove(bb));
  }

  public void makeNullMoveInPlace(long[] bb) {
    long h        = bb[HASH];
    int  metaOld  = (int) bb[META];
    int  oldCR    = (metaOld & CR_BITS) >>> CR_SHIFT;
    int  oldEP    = (metaOld & EP_BITS) >>> EP_SHIFT;

    int sp = (int) bb[COOKIE_SP];
    bb[COOKIE_BASE + sp] =
            (bb[DIFF_META] & 0xFFFF_FFFFL) << 32 |
                    (bb[DIFF_INFO] & 0xFFFF_FFFFL);
    bb[COOKIE_SP] = sp + 1;

    int meta = metaOld;

    int ep = (int) EP_NONE;
    if (ep != oldEP) {
      meta = (meta & ~EP_BITS) | (ep << EP_SHIFT);
      if (oldEP != EP_NONE) h ^= EP_FILE[oldEP & 7];
    }

    int oldHC = (metaOld & HC_BITS) >>> HC_SHIFT;
    int newHC = oldHC + 1;
    if (newHC > 0x7F) newHC = 0x7F;
    meta = (meta & ~HC_BITS) | (newHC << HC_SHIFT);

    meta ^= STM_MASK;
    h ^= SIDE_TO_MOVE;

    bb[DIFF_INFO] = 0;
    bb[DIFF_META] = (int) (bb[META] ^ meta);
    bb[META]      = meta;
    bb[HASH]      = h;

    int hsp = (int) bb[HIST_SP];
    if (hsp < HIST_CAP) {
      bb[HIST_BASE + hsp] = bb[HASH];
      bb[HIST_SP] = hsp + 1;
    }
  }

  public void undoNullMoveInPlace(long[] bb) {
    long h          = bb[HASH];
    int  metaAfter  = (int) bb[META];
    int  crAfter    = (metaAfter & CR_BITS) >>> CR_SHIFT;
    int  epAfter    = (metaAfter & EP_BITS) >>> EP_SHIFT;

    bb[META] ^= bb[DIFF_META];
    int metaBefore = (int) bb[META];
    int crBefore   = (metaBefore & CR_BITS) >>> CR_SHIFT;
    int epBefore   = (metaBefore & EP_BITS) >>> EP_SHIFT;

    h ^= SIDE_TO_MOVE;
    if (crAfter != crBefore) h ^= CASTLING[crAfter] ^ CASTLING[crBefore];
    if (epAfter != epBefore) {
      if (epAfter != EP_NONE)  h ^= EP_FILE[epAfter & 7];
      if (epBefore != EP_NONE) h ^= EP_FILE[epBefore & 7];
    }

    int sp = (int) bb[COOKIE_SP] - 1;
    long ck = bb[COOKIE_BASE + sp];
    bb[COOKIE_SP] = sp;
    bb[DIFF_INFO] = (int)  ck;
    bb[DIFF_META] = (int) (ck >>> 32);

    bb[HASH] = h;

    int hsp = (int) bb[HIST_SP];
    if (hsp > 1) bb[HIST_SP] = hsp - 1;
  }

  private static long packDiff(int from, int to, int cap, int mover, int typ, int pro) {
    return (from) | ((long) to << 6) | ((long) cap << 12) | ((long) mover << 16) | ((long) typ << 20) | ((long) pro << 22);
  }

  private static int dfFrom(long d) { return (int) (d & 0x3F); }
  private static int dfTo(long d) { return (int) ((d >>> 6) & 0x3F); }
  private static int dfCap(long d) { return (int) ((d >>> 12) & 0x0F); }
  private static int dfMover(long d) { return (int) ((d >>> 16) & 0x0F); }
  private static int dfType(long d) { return (int) ((d >>> 20) & 0x03); }
  private static int dfPromo(long d) { return (int) ((d >>> 22) & 0x03); }

  public static int pieceAt(long[] bb, int sq) {
    long bit = 1L << sq;
    if ((bb[WP] & bit) != 0) return WP;
    if ((bb[WN] & bit) != 0) return WN;
    if ((bb[WB] & bit) != 0) return WB;
    if ((bb[WR] & bit) != 0) return WR;
    if ((bb[WQ] & bit) != 0) return WQ;
    if ((bb[WK] & bit) != 0) return WK;
    if ((bb[BP] & bit) != 0) return BP;
    if ((bb[BN] & bit) != 0) return BN;
    if ((bb[BB] & bit) != 0) return BB;
    if ((bb[BR] & bit) != 0) return BR;
    if ((bb[BQ] & bit) != 0) return BQ;
    if ((bb[BK] & bit) != 0) return BK;
    return -1;
  }

  public static boolean isQuiet(long[] bb, int mv) {
    int flags = MoveFactory.GetFlags(mv);
    if (flags == MoveFactory.FLAG_PROMOTION || flags == MoveFactory.FLAG_CASTLE) return false;
    if (flags == MoveFactory.FLAG_EN_PASSANT) return false;
    int to = MoveFactory.GetTo(mv);
    return pieceAt(bb, to) == -1;
  }

  public boolean isPseudoLegalMove(long[] bb, int mv, MoveGenerator gen) {
    int m = MoveFactory.intToMove(mv);
    if (MoveFactory.isNone(m)) return false;

    int from = MoveFactory.GetFrom(m);
    int to = MoveFactory.GetTo(m);
    if ((from & ~63) != 0 || (to & ~63) != 0 || from == to) return false;

    int mover = pieceAt(bb, from);
    if (mover == -1) return false;
    boolean white = mover < 6;
    if (white != whiteToMove(bb)) return false;

    int flags = MoveFactory.GetFlags(m);
    int promo = MoveFactory.GetPromotion(m);
    if (flags < MoveFactory.FLAG_NORMAL || flags > MoveFactory.FLAG_CASTLE) return false;

    int toPiece = pieceAt(bb, to);
    if (toPiece != -1 && ((toPiece < 6) == white)) return false; // cannot capture own

    int fromRank = from >>> 3, fromFile = from & 7;
    int toRank = to >>> 3, toFile = to & 7;
    int dr = toRank - fromRank;
    int df = toFile - fromFile;
    int absDf = df < 0 ? -df : df;
    long occ = bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]|bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK];
    long toBit = 1L << to;

    // Handle special flags explicitly
    if (flags == MoveFactory.FLAG_CASTLE) {
      if ((white && mover != WK) || (!white && mover != BK)) return false;
      if (white) { if (from != 4 || (to != 6 && to != 2)) return false; }
      else       { if (from != 60 || (to != 62 && to != 58)) return false; }
      if (toPiece != -1) return false;
      if (gen == null) gen = new MoveGenerator();
      return gen.castleLegal(bb, from, to);
    }

    if (flags == MoveFactory.FLAG_EN_PASSANT) {
      if (mover != (white ? WP : BP)) return false;
      int epSq = (int) ((bb[META] & EP_MASK) >>> EP_SHIFT);
      if (epSq == EP_NONE || epSq != to) return false;
      if (dr != (white ? 1 : -1) || absDf != 1) return false;
      int victimSq = white ? (to - 8) : (to + 8);
      return pieceAt(bb, victimSq) == (white ? BP : WP) && toPiece == -1;
    }

    if (flags == MoveFactory.FLAG_PROMOTION) {
      if (mover != (white ? WP : BP)) return false;
      if (promo < 0 || promo > 3) return false;
      if (fromRank != (white ? 6 : 1)) return false;
      if (toRank != (white ? 7 : 0)) return false;
      if (absDf == 0) {
        if (dr != (white ? 1 : -1)) return false;
        if (toPiece != -1) return false;
        return true;
      } else if (absDf == 1) {
        if (dr != (white ? 1 : -1)) return false;
        if (toPiece == -1) return false;
        return true;
      } else {
        return false;
      }
    }

    // Normal moves, per piece type
    switch (mover) {
      case WP: {
        if (absDf == 0) {
          if (dr == 1 && toPiece == -1) return true;
          if (dr == 2 && fromRank == 1) {
            int mid = from + 8;
            if (pieceAt(bb, mid) == -1 && toPiece == -1) return true;
          }
          return false;
        } else if (absDf == 1 && dr == 1) {
          return toPiece != -1 && toPiece >= 6; // capture
        }
        return false;
      }
      case BP: {
        if (absDf == 0) {
          if (dr == -1 && toPiece == -1) return true;
          if (dr == -2 && fromRank == 6) {
            int mid = from - 8;
            if (pieceAt(bb, mid) == -1 && toPiece == -1) return true;
          }
          return false;
        } else if (absDf == 1 && dr == -1) {
          return toPiece != -1 && toPiece < 6; // capture
        }
        return false;
      }
      case WN: case BN: {
        long mask = MoveGenerator.KNIGHT_ATK[from];
        return (mask & toBit) != 0L;
      }
      case WB: case BB: {
        long att = MoveGenerator.bishopAtt(occ, from);
        return (att & toBit) != 0L;
      }
      case WR: case BR: {
        long att = MoveGenerator.rookAtt(occ, from);
        return (att & toBit) != 0L;
      }
      case WQ: case BQ: {
        long att = MoveGenerator.queenAtt(occ, from);
        return (att & toBit) != 0L;
      }
      case WK: case BK: {
        long mask = MoveGenerator.KING_ATK[from];
        return (mask & toBit) != 0L;
      }
      default:
        return false;
    }
  }
}