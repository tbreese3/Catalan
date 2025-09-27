package org.engine;

final class SEE {

	private static final int[] PIECE_VALUES = {100, 320, 330, 500, 900, 10000}; // P,N,B,R,Q,K

	private static final long FILE_A = 0x0101010101010101L;
	private static final long FILE_H = 0x8080808080808080L;
	private static final long NOT_FILE_A = ~FILE_A;
	private static final long NOT_FILE_H = ~FILE_H;

	static int see(long[] bb, int move) {
		int from = MoveFactory.GetFrom(move);
		int to = MoveFactory.GetTo(move);
		int flags = MoveFactory.GetFlags(move);

		int moverPiece = PositionFactory.pieceAt(bb, from);
		if (moverPiece == -1) return 0; // should not happen
		int moverType = moverPiece % 6;
		boolean initialStm = PositionFactory.whiteToMove(bb);

		int victimType;
		if (flags == MoveFactory.FLAG_EN_PASSANT) {
			victimType = 0; // pawn
		} else {
			int victim = PositionFactory.pieceAt(bb, to);
			if (victim == -1) return 0; // not a capture
			victimType = victim % 6;
		}

		int[] gain = new int[32];
		int d = 0;
		gain[d] = PIECE_VALUES[victimType];

		long occ = bb[PositionFactory.WP] | bb[PositionFactory.WN] | bb[PositionFactory.WB] | bb[PositionFactory.WR] | bb[PositionFactory.WQ] | bb[PositionFactory.WK]
				| bb[PositionFactory.BP] | bb[PositionFactory.BN] | bb[PositionFactory.BB] | bb[PositionFactory.BR] | bb[PositionFactory.BQ] | bb[PositionFactory.BK];

		// For en passant, remove the captured pawn from occupancy as it is not on 'to'
		if (flags == MoveFactory.FLAG_EN_PASSANT) {
			int capSq = initialStm ? (to - 8) : (to + 8);
			occ ^= (1L << capSq);
		}

		// Remove the first attacker (the moving piece) from occupancy
		occ ^= (1L << from);
		boolean stm = !initialStm; // next to move is the opponent (recapture side)

		while (true) {
			d++;
			gain[d] = PIECE_VALUES[moverType] - gain[d - 1];

			long[] outAttackerBit = new long[1];
			moverType = getLeastValuableAttacker(bb, to, stm, occ, outAttackerBit);
			if (moverType == -1) break;

			occ ^= outAttackerBit[0]; // remove that attacker
			stm = !stm;
		}

		while (--d > 0) {
			gain[d - 1] = -Math.max(-gain[d - 1], gain[d]);
		}
		return gain[0];
	}

	private static int getLeastValuableAttacker(long[] bb, int to, boolean stm, long occ, long[] outAttackerBit) {
		long toBB = 1L << to;
		long attackers;

		// Pawns
		if (stm) { // white attackers
			attackers = (((toBB & NOT_FILE_H) >>> 7) | ((toBB & NOT_FILE_A) >>> 9)) & bb[PositionFactory.WP];
		} else { // black attackers
			attackers = (((toBB & NOT_FILE_A) << 7) | ((toBB & NOT_FILE_H) << 9)) & bb[PositionFactory.BP];
		}
		attackers &= occ;
		if (attackers != 0) {
			outAttackerBit[0] = attackers & -attackers;
			return 0;
		}

		// Knights
		attackers = MoveGenerator.KNIGHT_ATK[to] & (stm ? bb[PositionFactory.WN] : bb[PositionFactory.BN]);
		attackers &= occ;
		if (attackers != 0) {
			outAttackerBit[0] = attackers & -attackers;
			return 1;
		}

		// Bishops
		attackers = MoveGenerator.bishopAtt(occ, to) & (stm ? bb[PositionFactory.WB] : bb[PositionFactory.BB]);
		attackers &= occ;
		if (attackers != 0) {
			outAttackerBit[0] = attackers & -attackers;
			return 2;
		}

		// Rooks
		attackers = MoveGenerator.rookAtt(occ, to) & (stm ? bb[PositionFactory.WR] : bb[PositionFactory.BR]);
		attackers &= occ;
		if (attackers != 0) {
			outAttackerBit[0] = attackers & -attackers;
			return 3;
		}

		// Queens
		attackers = MoveGenerator.queenAtt(occ, to) & (stm ? bb[PositionFactory.WQ] : bb[PositionFactory.BQ]);
		attackers &= occ;
		if (attackers != 0) {
			outAttackerBit[0] = attackers & -attackers;
			return 4;
		}

		// King
		attackers = MoveGenerator.KING_ATK[to] & (stm ? bb[PositionFactory.WK] : bb[PositionFactory.BK]);
		attackers &= occ;
		if (attackers != 0) {
			outAttackerBit[0] = attackers & -attackers;
			return 5;
		}

		outAttackerBit[0] = 0L;
		return -1;
	}
}
