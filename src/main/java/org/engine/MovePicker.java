package org.engine;

final class MovePicker {
	private final long[] board;
	private final PositionFactory pos;
	private final MoveGenerator gen;
	private final int[] buffer;
	private final int ttMove;
	private final int killerMove;
	private final boolean isQSearch;
	private final boolean genChecks;

	private enum Stage { TT, KILLER, CAPTURES, QUIETS, CHECKS, DONE }
    private Stage stage;
	private int index;
	private int count;
	private boolean ttTried;
	private boolean killerTried;
	private boolean inCheck;

	public MovePicker(long[] board, PositionFactory pos, MoveGenerator gen, int[] moveBuffer, int ttMove, int killerMove, boolean isQSearch, boolean genChecks, boolean inCheck) {
		this.board = board;
		this.pos = pos;
		this.gen = gen;
		this.buffer = moveBuffer;
        this.ttMove = MoveFactory.intToMove(ttMove);
        this.killerMove = MoveFactory.intToMove(killerMove);
		this.isQSearch = isQSearch;
		this.genChecks = genChecks;
		this.inCheck = inCheck;
        this.stage = Stage.TT;
		this.index = 0;
		this.count = 0;
		this.ttTried = false;
		this.killerTried = false;
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
					if (isQSearch || inCheck) break;
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
					if(inCheck || !isQSearch)
					{
						stage = Stage.QUIETS;
					}
					else {
						if (genChecks) stage = Stage.CHECKS;
						else stage = Stage.DONE;
					}
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
					count = 0;
					stage = Stage.DONE;
					break;
				}
				case CHECKS: {
					if (count == 0) {
						index = 0;
						count = gen.generateChecks(board, buffer, 0);
					}
					while (index < count) {
						int m = buffer[index++];
						m = MoveFactory.intToMove(m);
						if (m == ttMove || m == killerMove) continue;
						return m;
					}
					count = 0;
					stage = Stage.DONE;
					break;
				}
                default:
					return 0;
			}
		}
	}
}
