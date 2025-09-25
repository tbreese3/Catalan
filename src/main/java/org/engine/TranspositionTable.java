package org.engine;

import java.util.Arrays;

public final class TranspositionTable {
    public static final int SLOTS_PER_SET = 3;

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

    public int getStaticEval(int entryIndex) {
        long body = bodies[entryIndex];
        return decodeEval(body);
    }

    public int getDepth(int entryIndex) {
        long body = bodies[entryIndex];
        return decodeDepth(body) & 0xFF;
    }

    public int getBound(int entryIndex) {
        long body = bodies[entryIndex];
        return boundFromTT(decodeAgeBoundPV(body) & 0xFF);
    }

    public boolean wasPV(int entryIndex) {
        long body = bodies[entryIndex];
        return formerPV(decodeAgeBoundPV(body) & 0xFF);
    }

    public short getPackedMove(int entryIndex) {
        long body = bodies[entryIndex];
        return decodePackedMove(body);
    }

    public int getScore(int entryIndex, int ply) {
        long body = bodies[entryIndex];
        int s = decodeScore(body);
        if (s == SCORE_VOID) return SCORE_VOID;
        return scoreFromTT(s, ply);
    }

    public boolean isEmpty(int entryIndex) {
        long body = bodies[entryIndex];
        short s = decodeScore(body);
        byte ab = decodeAgeBoundPV(body);
        return s == 0 && ab == 0;
    }

    public void store(int entryIndex, long key, int bound, int depth, int move, int score, int eval, boolean isPV, int ply) {
        long body = bodies[entryIndex];
        short existingKey = keys[entryIndex];

        short bodyMove = decodePackedMove(body);
        short bodyScore = decodeScore(body);
        short bodyEval = decodeEval(body);
        byte bodyDepth = decodeDepth(body);
        byte bodyAbpv = decodeAgeBoundPV(body);

        short newPackedMove = bodyMove;
        int wantKey = (int) (key & 0xFFFFL);
        if ((move & 0xFFFF) != 0 || (existingKey & 0xFFFF) != wantKey) {
            newPackedMove = (short) (move & 0xFFFF);
        }

        boolean keyMismatch = (existingKey & 0xFFFF) != wantKey;

        int adjScore;
        if (score == SCORE_VOID) adjScore = score;
        else adjScore = scoreToTT(score, ply);

        boolean isExact = (bound == BOUND_EXACT);
        int existingDepth = bodyDepth & 0xFF;
        int existingGen = ageFromTT(bodyAbpv & 0xFF);
        int genNow = age & 0xFF;
        boolean refresh = existingGen != genNow;
        boolean depthImproves = depth + (isPV ? 2 : 0) >= existingDepth - 1;
        boolean overwrite = keyMismatch || isExact || depthImproves || refresh;

        if (overwrite) {
            bodyDepth = (byte) clamp(depth, 0, 255);
            boolean persistPV = isPV || ((bodyAbpv & 0xFF) != 0 && formerPV(bodyAbpv & 0xFF));
            bodyAbpv = (byte) packToTT(bound, persistPV, genNow);
            bodyScore = (short) clamp(adjScore, Short.MIN_VALUE, Short.MAX_VALUE);
            bodyEval = (short) clamp(eval, Short.MIN_VALUE, Short.MAX_VALUE);
        }

        long newBody = encodeBody(newPackedMove, bodyScore, bodyEval, bodyDepth, bodyAbpv);
        bodies[entryIndex] = newBody;
        keys[entryIndex] = (short) wantKey;
    }

    public ProbeResult probe(long key) {
        if (bodies == null || numBuckets == 0) return new ProbeResult(0, false);
        int bucket = (int) index(key);
        int base = setBase(bucket);
        int wantKey = (int) (key & 0xFFFFL);

        // Modern replacement scheme inspired by top engines
        // Value function: depth - 8*age, with bonuses for bound type and PV flag
        int bestIdx = base;
        int bestValue = Integer.MIN_VALUE;

        for (int slot = 0; slot < SLOTS_PER_SET; slot++) {
            int idx = base + slot;
            short k = keys[idx];
            
            // Always return on key match
            if ((k & 0xFFFF) == wantKey) {
                boolean hit = !isEmpty(idx);
                return new ProbeResult(idx, hit);
            }

            // Calculate replacement value for this slot
            long body = bodies[idx];
            if (body == 0L && k == 0) {
                // Empty slot has lowest value (will be replaced first)
                return new ProbeResult(idx, false);
            }

            byte abpv = decodeAgeBoundPV(body);
            int entryDepth = decodeDepth(body) & 0xFF;
            int entryAge = ageFromTT(abpv & 0xFF);
            int ageDiff = ((age - entryAge) & AGE_MASK);
            int bound = boundFromTT(abpv & 0xFF);
            boolean wasPv = formerPV(abpv & 0xFF);


            int value = entryDepth - 8 * ageDiff;
            if (wasPv) value += 1;
            if (bound == BOUND_EXACT) value += 1;

            if (value < bestValue) {
                bestValue = value;
                bestIdx = idx;
            }
        }

        return new ProbeResult(bestIdx, false);
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
}