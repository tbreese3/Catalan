package org.engine;

import java.util.Arrays;

public final class TranspositionTable {

    public static final int SLOTS_PER_SET = 4;

    private static final int ENTRY_SIZE_BYTES = 10;
    private static final int SET_SIZE_BYTES_NO_PADDING = SLOTS_PER_SET * ENTRY_SIZE_BYTES; // 40 bytes

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

    

    public final class ProbeResult {
        private final int index;
        public final boolean hit;

        private ProbeResult(int index, boolean hit) {
            this.index = index;
            this.hit = hit;
        }

        public int getStaticEval() {
            long body = bodies[index];
            return decodeEval(body);
        }

        public int getDepth() {
            long body = bodies[index];
            return decodeDepth(body) & 0xFF;
        }

        public int getBound() {
            long body = bodies[index];
            return boundFromTT(decodeAgeBoundPV(body) & 0xFF);
        }

        public int getAge() {
            long body = bodies[index];
            return ageFromTT(decodeAgeBoundPV(body) & 0xFF);
        }

        public short getPackedMove() {
            long body = bodies[index];
            return decodePackedMove(body);
        }

        public int getScore(int ply) {
            long body = bodies[index];
            int s = decodeScore(body);
            if (s == SCORE_VOID) return SCORE_VOID;
            return scoreFromTT(s, ply);
        }

        public boolean wasPV() {
            long body = bodies[index];
            return formerPV(decodeAgeBoundPV(body) & 0xFF);
        }

        public boolean isEmpty() {
            long body = bodies[index];
            short s = decodeScore(body);
            byte ab = decodeAgeBoundPV(body);
            return s == 0 && ab == 0;
        }

        public int getAgeDistance() {
            int a = getAge();
            return (MAX_AGE + (age & 0xFF) - (a & 0xFF)) & AGE_MASK;
        }

        public void store(long key, int bound, int depth, int move, int score, int eval, boolean isPV, int ply) {
            long body = bodies[index];
            short existingKey = keys[index];

            short bodyMove = decodePackedMove(body);
            short bodyScore = decodeScore(body);
            short bodyEval = decodeEval(body);
            byte bodyDepth = decodeDepth(body);
            byte bodyAbpv = decodeAgeBoundPV(body);

            int wantKey = (int) (key & 0xFFFFL);
            boolean keyMatches = (existingKey & 0xFFFF) == wantKey;

            // Decide move to keep: prefer explicit move, else keep existing move for same key, else clear
            short newPackedMove = bodyMove;
            if ((move & 0xFFFF) != 0) {
                newPackedMove = (short) (move & 0xFFFF);
            } else if (!keyMatches) {
                newPackedMove = 0;
            }

            int adjScore = (score == SCORE_VOID) ? SCORE_VOID : scoreToTT(score, ply);

            int existingDepth = bodyDepth & 0xFF;
            int existingAge = ageFromTT(bodyAbpv & 0xFF);
            int ageDelta = (MAX_AGE + (age & 0xFF) - (existingAge & 0xFF)) & AGE_MASK;

            int existingBound = boundFromTT(bodyAbpv & 0xFF);
            boolean existingIsPV = formerPV(bodyAbpv & 0xFF);

            // Compute a replacement score similar to modern engines: favor newer gen, higher depth, better bounds, PV
            int existingScore =
                    (keyMatches ? 1000 : 0) +
                    ((MAX_AGE - ageDelta) << 12) +
                    (existingIsPV ? (1 << 11) : 0) +
                    ((existingBound == BOUND_EXACT ? 2 : existingBound != BOUND_NONE ? 1 : 0) << 9) +
                    (existingDepth << 0);

            int incomingScore =
                    1000 +
                    (MAX_AGE << 12) +
                    ((isPV ? 1 : 0) << 11) +
                    ((bound == BOUND_EXACT ? 2 : bound != BOUND_NONE ? 1 : 0) << 9) +
                    (clamp(depth, 0, 255) << 0);

            boolean replace = !keyMatches || (incomingScore >= existingScore);

            if (replace) {
                bodyDepth = (byte) clamp(depth, 0, 255);
                boolean persistPV = isPV || existingIsPV;
                bodyAbpv = (byte) packToTT(bound, persistPV, age & 0xFF);
                bodyScore = (short) clamp(adjScore, Short.MIN_VALUE, Short.MAX_VALUE);
                bodyEval = (short) clamp(eval, Short.MIN_VALUE, Short.MAX_VALUE);
            } else {
                // Refresh generation; keep stronger state
                int b = existingBound;
                boolean persistPV = isPV || existingIsPV;
                bodyAbpv = (byte) packToTT(b, persistPV, age & 0xFF);
            }

            long newBody = encodeBody(newPackedMove, bodyScore, bodyEval, bodyDepth, bodyAbpv);
            bodies[index] = newBody;
            keys[index] = (short) wantKey;
        }
    }

    public ProbeResult probe(long key) {
        if (bodies == null || numBuckets == 0) return new ProbeResult(0, false);
        int bucket = (int) index(key);
        int base = setBase(bucket);
        int wantKey = (int) (key & 0xFFFFL);

        int emptyIdx = -1;
        int bestIdx = base; // default victim
        int bestScore = Integer.MIN_VALUE;

        for (int slot = 0; slot < SLOTS_PER_SET; slot++) {
            int idx = base + slot;
            short k = keys[idx];
            long body = bodies[idx];

            if ((k & 0xFFFF) == wantKey) {
                boolean hit = !isEmptyBody(body);
                return new ProbeResult(idx, hit);
            }

            if ((k & 0xFFFF) == 0 && isEmptyBody(body)) {
                if (emptyIdx == -1) emptyIdx = idx;
                continue;
            }

            // Score this slot for potential replacement: prefer older generation, non-PV, non-exact, shallow depth
            byte abpv = decodeAgeBoundPV(body);
            int a = ageFromTT(abpv & 0xFF);
            int ageDelta = (MAX_AGE + (age & 0xFF) - (a & 0xFF)) & AGE_MASK;
            boolean isPV = formerPV(abpv & 0xFF);
            int b = boundFromTT(abpv & 0xFF);
            int d = decodeDepth(body) & 0xFF;

            int score = (ageDelta << 12) - (isPV ? (1 << 11) : 0) - ((b == BOUND_EXACT ? 2 : b != BOUND_NONE ? 1 : 0) << 9) - d;
            if (score > bestScore) {
                bestScore = score;
                bestIdx = idx;
            }
        }

        int idx;
        if (emptyIdx != -1) idx = emptyIdx; else idx = bestIdx;
        return new ProbeResult(idx, false);
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

    private static boolean isEmptyBody(long body) {
        return decodeScore(body) == 0 && decodeAgeBoundPV(body) == 0;
    }
}