package org.engine;

import java.util.Arrays;

public final class TranspositionTable {

    public static final int SLOTS_PER_SET = 4; // modern 4-way set associativity

    private static final int ENTRY_SIZE_BYTES = 10;
    private static final int SET_SIZE_BYTES_NO_PADDING = SLOTS_PER_SET * ENTRY_SIZE_BYTES; // 30 bytes

    private static final int MAX_AGE = 1 << 5;
    private static final int AGE_MASK = MAX_AGE - 1;

    public static final int BOUND_NONE  = 0;
    public static final int BOUND_LOWER = 1;
    public static final int BOUND_UPPER = 2;
    public static final int BOUND_EXACT = 3;

    private static final int MATE_VALUE = 32000;
    private static final int MATE_THRESHOLD = 31000;

    public static final short SCORE_VOID = (short) 0x7FFF;

    public static final TranspositionTable TT = new TranspositionTable();

    private long[] bodies;
    private short[] keys;
    private long numBuckets;
    private byte age;

    private TranspositionTable() {
        this.bodies = null;
        this.keys = null;
        this.numBuckets = 0L;
        this.age = 1;
    }

    public synchronized void init(long megaBytes) {
        final long ONE_MB = 1024L * 1024L;
        final long hashSize = megaBytes * ONE_MB;

        this.numBuckets = hashSize / SET_SIZE_BYTES_NO_PADDING;

        long numEntriesLong = this.numBuckets * SLOTS_PER_SET;
        if (numEntriesLong > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Requested TT too large: entries=" + numEntriesLong);
        }
        int numEntries = (int) numEntriesLong;

        this.bodies = new long[numEntries];
        this.keys = new short[numEntries];
        clear();
    }

    public synchronized void clear() {
        if (bodies == null || keys == null) return;
        Arrays.fill(bodies, 0L);
        Arrays.fill(keys, (short) 0);
        age = 1;
    }

    public synchronized void resize(long megaBytes) {
        init(megaBytes);
    }

    public void nextSearch() {
        updateTableAge();
    }

    public int hashfull() {
        return getHashfull();
    }

    public int getHashfull() {
        if (bodies == null || numBuckets == 0) return 0;
        int toSample = (int) Math.min(2000L, numBuckets);
        int hit = 0;
        for (int i = 0; i < toSample; i++) {
            int base = setBase(i);
            for (int slot = 0; slot < SLOTS_PER_SET; slot++) {
                int idx = base + slot;
                short key = keys[idx];
                if ((key & 0xFFFF) != 0) {
                    long body = bodies[idx];
                    byte abpv = decodeAgeBoundPV(body);
                    if (ageFromTT(abpv) == age) hit++;
                }
            }
        }
        return hit / (2 * SLOTS_PER_SET);
    }

    public void updateTableAge() {
        age = (byte) ((age + 1) & AGE_MASK);
    }

    public static int scoreToTT(int score, int ply) {
        if (score > MATE_THRESHOLD) return score + ply;
        if (score < -MATE_THRESHOLD) return score - ply;
        return score;
    }

    public static int scoreFromTT(int score, int ply) {
        if (score > MATE_THRESHOLD) return score - ply;
        if (score < -MATE_THRESHOLD) return score + ply;
        return score;
    }

    public static int boundFromTT(int ageBoundPV) { return ageBoundPV & 0b11; }
    public static boolean formerPV(int ageBoundPV) { return (ageBoundPV & 0b100) != 0; }
    public static int ageFromTT(int ageBoundPV) { return (ageBoundPV >>> 3) & AGE_MASK; }
    public static int packToTT(int bound, boolean wasPV, int age) {
        return (bound & 0b11) | (wasPV ? 0b100 : 0) | ((age & AGE_MASK) << 3);
    }

    

    public static final class ProbeResult {
        public final int index;
        public final boolean hit;

        private ProbeResult(int index, boolean hit) {
            this.index = index;
            this.hit = hit;
        }
    }

    // Accessors for probed slot (avoid Entry wrapper). These mirror former Entry methods
    public int getStaticEval(ProbeResult pr) {
        long body = bodies[pr.index];
        return decodeEval(body);
    }

