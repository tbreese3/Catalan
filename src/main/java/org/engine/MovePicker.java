package org.engine;

final class MovePicker {
	private final long[] board;
	private final PositionFactory pos;
	private final MoveGenerator gen;
	private final int[] buffer;
	private final int ttMove;
	private final int killerMove;
	private final boolean includeQuiets;

    private enum Stage { TT, KILLER, CAPTURES, QUIETS, DONE }
    private Stage stage;
	private int index;
	private int count;
	private boolean ttTried;
	private boolean killerTried;

    MovePicker(long[] board, PositionFactory pos, MoveGenerator gen, int[] moveBuffer, int ttMove, int killerMove, boolean includeQuiets) {
		this.board = board;
		this.pos = pos;
		this.gen = gen;
		this.buffer = moveBuffer;
        this.ttMove = MoveFactory.intToMove(ttMove);
        this.killerMove = MoveFactory.intToMove(killerMove);
		this.includeQuiets = includeQuiets;
        this.stage = Stage.TT;
		this.index = 0;
		this.count = 0;
		this.ttTried = false;
		this.killerTried = false;
	}

	private boolean isCaptureOrTactical(int m) {
		int flags = MoveFactory.GetFlags(m);
		if (flags == MoveFactory.FLAG_EN_PASSANT) return true;
		if (flags == MoveFactory.FLAG_PROMOTION) return true;
		if (flags == MoveFactory.FLAG_CASTLE) return false;
		int to = MoveFactory.GetTo(m);
		int target = PositionFactory.pieceAt(board, to);
		return target != -1;
	}

	int next() {
        for (;;) {
            switch (stage) {
                case TT: {
                    stage = Stage.KILLER;
                    if (!ttTried && !MoveFactory.isNone(ttMove)) {
						ttTried = true;
						if (pos.isPseudoLegalMove(board, ttMove, gen)) return ttMove;
					}
					break;
				}
                case KILLER: {
                    stage = Stage.CAPTURES;
					if (!includeQuiets) break;
                    if (!killerTried && !MoveFactory.isNone(killerMove) && killerMove != ttMove) {
						killerTried = true;
                        if (pos.isPseudoLegalMove(board, killerMove, gen)) return killerMove;
					}
					break;
				}
                case CAPTURES: {
					if (count == 0) {
						index = 0;
						count = gen.generateCaptures(board, buffer, 0);
					}
					while (index < count) {
						int m = buffer[index++];
                        m = MoveFactory.intToMove(m);
						if (m == ttMove || m == killerMove) continue;
						return m;
					}
					count = 0;
                    stage = includeQuiets ? Stage.QUIETS : Stage.DONE;
					break;
				}
                case QUIETS: {
					if (count == 0) {
						index = 0;
						count = gen.generateQuiets(board, buffer, 0);
					}
					while (index < count) {
						int m = buffer[index++];
                        m = MoveFactory.intToMove(m);
						if (m == ttMove || m == killerMove) continue;
						return m;
					}
                    stage = Stage.DONE;
					break;
				}
                default:
					return 0;
			}
		}
	}
}
