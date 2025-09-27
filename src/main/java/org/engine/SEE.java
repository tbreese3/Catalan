package org.engine;

final class SEE {

	private static final int[] PIECE_VALUES = {100, 320, 330, 500, 900, 20000, 100, 320, 330, 500, 900, 20000};

	static int seeCapture(long[] bb, int move) {
		int from = MoveFactory.GetFrom(move);
		int to = MoveFactory.GetTo(move);
		int flags = MoveFactory.GetFlags(move);
		int promo = MoveFactory.GetPromotion(move);

		boolean usWhite = PositionFactory.whiteToMove(bb);

		int mover = PositionFactory.pieceAt(bb, from);
		if (mover == -1) mover = usWhite ? PositionFactory.WP : PositionFactory.BP;

		int victimIdx;
		boolean isEP = (flags == MoveFactory.FLAG_EN_PASSANT);
		if (isEP) {
			victimIdx = usWhite ? PositionFactory.BP : PositionFactory.WP;
		} else {
			victimIdx = PositionFactory.pieceAt(bb, to);
			if (victimIdx == -1) return 0; // not a capture
		}

		int victimVal = PIECE_VALUES[victimIdx];
		if (victimVal <= 0) return 0;

		long wp = bb[PositionFactory.WP];
		long wn = bb[PositionFactory.WN];
		long wb = bb[PositionFactory.WB];
		long wr = bb[PositionFactory.WR];
		long wq = bb[PositionFactory.WQ];
		long wk = bb[PositionFactory.WK];
		long bp = bb[PositionFactory.BP];
		long bn = bb[PositionFactory.BN];
		long bbB = bb[PositionFactory.BB];
		long br = bb[PositionFactory.BR];
		long bq = bb[PositionFactory.BQ];
		long bk = bb[PositionFactory.BK];

		long occ = (wp|wn|wb|wr|wq|wk|bp|bn|bbB|br|bq|bk);

		int[] gain = new int[128];
		int depth = 0;
		gain[depth] = victimVal;

		int lastPieceVal;
		if (flags == MoveFactory.FLAG_PROMOTION) {
			int promotedIdx = (usWhite ? PositionFactory.WN : PositionFactory.BN) + (promo & 3);
			lastPieceVal = PIECE_VALUES[promotedIdx];
		} else {
			lastPieceVal = PIECE_VALUES[mover];
		}

		// Perform the first capture: remove mover from its square, place on 'to'.
		int fromSq = from;
		if (mover == PositionFactory.WP) wp &= ~(1L << fromSq);
		else if (mover == PositionFactory.WN) wn &= ~(1L << fromSq);
		else if (mover == PositionFactory.WB) wb &= ~(1L << fromSq);
		else if (mover == PositionFactory.WR) wr &= ~(1L << fromSq);
		else if (mover == PositionFactory.WQ) wq &= ~(1L << fromSq);
		else if (mover == PositionFactory.WK) wk &= ~(1L << fromSq);
		else if (mover == PositionFactory.BP) bp &= ~(1L << fromSq);
		else if (mover == PositionFactory.BN) bn &= ~(1L << fromSq);
		else if (mover == PositionFactory.BB) bbB &= ~(1L << fromSq);
		else if (mover == PositionFactory.BR) br &= ~(1L << fromSq);
		else if (mover == PositionFactory.BQ) bq &= ~(1L << fromSq);
		else if (mover == PositionFactory.BK) bk &= ~(1L << fromSq);

		long toBit = 1L << to;
		long fromBit = 1L << fromSq;
		occ &= ~fromBit; // from becomes empty

		// For en passant, also remove the pawn behind 'to'
		if (isEP) {
			int capSq = usWhite ? (to - 8) : (to + 8);
			long capBit = 1L << capSq;
			if (usWhite) bp &= ~capBit; else wp &= ~capBit;
			occ &= ~capBit;
		}

		// Now 'to' is occupied by the mover piece
		if (mover < 6) {
			if (flags == MoveFactory.FLAG_PROMOTION) {
				int promIdx = PositionFactory.WN + (promo & 3);
				if (promIdx == PositionFactory.WN) wn |= toBit;
				else if (promIdx == PositionFactory.WB) wb |= toBit;
				else if (promIdx == PositionFactory.WR) wr |= toBit;
				else wq |= toBit;
			} else {
				if (mover == PositionFactory.WP) wp |= toBit;
				else if (mover == PositionFactory.WN) wn |= toBit;
				else if (mover == PositionFactory.WB) wb |= toBit;
				else if (mover == PositionFactory.WR) wr |= toBit;
				else if (mover == PositionFactory.WQ) wq |= toBit;
				else wk |= toBit;
			}
		} else {
			if (mover == PositionFactory.BP) bp |= toBit;
			else if (mover == PositionFactory.BN) bn |= toBit;
			else if (mover == PositionFactory.BB) bbB |= toBit;
			else if (mover == PositionFactory.BR) br |= toBit;
			else if (mover == PositionFactory.BQ) bq |= toBit;
			else bk |= toBit;
		}
		occ |= toBit; // ensure 'to' is occupied

		boolean sideWhite = !usWhite; // opponent to move to recapture

		while (true) {
			long attackers = sideWhite ? attackersToSquareWhite(occ, to, wp, wn, wb, wr, wq, wk)
					: attackersToSquareBlack(occ, to, bp, bn, bbB, br, bq, bk);
			if (attackers == 0L) break;

			int aSq = leastValuableAttackerSquare(attackers,
					sideWhite ? wp : bp,
					sideWhite ? wn : bn,
					sideWhite ? wb : bbB,
					sideWhite ? wr : br,
					sideWhite ? wq : bq,
					sideWhite ? wk : bk);

			int aPieceVal = attackerPieceValueAtSquare(sideWhite, aSq, wp, wn, wb, wr, wq, wk, bp, bn, bbB, br, bq, bk);
			if (aPieceVal == 0) break;

			depth++;
			if (depth >= gain.length) break;
			gain[depth] = lastPieceVal - gain[depth - 1];

			long aBit = 1L << aSq;
			// move attacker from aSq to 'to': remove from aSq, keep 'to' occupied
			if (sideWhite) {
				if ((wp & aBit) != 0) { wp &= ~aBit; }
				else if ((wn & aBit) != 0) { wn &= ~aBit; }
				else if ((wb & aBit) != 0) { wb &= ~aBit; }
				else if ((wr & aBit) != 0) { wr &= ~aBit; }
				else if ((wq & aBit) != 0) { wq &= ~aBit; }
				else if ((wk & aBit) != 0) { wk &= ~aBit; }
			} else {
				if ((bp & aBit) != 0) { bp &= ~aBit; }
				else if ((bn & aBit) != 0) { bn &= ~aBit; }
				else if ((bbB & aBit) != 0) { bbB &= ~aBit; }
				else if ((br & aBit) != 0) { br &= ~aBit; }
				else if ((bq & aBit) != 0) { bq &= ~aBit; }
				else if ((bk & aBit) != 0) { bk &= ~aBit; }
			}
			occ &= ~aBit;

			lastPieceVal = aPieceVal; // next side will capture this piece
			sideWhite = !sideWhite;
		}

		while (depth > 0) {
			gain[depth - 1] = Math.max(-gain[depth - 1], gain[depth]);
			depth--;
		}
		return gain[0];
	}

