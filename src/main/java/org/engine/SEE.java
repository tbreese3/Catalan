package org.engine;

/**
 * Static Exchange Evaluation (SEE) implementation for pruning bad captures in quiescence search.
 * SEE evaluates the outcome of a series of exchanges on a given square.
 */
public final class SEE {
    
    // Standard piece values in centipawns for SEE calculation
    // Using typical values: Pawn=100, Knight=320, Bishop=330, Rook=500, Queen=900, King=20000
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
    
    // Piece attack directions for sliding pieces
    private static final int[][] ROOK_DIRECTIONS = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
    private static final int[][] BISHOP_DIRECTIONS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int[][] QUEEN_DIRECTIONS = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int[][] KNIGHT_MOVES = {{2, 1}, {2, -1}, {-2, 1}, {-2, -1}, {1, 2}, {1, -2}, {-1, 2}, {-1, -2}};
    private static final int[][] KING_MOVES = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    
    private SEE() {} // Utility class, no instantiation
    
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
            boolean white = PositionFactory.whiteToMove(board);
            targetPiece = white ? PositionFactory.BP : PositionFactory.WP;
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
        
        // If the initial gain is already below threshold minus our piece value, it's bad
        if (gain - PIECE_VALUES[movingPiece] < threshold) {
            return false;
        }
        
        // Simulate the capture
        long allPieces = getAllPieces(board);
        allPieces &= ~(1L << from); // Remove attacker
        if (flags == MoveFactory.FLAG_EN_PASSANT) {
            // Remove the en passant victim
            int epVictimSq = (movingPiece < 6) ? to - 8 : to + 8;
            allPieces &= ~(1L << epVictimSq);
        }
        
        // Find all attackers to the target square
        long attackers = getAttackers(board, to, allPieces);
        
        // Remove the initial attacker from the attacker set
        attackers &= ~(1L << from);
        
        // Determine which side moves next (opposite of the side that just moved)
        boolean stm = !(movingPiece < 6); // Side to move after the capture
        
        // The piece on the target square after the initial capture
        int pieceOnTarget = (flags == MoveFactory.FLAG_PROMOTION) ? 
            getPromotedPiece(movingPiece < 6, promo) : movingPiece;
        
        // Perform static exchange evaluation
        while (attackers != 0) {
            // Find the least valuable attacker for the side to move
            int leastValuableAttacker = findLeastValuableAttacker(board, attackers, stm);
            if (leastValuableAttacker == -1) break;
            
            // Make the capture
            gain = -gain + PIECE_VALUES[pieceOnTarget];
            pieceOnTarget = leastValuableAttacker;
            
            // If it's our turn and the gain is already negative, we won't make this capture
            if (stm == (movingPiece < 6) && gain < threshold) {
                return false;
            }
            
            // Remove the attacker
            int attackerSq = Long.numberOfTrailingZeros(attackers & getPieceBitboard(board, leastValuableAttacker));
            attackers &= ~(1L << attackerSq);
            allPieces &= ~(1L << attackerSq);
            
            // Add any X-ray attackers
            attackers |= getXrayAttackers(board, to, allPieces, attackerSq);
            
            // Switch sides
            stm = !stm;
        }
        