    public int getDepth(ProbeResult pr) {
        long body = bodies[pr.index];
        return decodeDepth(body) & 0xFF;
    }

    public int getBound(ProbeResult pr) {
        long body = bodies[pr.index];
        return boundFromTT(decodeAgeBoundPV(body) & 0xFF);
    }

    public int getAge(ProbeResult pr) {
        long body = bodies[pr.index];
        return ageFromTT(decodeAgeBoundPV(body) & 0xFF);
    }

    public short getPackedMove(ProbeResult pr) {
        long body = bodies[pr.index];
        return decodePackedMove(body);
    }

    public int getScore(ProbeResult pr, int ply) {
        long body = bodies[pr.index];
        int s = decodeScore(body);
        if (s == SCORE_VOID) return SCORE_VOID;
        return scoreFromTT(s, ply);
    }

    public boolean wasPV(ProbeResult pr) {
        long body = bodies[pr.index];
        return formerPV(decodeAgeBoundPV(body) & 0xFF);
    }

    public boolean isEmpty(ProbeResult pr) {
        long body = bodies[pr.index];
        short s = decodeScore(body);
        byte ab = decodeAgeBoundPV(body);
        return s == 0 && ab == 0;
    }

    public int getAgeDistance(ProbeResult pr) {
        int a = getAge(pr);
        return (MAX_AGE + (age & 0xFF) - (a & 0xFF)) & AGE_MASK;
    }

    public void store(ProbeResult pr, long key, int bound, int depth, int move, int score, int eval, boolean isPV, int ply) {
        int index = pr.index;
        long body = bodies[index];
        short existingKey = keys[index];

        short curMove = decodePackedMove(body);
        short curScore = decodeScore(body);
        short curEval = decodeEval(body);
        byte curDepth = decodeDepth(body);
        byte curAbpv = decodeAgeBoundPV(body);

        int wantKey = (int) (key & 0xFFFFL);
        boolean keyMismatch = (existingKey & 0xFFFF) != wantKey;
        boolean emptySlot = isBodyEmpty(body) || (existingKey & 0xFFFF) == 0;

        // Move handling: top engines typically keep existing move if none provided
        short newPackedMove = (short) (move & 0xFFFF);
        if (newPackedMove == 0) newPackedMove = curMove;

        int adjScore = (score == SCORE_VOID) ? SCORE_VOID : scoreToTT(score, ply);

        int entryAge = ageFromTT(curAbpv & 0xFF);
        int ageDelta = (MAX_AGE + (age & 0xFF) - entryAge) & AGE_MASK;
        int existingDepth = curDepth & 0xFF;
        int newDepth = clamp(depth, 0, 255);

        int existingBound = boundFromTT(curAbpv & 0xFF);
        boolean existingPv = formerPV(curAbpv & 0xFF);
        int curAge = (age & 0xFF);

        boolean replace;
        if (emptySlot || keyMismatch || entryAge != curAge) {
            // Misses and stale entries are always replaced
            replace = true;
        } else if (bound == BOUND_EXACT) {
            replace = newDepth + 1 >= existingDepth;
        } else if (existingBound == BOUND_EXACT) {
            replace = newDepth >= existingDepth + 2;
        } else {
            replace = (newDepth > existingDepth) || (newDepth == existingDepth && isPV && !existingPv);
        }

        if (replace) {
            boolean stickPV = isPV || (!keyMismatch && entryAge == curAge && formerPV(curAbpv & 0xFF));
            byte newAbpv = (byte) packToTT(bound, stickPV, age & 0xFF);
            long newBody = encodeBody(
                    newPackedMove,
                    (short) clamp(adjScore, Short.MIN_VALUE, Short.MAX_VALUE),
                    (short) clamp(eval, Short.MIN_VALUE, Short.MAX_VALUE),
                    (byte) newDepth,
                    newAbpv
            );
            bodies[index] = newBody;
            keys[index] = (short) wantKey;
        } else if (!keyMismatch && (move & 0xFFFF) != 0 && newPackedMove != curMove) {
            // Same position but not replacing fully: refresh best move only
            long newBody = encodeBody(newPackedMove, curScore, curEval, curDepth, curAbpv);
            bodies[index] = newBody;
        }
    }