	static boolean isBadCapture(long[] bb, int move) {
		return seeCapture(bb, move) < 0;
	}

	private static long attackersToSquareWhite(long occ, int sq, long wp, long wn, long wb, long wr, long wq, long wk) {
		long atk = 0L;
		atk |= MoveGenerator.PAWN_ATK_W[sq] & wp;
		atk |= MoveGenerator.KNIGHT_ATK[sq] & wn;
		atk |= MoveGenerator.bishopAtt(occ, sq) & (wb | wq);
		atk |= MoveGenerator.rookAtt(occ, sq) & (wr | wq);
		atk |= MoveGenerator.KING_ATK[sq] & wk;
		return atk;
	}

	private static long attackersToSquareBlack(long occ, int sq, long bp, long bn, long bbB, long br, long bq, long bk) {
		long atk = 0L;
		atk |= MoveGenerator.PAWN_ATK_B[sq] & bp;
		atk |= MoveGenerator.KNIGHT_ATK[sq] & bn;
		atk |= MoveGenerator.bishopAtt(occ, sq) & (bbB | bq);
		atk |= MoveGenerator.rookAtt(occ, sq) & (br | bq);
		atk |= MoveGenerator.KING_ATK[sq] & bk;
		return atk;
	}

	private static int leastValuableAttackerSquare(long attackers, long p, long n, long b, long r, long q, long k) {
		long m;
		m = attackers & p; if (m != 0) return Long.numberOfTrailingZeros(m);
		m = attackers & n; if (m != 0) return Long.numberOfTrailingZeros(m);
		m = attackers & b; if (m != 0) return Long.numberOfTrailingZeros(m);
		m = attackers & r; if (m != 0) return Long.numberOfTrailingZeros(m);
		m = attackers & q; if (m != 0) return Long.numberOfTrailingZeros(m);
		m = attackers & k; if (m != 0) return Long.numberOfTrailingZeros(m);
		return -1;
	}

	private static int attackerPieceValueAtSquare(boolean white, int sq,
												long wp, long wn, long wb, long wr, long wq, long wk,
												long bp, long bn, long bbB, long br, long bq, long bk) {
		long bit = 1L << sq;
		if (white) {
			if ((wp & bit) != 0) return PIECE_VALUES[PositionFactory.WP];
			if ((wn & bit) != 0) return PIECE_VALUES[PositionFactory.WN];
			if ((wb & bit) != 0) return PIECE_VALUES[PositionFactory.WB];
			if ((wr & bit) != 0) return PIECE_VALUES[PositionFactory.WR];
			if ((wq & bit) != 0) return PIECE_VALUES[PositionFactory.WQ];
			if ((wk & bit) != 0) return PIECE_VALUES[PositionFactory.WK];
		} else {
			if ((bp & bit) != 0) return PIECE_VALUES[PositionFactory.BP];
			if ((bn & bit) != 0) return PIECE_VALUES[PositionFactory.BN];
			if ((bbB & bit) != 0) return PIECE_VALUES[PositionFactory.BB];
			if ((br & bit) != 0) return PIECE_VALUES[PositionFactory.BR];
			if ((bq & bit) != 0) return PIECE_VALUES[PositionFactory.BQ];
			if ((bk & bit) != 0) return PIECE_VALUES[PositionFactory.BK];
		}
		return 0;
	}
}


