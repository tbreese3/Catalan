package org.engine;

final class SEE {

    private static final int[] PIECE_VALUES = {100, 320, 330, 500, 900, 20000, 100, 320, 330, 500, 900, 20000};

    private SEE() {}

    static int see(long[] bb, int move) {
        int from = MoveFactory.GetFrom(move);
        int to = MoveFactory.GetTo(move);
        int flags = MoveFactory.GetFlags(move);

        int promoGain = 0;
        if (flags == MoveFactory.FLAG_PROMOTION) {
            int promo = MoveFactory.GetPromotion(move) & 3;
            int promoPieceIndex = promoToPieceIndex(bb, from, promo);
            int pawnIndex = pawnIndexAtSquare(bb, from);
            if (promoPieceIndex != -1 && pawnIndex != -1) {
                promoGain = PIECE_VALUES[promoPieceIndex] - PIECE_VALUES[pawnIndex];
            }
        }

        int victimPieceIndex;
        if (flags == MoveFactory.FLAG_EN_PASSANT) {
            boolean white = PositionFactory.whiteToMove(bb);
            victimPieceIndex = white ? PositionFactory.BP : PositionFactory.WP;
        } else {
            victimPieceIndex = PositionFactory.pieceAt(bb, to);
        }

        if (victimPieceIndex == -1 && flags != MoveFactory.FLAG_EN_PASSANT) {
            return promoGain;
        }

        int attackerPieceIndex = PositionFactory.pieceAt(bb, from);
        if (attackerPieceIndex == -1) attackerPieceIndex = PositionFactory.whiteToMove(bb) ? PositionFactory.WP : PositionFactory.BP;

        long whiteAll = bb[PositionFactory.WP]|bb[PositionFactory.WN]|bb[PositionFactory.WB]|bb[PositionFactory.WR]|bb[PositionFactory.WQ]|bb[PositionFactory.WK];
        long blackAll = bb[PositionFactory.BP]|bb[PositionFactory.BN]|bb[PositionFactory.BB]|bb[PositionFactory.BR]|bb[PositionFactory.BQ]|bb[PositionFactory.BK];
        long occ = whiteAll | blackAll;

        boolean stmWhite = attackerPieceIndex < 6;

        long[] atksBySide = new long[2];
        atksBySide[0] = attackersTo(bb, occ, to, true);
        atksBySide[1] = attackersTo(bb, occ, to, false);

        long fromBit = 1L << from;
        long toBit = 1L << to;

        if (flags == MoveFactory.FLAG_EN_PASSANT) {
            int capSq = stmWhite ? (to - 8) : (to + 8);
            occ &= ~(1L << capSq);
            if (stmWhite) blackAll &= ~(1L << capSq); else whiteAll &= ~(1L << capSq);
        }

        occ ^= fromBit;

        int[] gains = new int[32];
        int depth = 0;
        int side = stmWhite ? 0 : 1;

        int victimValue = (flags == MoveFactory.FLAG_EN_PASSANT) ? PIECE_VALUES[victimPieceIndex] : (victimPieceIndex == -1 ? 0 : PIECE_VALUES[victimPieceIndex]);
        gains[depth] = victimValue + promoGain;

        long attackersWhite = atksBySide[0];
        long attackersBlack = atksBySide[1];
        if (stmWhite) attackersWhite &= ~fromBit; else attackersBlack &= ~fromBit;

        occ |= toBit;

        int lastCaptured = attackerPieceIndex;

        while (true) {
            side ^= 1;

            long sideAttackers = (side == 0) ? attackersWhite : attackersBlack;
            if (sideAttackers == 0) break;

            int nextAttackerSq = leastValuableAttackerSquare(bb, occ, sideAttackers, to);
            if (nextAttackerSq == -1) break;

            int nextAttackerPiece = PositionFactory.pieceAt(bb, nextAttackerSq);
            if (nextAttackerPiece == -1) nextAttackerPiece = (side == 0) ? PositionFactory.WP : PositionFactory.BP;

            depth++;
            gains[depth] = PIECE_VALUES[lastCaptured] - gains[depth - 1];

            long nextFromBit = 1L << nextAttackerSq;
            occ ^= nextFromBit;

            long discovered = discoveredAttackers(bb, occ, nextAttackerSq, to);
            if (side == 0) attackersWhite = (attackersWhite & ~nextFromBit) | (discovered & whiteAll);
            else           attackersBlack = (attackersBlack & ~nextFromBit) | (discovered & blackAll);

            occ |= toBit;
            lastCaptured = nextAttackerPiece;
        }

        while (depth > 0) {
            gains[depth - 1] = Math.max(-gains[depth - 1], gains[depth]);
            depth--;
        }
        return gains[0];
    }

