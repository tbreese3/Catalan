package org.engine;

import static org.engine.PositionFactory.*;

/**
 * Static Exchange Evaluation (SEE) for pruning bad captures in quiescence search.
 * This implementation leverages the existing MoveGenerator attack generation.
 */
public final class SEE {
    
    // Piece values in centipawns for SEE calculation  
    private static final int[] PIECE_VALUES = {
        100,   // WP = 0
        320,   // WN = 1  
        330,   // WB = 2
        500,   // WR = 3
        900,   // WQ = 4
        20000, // WK = 5
        100,   // BP = 6
        320,   // BN = 7
        330,   // BB = 8
        500,   // BR = 9
        900,   // BQ = 10
        20000  // BK = 11
    };
    
    private static final MoveGenerator moveGen = new MoveGenerator();
    
    private SEE() {} // Utility class
    
    /**
     * Evaluates whether a capture is likely to be good using Static Exchange Evaluation.
     * 
     * @param board The current board position
     * @param move The capture move to evaluate
     * @param threshold The minimum gain required (usually 0 for equal exchanges)
     * @return true if the capture gains at least the threshold value
     */
    public static boolean seeGE(long[] board, int move, int threshold) {
        int from = MoveFactory.GetFrom(move);
        int to = MoveFactory.GetTo(move);
        int flags = MoveFactory.GetFlags(move);
        int promo = MoveFactory.GetPromotion(move);
        
        // Get the moving piece
        int movingPiece = PositionFactory.pieceAt(board, from);
        if (movingPiece == -1) return false;
        
        // Get the target piece (what we're capturing)
        int targetPiece;
        if (flags == MoveFactory.FLAG_EN_PASSANT) {
            // En passant captures a pawn
            targetPiece = movingPiece < 6 ? BP : WP;
        } else {
            targetPiece = PositionFactory.pieceAt(board, to);
            if (targetPiece == -1) return true; // Not a capture, assume good
        }
        
        // Initial gain is the value of the captured piece
        int gain = PIECE_VALUES[targetPiece];
        
        // Handle promotion
        if (flags == MoveFactory.FLAG_PROMOTION) {
            // Add promotion bonus (difference between promoted piece and pawn)
            int promoPiece = getPromotedPiece(movingPiece < 6, promo);
            gain += PIECE_VALUES[promoPiece] - PIECE_VALUES[movingPiece];
        }
        
        // If initial gain minus our piece value is already below threshold, it's bad
        if (gain - PIECE_VALUES[movingPiece] < threshold) {
            return false;
        }
        
        // Get all pieces
        long occ = board[WP] | board[WN] | board[WB] | board[WR] | board[WQ] | board[WK] |
                   board[BP] | board[BN] | board[BB] | board[BR] | board[BQ] | board[BK];
        
        // Remove the initial attacker
        occ &= ~(1L << from);
        
        // Handle en passant victim removal
        if (flags == MoveFactory.FLAG_EN_PASSANT) {
            int epVictimSq = (movingPiece < 6) ? to - 8 : to + 8;
            occ &= ~(1L << epVictimSq);
        }
        
        // The piece on the target square after initial capture
        int pieceOnTarget = (flags == MoveFactory.FLAG_PROMOTION) ? 
            getPromotedPiece(movingPiece < 6, promo) : movingPiece;
        
        // Side to move after the initial capture
        boolean stm = !(movingPiece < 6);
        
        // Perform static exchange evaluation
        while (true) {
            // Find all attackers to the target square
            long attackers = getAttackers(board, occ, to, !stm);
            if (attackers == 0) break;
            
            // Find the least valuable attacker
            int leastValuableAttacker = findLeastValuableAttacker(board, attackers, stm);
            if (leastValuableAttacker == -1) break;
            
            // Make the capture
            gain = -gain + PIECE_VALUES[pieceOnTarget];
            pieceOnTarget = leastValuableAttacker;
            
            // If it's our turn and the gain is negative, we won't make this capture
            if (stm == (movingPiece < 6) && gain < threshold) {
                return false;
            }
            
            // Remove the attacker and handle x-ray attacks
            int attackerSq = Long.numberOfTrailingZeros(attackers & board[leastValuableAttacker]);
            occ &= ~(1L << attackerSq);
            
            // Add x-ray attackers by updating occupation
            // This is handled automatically by getAttackers in the next iteration
            
            // Switch sides
            stm = !stm;
        }
        
        return gain >= threshold;
    }
    
    /**
     * Get all attackers to a given square using MoveGenerator's existing logic
     */
    private static long getAttackers(long[] board, long occ, int sq, boolean byWhite) {
        long attackers = 0;
        
        // Pawn attacks
        long sqBit = 1L << sq;
        if (byWhite) {
            attackers |= board[WP] & (((sqBit & ~MoveGenerator.FILE_H) >>> 7) | ((sqBit & ~MoveGenerator.FILE_A) >>> 9));
        } else {
            attackers |= board[BP] & (((sqBit & ~MoveGenerator.FILE_H) << 9) | ((sqBit & ~MoveGenerator.FILE_A) << 7));
        }
        
        // Knight attacks
        attackers |= MoveGenerator.KNIGHT_ATK[sq] & (byWhite ? board[WN] : board[BN]);
        
        // Bishop/Queen attacks
        long bishopAttacks = MoveGenerator.bishopAtt(occ, sq);
        attackers |= bishopAttacks & (byWhite ? (board[WB] | board[WQ]) : (board[BB] | board[BQ]));
        
        // Rook/Queen attacks
        long rookAttacks = MoveGenerator.rookAtt(occ, sq);
        attackers |= rookAttacks & (byWhite ? (board[WR] | board[WQ]) : (board[BR] | board[BQ]));
        
        // King attacks
        attackers |= MoveGenerator.KING_ATK[sq] & (byWhite ? board[WK] : board[BK]);
        
        return attackers;
    }
    
    /**
     * Find the least valuable attacker for a given side
     */
    private static int findLeastValuableAttacker(long[] board, long attackers, boolean white) {
        // Check pieces in order of increasing value
        if (white) {
            if ((attackers & board[WP]) != 0) return WP;
            if ((attackers & board[WN]) != 0) return WN;
            if ((attackers & board[WB]) != 0) return WB;
            if ((attackers & board[WR]) != 0) return WR;
            if ((attackers & board[WQ]) != 0) return WQ;
            if ((attackers & board[WK]) != 0) return WK;
        } else {
            if ((attackers & board[BP]) != 0) return BP;
            if ((attackers & board[BN]) != 0) return BN;
            if ((attackers & board[BB]) != 0) return BB;
            if ((attackers & board[BR]) != 0) return BR;
            if ((attackers & board[BQ]) != 0) return BQ;
            if ((attackers & board[BK]) != 0) return BK;
        }
        return -1;
    }
    
    /**
     * Get the promoted piece type
     */
    private static int getPromotedPiece(boolean white, int promotionType) {
        int base = white ? WN : BN;
        return base + promotionType; // N=0, B=1, R=2, Q=3
    }
}
