package org.engine;

import static org.engine.MoveFactory.GetFlags;
import static org.engine.MoveFactory.GetFrom;
import static org.engine.MoveFactory.GetTo;
import static org.engine.PositionFactory.*;

public final class MovePicker {

    private static final int STAGE_EVASIONS_GEN  = 0;
    private static final int STAGE_EVASIONS_PLAY = 1;
    private static final int STAGE_CAPTURES_GEN  = 2;
    private static final int STAGE_CAPTURES_PLAY = 3;
    private static final int STAGE_QUIETS_GEN    = 4;
    private static final int STAGE_QUIETS_PLAY   = 5;
    private static final int STAGE_DONE          = 6;

	private final long[] board;
	private final MoveGenerator moveGen;
	private final boolean isQsearch;
	private final boolean inCheck;
	private final int ttMove;
	private final int[] moves;
	private final int[] scores;

	private int stage;
	private int size;
	private int index;

	// Simple MVV-LVA values (same for both colors)
	private static final int[] PIECE_VALUES = new int[] {
			100, 320, 330, 500, 900, 20000, // white P N B R Q K
			100, 320, 330, 500, 900, 20000  // black P N B R Q K
	};

	public MovePicker(long[] board, MoveGenerator moveGen, boolean inCheck, boolean isQsearch, int ttMove,
	                 int[] movesBuffer, int[] scoresBuffer) {
		this.board = board;
		this.moveGen = moveGen;
		this.inCheck = inCheck;
		this.isQsearch = isQsearch;
		this.ttMove = ttMove;
		this.moves = movesBuffer;
		this.scores = scoresBuffer;

        if (inCheck) this.stage = STAGE_EVASIONS_GEN; else this.stage = STAGE_CAPTURES_GEN;
		this.size = 0;
		this.index = 0;
	}

	public int next() {
        while (true) {
            switch (stage) {
                case STAGE_EVASIONS_GEN: {
                    index = 0; size = moveGen.generateEvasions(board, moves, 0);
                    if (size > 0) { scoreEvasions(); stage = STAGE_EVASIONS_PLAY; continue; }
                    stage = STAGE_DONE; continue;
                }
                case STAGE_EVASIONS_PLAY: {
                    if (index < size) return selectAndPop();
                    stage = STAGE_DONE; continue;
                }
                case STAGE_CAPTURES_GEN: {
                    index = 0; size = moveGen.generateCaptures(board, moves, 0);
                    if (size > 0) { scoreCaptures(); stage = STAGE_CAPTURES_PLAY; continue; }
                    stage = (isQsearch || inCheck) ? STAGE_DONE : STAGE_QUIETS_GEN; continue;
                }
                case STAGE_CAPTURES_PLAY: {
                    if (index < size) return selectAndPop();
                    stage = (isQsearch || inCheck) ? STAGE_DONE : STAGE_QUIETS_GEN; continue;
                }
                case STAGE_QUIETS_GEN: {
                    index = 0; size = moveGen.generateQuiets(board, moves, 0);
                    if (size > 0) { scoreQuiets(); stage = STAGE_QUIETS_PLAY; continue; }
                    stage = STAGE_DONE; continue;
                }
                case STAGE_QUIETS_PLAY: {
                    if (index < size) return selectAndPop();
                    stage = STAGE_DONE; continue;
                }
                default: return 0;
            }
        }
	}

    private int selectAndPop() {
        int best = index;
        int bestScore = Integer.MIN_VALUE;
        for (int i = index; i < size; i++) {
            if (scores[i] > bestScore) { bestScore = scores[i]; best = i; }
        }
        int tmpS = scores[best]; scores[best] = scores[index]; scores[index] = tmpS;
        int tmpM = moves[best];  moves[best]  = moves[index];  moves[index]  = tmpM;
        return moves[index++];
    }

	private void scoreEvasions() {
		for (int i = 0; i < size; i++) {
			int m = moves[i];
			if (ttMatch(m)) scores[i] = Integer.MAX_VALUE; else scores[i] = 0;
		}
	}

	private void scoreCaptures() {
		boolean stmWhite = whiteToMove(board);
		for (int i = 0; i < size; i++) {
			int m = moves[i];
			if (ttMatch(m)) { scores[i] = Integer.MAX_VALUE; continue; }

			int from = GetFrom(m);
			int to = GetTo(m);
			int flags = GetFlags(m);

			int attacker = PositionFactory.pieceAt(board, from);
			int victim;
			if (flags == MoveFactory.FLAG_EN_PASSANT) {
				victim = stmWhite ? BP : WP;
			} else {
				victim = PositionFactory.pieceAt(board, to);
				if (victim < 0) victim = stmWhite ? BP : WP; // fallback for safety
			}

			int victimVal = (victim >= 0) ? PIECE_VALUES[victim] : 0;
			int attackerVal = (attacker >= 0) ? PIECE_VALUES[attacker] : 0;
			int mvvLva = victimVal * 1024 - attackerVal; // scaled to spread

			if (flags == MoveFactory.FLAG_PROMOTION) {
				mvvLva += 5000; // prefer promotions among captures/attacks
			}
			scores[i] = mvvLva;
		}
	}

	private void scoreQuiets() {
		for (int i = 0; i < size; i++) {
			int m = moves[i];
			scores[i] = ttMatch(m) ? Integer.MAX_VALUE : 0;
		}
	}

	private boolean ttMatch(int m) {
		return ttMove != 0 && MoveFactory.intToMove(ttMove) == MoveFactory.intToMove(m);
	}
}