    private static int promoToPieceIndex(long[] bb, int from, int promo) {
        boolean white = PositionFactory.whiteToMove(bb);
        int base = white ? PositionFactory.WN : PositionFactory.BN;
        return base + promo;
    }

    private static int pawnIndexAtSquare(long[] bb, int sq) {
        if ((bb[PositionFactory.WP] & (1L << sq)) != 0) return PositionFactory.WP;
        if ((bb[PositionFactory.BP] & (1L << sq)) != 0) return PositionFactory.BP;
        return -1;
    }

    private static long attackersTo(long[] bb, long occ, int sq, boolean forWhite) {
        long atk = 0L, sqBit = 1L << sq;
        if (forWhite) {
            atk |= bb[PositionFactory.WP] & (((sqBit & ~MoveGenerator.FILE_H) >>> 7) | ((sqBit & ~MoveGenerator.FILE_A) >>> 9));
            atk |= MoveGenerator.KNIGHT_ATK[sq] & bb[PositionFactory.WN];
            atk |= MoveGenerator.bishopAtt(occ, sq) & (bb[PositionFactory.WB] | bb[PositionFactory.WQ]);
            atk |= MoveGenerator.rookAtt(occ, sq) & (bb[PositionFactory.WR] | bb[PositionFactory.WQ]);
            atk |= MoveGenerator.KING_ATK[sq] & bb[PositionFactory.WK];
        } else {
            atk |= bb[PositionFactory.BP] & (((sqBit & ~MoveGenerator.FILE_H) << 9) | ((sqBit & ~MoveGenerator.FILE_A) << 7));
            atk |= MoveGenerator.KNIGHT_ATK[sq] & bb[PositionFactory.BN];
            atk |= MoveGenerator.bishopAtt(occ, sq) & (bb[PositionFactory.BB] | bb[PositionFactory.BQ]);
            atk |= MoveGenerator.rookAtt(occ, sq) & (bb[PositionFactory.BR] | bb[PositionFactory.BQ]);
            atk |= MoveGenerator.KING_ATK[sq] & bb[PositionFactory.BK];
        }
        return atk;
    }

    private static int leastValuableAttackerSquare(long[] bb, long occ, long attackersMask, int toSq) {
        int sq;
        long mask;
        mask = attackersMask & (bb[PositionFactory.WP] | bb[PositionFactory.BP]);
        if (mask != 0) { sq = Long.numberOfTrailingZeros(mask); return sq; }
        mask = attackersMask & (bb[PositionFactory.WN] | bb[PositionFactory.BN]);
        if (mask != 0) { sq = Long.numberOfTrailingZeros(mask); return sq; }
        mask = attackersMask & (bb[PositionFactory.WB] | bb[PositionFactory.BB]);
        mask = attackersMask & (bb[PositionFactory.WR] | bb[PositionFactory.BR]);
        if (mask != 0) { sq = Long.numberOfTrailingZeros(mask); return sq; }
        mask = attackersMask & (bb[PositionFactory.WQ] | bb[PositionFactory.BQ]);
        if (mask != 0) { sq = Long.numberOfTrailingZeros(mask); return sq; }
        mask = attackersMask & (bb[PositionFactory.WK] | bb[PositionFactory.BK]);
        if (mask != 0) { sq = Long.numberOfTrailingZeros(mask); return sq; }
        return -1;
    }

    private static long discoveredAttackers(long[] bb, long occ, int fromSq, int toSq) {
        long discovered = 0L;
        discovered |= MoveGenerator.bishopAtt(occ, toSq) & (bb[PositionFactory.WB] | bb[PositionFactory.BB] | bb[PositionFactory.WQ] | bb[PositionFactory.BQ]);
        discovered |= MoveGenerator.rookAtt(occ, toSq)   & (bb[PositionFactory.WR] | bb[PositionFactory.BR] | bb[PositionFactory.WQ] | bb[PositionFactory.BQ]);
        return discovered;
    }
}