        return gain >= threshold;
    }
    
    /**
     * Get all pieces on the board as a bitboard
     */
    private static long getAllPieces(long[] board) {
        long allPieces = 0;
        for (int i = PositionFactory.WP; i <= PositionFactory.BK; i++) {
            allPieces |= board[i];
        }
        return allPieces;
    }
    
    /**
     * Get all attackers to a given square
     */
    private static long getAttackers(long[] board, int square, long occupied) {
        long attackers = 0;
        int rank = square >>> 3;
        int file = square & 7;
        
        // Check pawn attacks
        if (rank > 0) {
            if (file > 0 && ((board[PositionFactory.WP] >>> (square - 9)) & 1) != 0) {
                attackers |= 1L << (square - 9);
            }
            if (file < 7 && ((board[PositionFactory.WP] >>> (square - 7)) & 1) != 0) {
                attackers |= 1L << (square - 7);
            }
        }
        if (rank < 7) {
            if (file > 0 && ((board[PositionFactory.BP] >>> (square + 7)) & 1) != 0) {
                attackers |= 1L << (square + 7);
            }
            if (file < 7 && ((board[PositionFactory.BP] >>> (square + 9)) & 1) != 0) {
                attackers |= 1L << (square + 9);
            }
        }
        
        // Check knight attacks
        for (int[] move : KNIGHT_MOVES) {
            int newRank = rank + move[0];
            int newFile = file + move[1];
            if (newRank >= 0 && newRank < 8 && newFile >= 0 && newFile < 8) {
                int sq = newRank * 8 + newFile;
                if (((board[PositionFactory.WN] | board[PositionFactory.BN]) >>> sq) & 1) {
                    attackers |= 1L << sq;
                }
            }
        }
        
        // Check sliding piece attacks (bishops, rooks, queens)
        for (int[] dir : BISHOP_DIRECTIONS) {
            int r = rank + dir[0];
            int f = file + dir[1];
            while (r >= 0 && r < 8 && f >= 0 && f < 8) {
                int sq = r * 8 + f;
                if ((occupied >>> sq) & 1) {
                    if (((board[PositionFactory.WB] | board[PositionFactory.BB] | 
                          board[PositionFactory.WQ] | board[PositionFactory.BQ]) >>> sq) & 1) {
                        attackers |= 1L << sq;
                    }
                    break;
                }
                r += dir[0];
                f += dir[1];
            }
        }
        
        for (int[] dir : ROOK_DIRECTIONS) {
            int r = rank + dir[0];
            int f = file + dir[1];
            while (r >= 0 && r < 8 && f >= 0 && f < 8) {
                int sq = r * 8 + f;
                if ((occupied >>> sq) & 1) {
                    if (((board[PositionFactory.WR] | board[PositionFactory.BR] | 
                          board[PositionFactory.WQ] | board[PositionFactory.BQ]) >>> sq) & 1) {
                        attackers |= 1L << sq;
                    }
                    break;
                }
                r += dir[0];
                f += dir[1];
            }
        }
        
        // Check king attacks
        for (int[] move : KING_MOVES) {
            int newRank = rank + move[0];
            int newFile = file + move[1];
            if (newRank >= 0 && newRank < 8 && newFile >= 0 && newFile < 8) {
                int sq = newRank * 8 + newFile;
                if (((board[PositionFactory.WK] | board[PositionFactory.BK]) >>> sq) & 1) {
                    attackers |= 1L << sq;
                }
            }
        }
        
        return attackers;
    }
    
    /**
     * Find the least valuable attacker for a given side
     */
    private static int findLeastValuableAttacker(long[] board, long attackers, boolean white) {
        // Check pieces in order of increasing value
        int[] pieceOrder = white ? 
            new int[]{PositionFactory.WP, PositionFactory.WN, PositionFactory.WB, 
                      PositionFactory.WR, PositionFactory.WQ, PositionFactory.WK} :
            new int[]{PositionFactory.BP, PositionFactory.BN, PositionFactory.BB, 
                      PositionFactory.BR, PositionFactory.BQ, PositionFactory.BK};
        
        for (int piece : pieceOrder) {
            if ((attackers & board[piece]) != 0) {
                return piece;
            }
        }
        
        return -1;
    }
    
    /**
     * Get the bitboard for a specific piece type
     */
    private static long getPieceBitboard(long[] board, int piece) {
        return board[piece];
    }
    
    /**
     * Get X-ray attackers that are revealed after a piece moves
     */
    private static long getXrayAttackers(long[] board, int target, long occupied, int movedFrom) {
        long xrayAttackers = 0;
        int rank = target >>> 3;
        int file = target & 7;
        int fromRank = movedFrom >>> 3;
        int fromFile = movedFrom & 7;
        
        // Check if the moved piece was blocking a sliding attacker
        int dr = Integer.signum(rank - fromRank);
        int df = Integer.signum(file - fromFile);
        
        if (dr != 0 || df != 0) {
            // Continue in the same direction to find X-ray attackers
            int r = fromRank + dr;
            int f = fromFile + df;
            
            while (r >= 0 && r < 8 && f >= 0 && f < 8) {
                int sq = r * 8 + f;
                if ((occupied >>> sq) & 1) {
                    // Check if this is a relevant sliding piece
                    boolean isDiagonal = (dr != 0 && df != 0);
                    if (isDiagonal) {
                        if (((board[PositionFactory.WB] | board[PositionFactory.BB] | 
                              board[PositionFactory.WQ] | board[PositionFactory.BQ]) >>> sq) & 1) {
                            xrayAttackers |= 1L << sq;
                        }
                    } else {
                        if (((board[PositionFactory.WR] | board[PositionFactory.BR] | 
                              board[PositionFactory.WQ] | board[PositionFactory.BQ]) >>> sq) & 1) {
                            xrayAttackers |= 1L << sq;
                        }
                    }
                    break;
                }
                r += dr;
                f += df;
            }
        }
        
        return xrayAttackers;
    }
    
    /**
     * Get the promoted piece type
     */
    private static int getPromotedPiece(boolean white, int promotionType) {
        int base = white ? PositionFactory.WN : PositionFactory.BN;
        return base + promotionType; // N=0, B=1, R=2, Q=3
    }
}
