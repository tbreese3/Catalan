package org.engine;

import static org.engine.PositionFactory.META;
import static org.engine.PositionFactory.*;
import static org.engine.PositionFactory.whiteToMove;

public final class MoveGenerator {
  public static final long[] ROOKMASK_PEXT = {
          0x000101010101017EL, 0x000202020202027CL, 0x000404040404047AL, 0x0008080808080876L,
          0x001010101010106EL, 0x002020202020205EL, 0x004040404040403EL, 0x008080808080807EL,
          0x0001010101017E00L, 0x0002020202027C00L, 0x0004040404047A00L, 0x0008080808087600L,
          0x0010101010106E00L, 0x0020202020205E00L, 0x0040404040403E00L, 0x0080808080807E00L,
          0x00010101017E0100L, 0x00020202027C0200L, 0x00040404047A0400L, 0x0008080808760800L,
          0x00101010106E1000L, 0x00202020205E2000L, 0x00404040403E4000L, 0x00808080807E8000L,
          0x000101017E010100L, 0x000202027C020200L, 0x000404047A040400L, 0x0008080876080800L,
          0x001010106E101000L, 0x002020205E202000L, 0x004040403E404000L, 0x008080807E808000L,
          0x0001017E01010100L, 0x0002027C02020200L, 0x0004047A04040400L, 0x0008087608080800L,
          0x0010106E10101000L, 0x0020205E20202000L, 0x0040403E40404000L, 0x0080807E80808000L,
          0x00017E0101010100L, 0x00027C0202020200L, 0x00047A0404040400L, 0x0008760808080800L,
          0x00106E1010101000L, 0x00205E2020202000L, 0x00403E4040404000L, 0x00807E8080808000L,
          0x007E010101010100L, 0x007C020202020200L, 0x007A040404040400L, 0x0076080808080800L,
          0x006E101010101000L, 0x005E202020202000L, 0x003E404040404000L, 0x007E808080808000L,
          0x7E01010101010100L, 0x7C02020202020200L, 0x7A04040404040400L, 0x7608080808080800L,
          0x6E10101010101000L, 0x5E20202020202000L, 0x3E40404040404000L, 0x7E80808080808000L,
  };

  public static final long[] BISHOPMASK_PEXT = {
          0x0040201008040200L, 0x0000402010080400L, 0x0000004020100A00L, 0x0000000040221400L,
          0x0000000002442800L, 0x0000000204085000L, 0x0000020408102000L, 0x0002040810204000L,
          0x0020100804020000L, 0x0040201008040000L, 0x00004020100A0000L, 0x0000004022140000L,
          0x0000000244280000L, 0x0000020408500000L, 0x0002040810200000L, 0x0004081020400000L,
          0x0010080402000200L, 0x0020100804000400L, 0x004020100A000A00L, 0x0000402214001400L,
          0x0000024428002800L, 0x0002040850005000L, 0x0004081020002000L, 0x0008102040004000L,
          0x0008040200020400L, 0x0010080400040800L, 0x0020100A000A1000L, 0x0040221400142200L,
          0x0002442800284400L, 0x0004085000500800L, 0x0008102000201000L, 0x0010204000402000L,
          0x0004020002040800L, 0x0008040004081000L, 0x00100A000A102000L, 0x0022140014224000L,
          0x0044280028440200L, 0x0008500050080400L, 0x0010200020100800L, 0x0020400040201000L,
          0x0002000204081000L, 0x0004000408102000L, 0x000A000A10204000L, 0x0014001422400000L,
          0x0028002844020000L, 0x0050005008040200L, 0x0020002010080400L, 0x0040004020100800L,
          0x0000020408102000L, 0x0000040810204000L, 0x00000A1020400000L, 0x0000142240000000L,
          0x0000284402000000L, 0x0000500804020000L, 0x0000201008040200L, 0x0000402010080400L,
          0x0002040810204000L, 0x0004081020400000L, 0x000A102040000000L, 0x0014224000000000L,
          0x0028440200000000L, 0x0050080402000000L, 0x0020100804020000L, 0x0040201008040200L,
  };