    public ProbeResult probe(long key) {
        if (bodies == null || numBuckets == 0) return new ProbeResult(0, false);
        int bucket = (int) index(key);
        int base = setBase(bucket);
        int wantKey = (int) (key & 0xFFFFL);

        int replaceSlot = 0;
        int replaceScore = Integer.MIN_VALUE; // higher is more replaceable

        for (int slot = 0; slot < SLOTS_PER_SET; slot++) {
            int idx = base + slot;
            if ((keys[idx] & 0xFFFF) == wantKey) {
                long body = bodies[idx];
                boolean hit = !isBodyEmpty(body);
                return new ProbeResult(idx, hit);
            }

            long body = bodies[idx];
            if (isBodyEmpty(body)) {
                return new ProbeResult(idx, false); // empty slot wins immediately
            }

            byte abpv = decodeAgeBoundPV(body);
            int entryAge = ageFromTT(abpv & 0xFF);
            int ageDelta = (MAX_AGE + (age & 0xFF) - entryAge) & AGE_MASK;
            int entryDepth = decodeDepth(body) & 0xFF;
            int bound = boundFromTT(abpv & 0xFF);
            boolean pv = formerPV(abpv & 0xFF);

            // Compute a value for the entry; the smallest value is the preferred victim
            int boundWeight = (bound == BOUND_EXACT) ? 2048 : (bound == BOUND_LOWER ? 384 : (bound == BOUND_UPPER ? 192 : 0));
            int pvWeight = pv ? 768 : 0;
            int value = (entryDepth << 7) + boundWeight + pvWeight - (ageDelta << 9);
            // Add a tiny salt to avoid deterministic thrashing under collisions
            int salt = (((int)(key) ^ (int)(key >>> 32) ^ (slot * 0x9E3779B1)) & 31) - 16;
            value += salt;

            // Translate to replaceScore by negating (we want minimal value)
            int score = -value;
            if (score > replaceScore) {
                replaceScore = score;
                replaceSlot = slot;
            }
        }

        return new ProbeResult(base + replaceSlot, false);
    }

    private long index(long posKey) {
        long xlo = (int) posKey & 0xFFFFFFFFL;
        long xhi = (posKey >>> 32) & 0xFFFFFFFFL;
        long nlo = (int) numBuckets & 0xFFFFFFFFL;
        long nhi = (numBuckets >>> 32) & 0xFFFFFFFFL;
        long c1 = (xlo * nlo) >>> 32;
        long c2 = (xhi * nlo) + c1;
        long c3 = (xlo * nhi) + (c2 & 0xFFFFFFFFL);
        long result = (xhi * nhi) + (c2 >>> 32) + (c3 >>> 32);
        return result;
    }

    private static long encodeBody(short packedMove, short score, short eval, byte depth, byte ageBoundPV) {
        long m = (packedMove & 0xFFFFL);
        long s = (score & 0xFFFFL) << 16;
        long e = (eval & 0xFFFFL) << 32;
        long d = (depth & 0xFFL) << 48;
        long a = (ageBoundPV & 0xFFL) << 56;
        return a | d | e | s | m;
    }

    private static short decodePackedMove(long body) {
        return (short) (body & 0xFFFFL);
    }

    private static short decodeScore(long body) {
        return (short) ((body >>> 16) & 0xFFFFL);
    }

    private static short decodeEval(long body) {
        return (short) ((body >>> 32) & 0xFFFFL);
    }

    private static byte decodeDepth(long body) {
        return (byte) ((body >>> 48) & 0xFFL);
    }

    private static byte decodeAgeBoundPV(long body) {
        return (byte) ((body >>> 56) & 0xFFL);
    }

    private static int setBase(int bucketIndex) {
        return bucketIndex * SLOTS_PER_SET;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isBodyEmpty(long body) {
        return decodeScore(body) == 0 && decodeAgeBoundPV(body) == 0;
    }
}