  public static final int[] ROOKOFFSET_PEXT = {
          0, 4160, 6240, 8320,
          10400, 12480, 14560, 16640,
          20800, 22880, 23936, 24992,
          26048, 27104, 28160, 29216,
          31296, 33376, 34432, 35584,
          36736, 37888, 39040, 40096,
          42176, 44256, 45312, 46464,
          48000, 49536, 50688, 51744,
          53824, 55904, 56960, 58112,
          59648, 61184, 62336, 63392,
          65472, 67552, 68608, 69760,
          70912, 72064, 73216, 74272,
          76352, 78432, 79488, 80544,
          81600, 82656, 83712, 84768,
          86848, 91008, 93088, 95168,
          97248, 99328, 101408, 103488,
  };

  public static final int[] BISHOPOFFSET_PEXT = {
          4096, 6208, 8288, 10368,
          12448, 14528, 16608, 20736,
          22848, 23904, 24960, 26016,
          27072, 28128, 29184, 31264,
          33344, 34400, 35456, 36608,
          37760, 38912, 40064, 42144,
          44224, 45280, 46336, 47488,
          49024, 50560, 51712, 53792,
          55872, 56928, 57984, 59136,
          60672, 62208, 63360, 65440,
          67520, 68576, 69632, 70784,
          71936, 73088, 74240, 76320,
          78400, 79456, 80512, 81568,
          82624, 83680, 84736, 86816,
          90944, 93056, 95136, 97216,
          99296, 101376, 103456, 107584,
  };

  public static final long[] SLIDER_PEXT;

  public static final long[] PAWN_ATK_W = new long[64];
  public static final long[] PAWN_ATK_B = new long[64];

  public static final long[] KING_ATK = new long[64];
  public static final long[] KNIGHT_ATK = new long[64];

  static final long FILE_A = 0x0101_0101_0101_0101L;
  static final long FILE_H = FILE_A << 7;

  private static final long RANK_1 = 0xFFL;
  private static final long RANK_2 = RANK_1 << 8;
  private static final long RANK_3 = RANK_1 << 16;
  private static final long RANK_6 = RANK_1 << 40;
  private static final long RANK_7 = RANK_1 << 48;
  private static final long RANK_8 = RANK_1 << 56;

  static {
    try (var in = MoveGenerator.class.getResourceAsStream("/gen/Pext.bin")) {
      if (in == null) throw new IllegalStateException("gen/Pext.bin missing");

      byte[] raw = in.readAllBytes();
      int n = raw.length >>> 3;

      SLIDER_PEXT = new long[n];
      java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).asLongBuffer().get(SLIDER_PEXT);
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }

    for (int sq = 0; sq < 64; ++sq) {
      int r = sq >>> 3, f = sq & 7;
      if (r < 7 && f > 0) PAWN_ATK_W[sq] |= 1L << (sq + 7);
      if (r < 7 && f < 7) PAWN_ATK_W[sq] |= 1L << (sq + 9);
      if (r > 0 && f > 0) PAWN_ATK_B[sq] |= 1L << (sq - 9);
      if (r > 0 && f < 7) PAWN_ATK_B[sq] |= 1L << (sq - 7);
    }
    for (int sq = 0; sq < 64; ++sq) {
      int r = sq >>> 3, f = sq & 7;

      long k = 0;
      for (int dr = -1; dr <= 1; ++dr)
        for (int df = -1; df <= 1; ++df) if ((dr | df) != 0) k = addToMask(k, r + dr, f + df);
      KING_ATK[sq] = k;
      KNIGHT_ATK[sq] = knightMask(r, f);
    }
  }

  private static long addToMask(long m, int r, int f) {
    return (r >= 0 && r < 8 && f >= 0 && f < 8) ? m | (1L << ((r << 3) | f)) : m;
  }

  private static long knightMask(int r, int f) {
    long m = 0;
    int[] dr = {-2, -1, 1, 2, 2, 1, -1, -2};
    int[] df = {1, 2, 2, 1, -1, -2, -2, -1};
    for (int i = 0; i < 8; i++) m = addToMask(m, r + dr[i], f + df[i]);
    return m;
  }

  public int generateCaptures(long[] bb, int[] mv, int n) {
    boolean white = whiteToMove(bb);
    final int usP = white ? WP : BP, usN = white ? WN : BN, usB = white ? WB : BB, usR = white ? WR : BR, usQ = white ? WQ : BQ, usK = white ? WK : BK;
    final long own   = white ? (bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK]) : (bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK]);
    final long enemy = white ? (bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK]) : (bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK]);
    final long occ   = own | enemy;
    final long captMask = enemy;
    final long allCapt  = captMask;

    n  = addPawnCaptures(bb, white, occ, enemy, mv, n, usP);
    n  = addPawnPushes   (bb[usP], white, occ, mv, n, usP, true, false, false, false);
    n  = addKnightMoves(bb[usN], allCapt, mv, n);

    long bishops = bb[usB];
    while (bishops != 0) {
      int from = Long.numberOfTrailingZeros(bishops);
      bishops &= bishops - 1;
      long tgt = bishopAtt(occ, from) & allCapt;
      n = emitSliderMoves(mv, n, from, tgt);
    }

    long rooks = bb[usR];
    while (rooks != 0) {
      int from = Long.numberOfTrailingZeros(rooks);
      rooks &= rooks - 1;
      long tgt = rookAtt(occ, from) & allCapt;
      n = emitSliderMoves(mv, n, from, tgt);
    }

    long queens = bb[usQ];
    while (queens != 0) {
      int from = Long.numberOfTrailingZeros(queens);
      queens &= queens - 1;
      long tgt = queenAtt(occ, from) & allCapt;
      n = emitSliderMoves(mv, n, from, tgt);
    }

    n  = addKingMovesAndCastle(bb, white, occ, 0L, captMask, 0L, mv, n);
    return n;
  }

  public int generateQuiets(long[] bb, int[] mv, int n) {
    boolean white = whiteToMove(bb);
    final int usP = white ? WP : BP, usN = white ? WN : BN, usB = white ? WB : BB, usR = white ? WR : BR, usQ = white ? WQ : BQ, usK = white ? WK : BK;
    final long own   = white ? (bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK]) : (bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK]);
    final long enemy = white ? (bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK]) : (bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK]);
    final long occ   = own | enemy;

    long quietMask = ~occ;
    long allQuiet  = quietMask;

    n = addPawnPushes(bb[usP], white, occ, mv, n, usP, false, true, true,  true);
    n = addKnightMoves(bb[usN], allQuiet, mv, n);

    for (long bishops = bb[usB]; bishops != 0; ) {
      int from = Long.numberOfTrailingZeros(bishops);
      bishops &= bishops - 1;
      long tgt = bishopAtt(occ, from) & allQuiet;
      n = emitSliderMoves(mv, n, from, tgt);
    }
    for (long rooks = bb[usR]; rooks != 0; ) {
      int from = Long.numberOfTrailingZeros(rooks);
      rooks &= rooks - 1;
      long tgt = rookAtt(occ, from) & allQuiet;
      n = emitSliderMoves(mv, n, from, tgt);
    }
    for (long queens = bb[usQ]; queens != 0; ) {
      int from = Long.numberOfTrailingZeros(queens);
      queens &= queens - 1;
      long tgt = queenAtt(occ, from) & allQuiet;
      n = emitSliderMoves(mv, n, from, tgt);
    }

    n = addKingMovesAndCastle(bb, white, occ, 0L, 0L,
            quietMask, mv, n);
    return n;
  }

  public boolean castleLegal(long[] bb, int from, int to) {
    boolean white = from == 4;
    int rookFrom  = white ? (to == 6 ? 7  : 0) : (to == 62 ? 63 : 56);
    long pathMask = to == 6 || to == 62 ? (1L << (from+1)) | (1L << (from+2)) : (1L << (from-1)) | (1L << (from-2)) | (1L << (from-3));

    int rights = (int)((bb[META] & CR_MASK) >>> CR_SHIFT);
    int need = white ? (to == 6 ? 1 : 2) : (to == 62 ? 4 : 8);
    if ( (rights & need) == 0 ) return false;

    long occ =  bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]|bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK];
    if ((occ & pathMask) != 0 || (occ & (1L<<rookFrom)) == 0) return false;

    int  transit = (to == 6 || to == 62) ? from + 1 : from - 1;
    if (squareAttacked(bb, !white, from) || squareAttacked(bb, !white, transit) || squareAttacked(bb, !white, to)) return false;
    return true;
  }

  private boolean squareAttacked(long[] bb, boolean byWhite, int sq) {
    long occ =  bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]|bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK];
    return attackersToSquare(bb, occ, sq, !byWhite) != 0;
  }

  private static int emitSliderMoves(int[] mv, int n, int from, long tgt) {
    while (tgt != 0) {
      int to = Long.numberOfTrailingZeros(tgt);
      tgt &= tgt - 1;
      mv[n++] = MoveFactory.Create(from, to, MoveFactory.FLAG_NORMAL);
    }
    return n;
  }

  private static int addPawnPushes(long pawns, boolean white, long occ, int[] mv, int n, int usP, boolean includeQueenPromo, boolean includeUnderPromo, boolean includeQuietPush, boolean includeDoublePush)
  {
    final int dir = white ? 8 : -8;
    final long one = white ? ((pawns << 8) & ~occ) : ((pawns >>> 8) & ~occ);
    final long PROMO = white ? RANK_8 : RANK_1;

    long promo = one & PROMO;
    while (promo != 0L) {
      int to = Long.numberOfTrailingZeros(promo);
      promo &= promo - 1;
      if (includeQueenPromo && includeUnderPromo)
        n = emitPromotions(mv, n, to - dir, to);
      else if (includeQueenPromo) n = emitQueenPromotion(mv, n, to - dir, to);
      else if (includeUnderPromo) n = emitUnderPromotions(mv, n, to - dir, to);
    }

    if (includeQuietPush) {
      long quiet = one & ~PROMO;
      while (quiet != 0L) {
        int to = Long.numberOfTrailingZeros(quiet);
        quiet &= quiet - 1;
        mv[n++] = MoveFactory.Create(to - dir, to, MoveFactory.FLAG_NORMAL);
      }
    }

    if (includeDoublePush) {
      long rank3 = white ? RANK_3 : RANK_6;
      long two = white ? (((one & rank3) << 8) & ~occ) : (((one & rank3) >>> 8) & ~occ);
      while (two != 0L) {
        int to = Long.numberOfTrailingZeros(two);
        two &= two - 1;
        mv[n++] = MoveFactory.Create(to - 2 * dir, to, MoveFactory.FLAG_NORMAL);
      }
    }
    return n;
  }

  private static int addPawnCaptures(long[] bb, boolean white, long occ, long enemy, int[] mv, int n, int usP) {

    long own = white ? (bb[WP] | bb[WN] | bb[WB] | bb[WR] | bb[WQ] | bb[WK]) : (bb[BP] | bb[BN] | bb[BB] | bb[BR] | bb[BQ] | bb[BK]);
    long legalTargets = enemy & ~own;

    long pawns = bb[usP];
    long PROMO = white ? RANK_8 : RANK_1;

    long capL = white ? ((pawns & ~FILE_A) << 7) : ((pawns & ~FILE_H) >>> 7);
    long capR = white ? ((pawns & ~FILE_H) << 9) : ((pawns & ~FILE_A) >>> 9);

    long promoL = capL & legalTargets & PROMO;
    long promoR = capR & legalTargets & PROMO;
    capL &= legalTargets & ~PROMO;
    capR &= legalTargets & ~PROMO;

    final int dL = white ? -7 : 7;
    final int dR = white ? -9 : 9;

    while (capL != 0) {
      int to = Long.numberOfTrailingZeros(capL);
      capL &= capL - 1;
      mv[n++] = MoveFactory.Create(to + dL, to, MoveFactory.FLAG_NORMAL);
    }
    while (capR != 0) {
      int to = Long.numberOfTrailingZeros(capR);
      capR &= capR - 1;
      mv[n++] = MoveFactory.Create(to + dR, to, MoveFactory.FLAG_NORMAL);
    }
    while (promoL != 0) {
      int to = Long.numberOfTrailingZeros(promoL);
      promoL &= promoL - 1;
      n = emitPromotions(mv, n, to + dL, to);
    }
    while (promoR != 0) {
      int to = Long.numberOfTrailingZeros(promoR);
      promoR &= promoR - 1;
      n = emitPromotions(mv, n, to + dR, to);
    }

    long epSqRaw = (bb[META] & EP_MASK) >>> EP_SHIFT;
    if (epSqRaw != 63) {
      long epBit = 1L << epSqRaw;
      long behind = white ? epBit >>> 8 : epBit << 8;
      if ((enemy & behind) != 0) {
        long epL = white ? ((pawns & ~FILE_A) << 7) & epBit : ((pawns & ~FILE_H) >>> 7) & epBit;
        long epR = white ? ((pawns & ~FILE_H) << 9) & epBit : ((pawns & ~FILE_A) >>> 9) & epBit;

        while (epL != 0) {
          int to = Long.numberOfTrailingZeros(epL);
          epL &= epL - 1;
          mv[n++] = MoveFactory.Create(to + (white ? -7 : 7), to, MoveFactory.FLAG_EN_PASSANT);
        }
        while (epR != 0) {
          int to = Long.numberOfTrailingZeros(epR);
          epR &= epR - 1;
          mv[n++] = MoveFactory.Create(to + (white ? -9 : 9), to, MoveFactory.FLAG_EN_PASSANT);
        }
      }
    }
    return n;
  }

  private static int addKnightMoves(long knights, long targetMask, int[] mv, int n) {
    while (knights != 0) {
      int from = Long.numberOfTrailingZeros(knights);
      knights &= knights - 1;
      long tgt = KNIGHT_ATK[from] & targetMask;
      while (tgt != 0) {
        int to = Long.numberOfTrailingZeros(tgt);
        tgt &= tgt - 1;
        mv[n++] = MoveFactory.Create(from, to, MoveFactory.FLAG_NORMAL);
      }
    }
    return n;
  }

  private static int addKingMovesAndCastle(long[] bb, boolean white, long occ, long enemySeen, long captMask, long quietMask, int[] mv, int n) {

    int usK = white ? WK : BK;
    int kSq = Long.numberOfTrailingZeros(bb[usK]);
    long own = occ & ~captMask;
    long moves = KING_ATK[kSq] & ~own;

    long qs = moves & quietMask;
    while (qs != 0) {
      int to = Long.numberOfTrailingZeros(qs);
      qs &= qs - 1;
      mv[n++] = MoveFactory.Create(kSq, to, MoveFactory.FLAG_NORMAL);
    }

    long cs = moves & captMask;
    while (cs != 0) {
      int to = Long.numberOfTrailingZeros(cs);
      cs &= cs - 1;
      mv[n++] = MoveFactory.Create(kSq, to, MoveFactory.FLAG_NORMAL);
    }

    if (quietMask != 0) {
      int rights = (int) ((bb[META] & CR_MASK) >>> CR_SHIFT);
      if (white) {
        if ((rights & 1) != 0 && ((bb[WR] & (1L << 7)) != 0) && ((occ & 0x60L) == 0)) mv[n++] = MoveFactory.Create(4, 6, MoveFactory.FLAG_CASTLE);
        if ((rights & 2) != 0 && ((bb[WR] & (1L << 0)) != 0) && ((occ & 0x0EL) == 0)) mv[n++] = MoveFactory.Create(4, 2, MoveFactory.FLAG_CASTLE);
      } else {
        if ((rights & 4) != 0 && ((bb[BR] & (1L << 63)) != 0) && ((occ & 0x6000_0000_0000_0000L) == 0)) mv[n++] = MoveFactory.Create(60, 62, MoveFactory.FLAG_CASTLE);
        if ((rights & 8) != 0 && ((bb[BR] & (1L << 56)) != 0) && ((occ & 0x0E00_0000_0000_0000L) == 0)) mv[n++] = MoveFactory.Create(60, 58, MoveFactory.FLAG_CASTLE);
      }
    }
    return n;
  }

  private static long attackersToSquare(long[] bb, long occ, int sq, boolean usIsWhite) {
    boolean enemyWhite = !usIsWhite;
    long atk = 0L, sqBit = 1L << sq;

    atk |= enemyWhite ? bb[WP] & (((sqBit & ~FILE_H) >>> 7) | ((sqBit & ~FILE_A) >>> 9)) : bb[BP] & (((sqBit & ~FILE_H) << 9) | ((sqBit & ~FILE_A) << 7));
    atk |= KNIGHT_ATK[sq] & (enemyWhite ? bb[WN] : bb[BN]);
    atk |= bishopAtt(occ, sq) & (enemyWhite ? (bb[WB] | bb[WQ]) : (bb[BB] | bb[BQ]));
    atk |= rookAtt(occ, sq) & (enemyWhite ? (bb[WR] | bb[WQ]) : (bb[BR] | bb[BQ]));
    atk |= KING_ATK[sq] & (enemyWhite ? bb[WK] : bb[BK]);

    return atk;
  }

  public boolean kingAttacked(long[] bb, boolean whiteSide) {
    long occ =  bb[WP]|bb[WN]|bb[WB]|bb[WR]|bb[WQ]|bb[WK]|bb[BP]|bb[BN]|bb[BB]|bb[BR]|bb[BQ]|bb[BK];
    int kSq = Long.numberOfTrailingZeros(whiteSide ? bb[WK] : bb[BK]);
    return attackersToSquare(bb, occ, kSq, /*usIsWhite=*/whiteSide) != 0;
  }

  private static int emitPromotions(int[] moves, int n, int from, int to) {
    moves[n++] = MoveFactory.Create(from, to, MoveFactory.FLAG_PROMOTION, MoveFactory.PROMOTION_QUEEN);
    moves[n++] = MoveFactory.Create(from, to, MoveFactory.FLAG_PROMOTION, MoveFactory.PROMOTION_ROOK);
    moves[n++] = MoveFactory.Create(from, to, MoveFactory.FLAG_PROMOTION, MoveFactory.PROMOTION_BISHOP);
    moves[n++] = MoveFactory.Create(from, to, MoveFactory.FLAG_PROMOTION, MoveFactory.PROMOTION_KNIGHT);
    return n;
  }

  private static int emitQueenPromotion(int[] mv, int n, int from, int to) {
    mv[n++] = MoveFactory.Create(from, to, 1, 3); // Q only
    return n;
  }

  private static int emitUnderPromotions(int[] mv, int n, int from, int to) {
    mv[n++] = MoveFactory.Create(from, to, MoveFactory.FLAG_PROMOTION, MoveFactory.PROMOTION_ROOK);
    mv[n++] = MoveFactory.Create(from, to, MoveFactory.FLAG_PROMOTION, MoveFactory.PROMOTION_BISHOP);
    mv[n++] = MoveFactory.Create(from, to, MoveFactory.FLAG_PROMOTION, MoveFactory.PROMOTION_KNIGHT);
    return n;
  }

  public static long rookAtt(long occ, int sq) {
    int base = ROOKOFFSET_PEXT[sq];
    long mask = ROOKMASK_PEXT[sq];
    int idx = (int) Long.compress(occ, mask);
    return SLIDER_PEXT[base + idx];
  }

  public static long bishopAtt(long occ, int sq) {
    int base = BISHOPOFFSET_PEXT[sq];
    long mask = BISHOPMASK_PEXT[sq];
    int idx = (int) Long.compress(occ, mask);
    return SLIDER_PEXT[base + idx];
  }

  public static long queenAtt(long occ, int sq) {
    return rookAtt(occ, sq) | bishopAtt(occ, sq);
  }

  public int getFirstLegalMove(long[] bb) {
    int[] mv = new int[256];
    int n;
    boolean inCheck = kingAttacked(bb, whiteToMove(bb));
    n = generateCaptures(bb, mv, 0);
    n = generateQuiets(bb, mv, n);
    if (n == 0) return 0;

    PositionFactory pos = new PositionFactory();
    for (int i = 0; i < n; i++) {
      int move = mv[i];
      if (pos.makeMoveInPlace(bb, move, this)) {
        pos.undoMoveInPlace(bb);
        return move;
      }
    }
    return 0;
  }
}