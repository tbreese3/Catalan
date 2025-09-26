package org.engine;

import java.util.Arrays;

public final class TranspositionTable {

    public static final int SLOTS_PER_SET = 4;

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

            boolean replace;
            if (!keyMatches) {
                replace = true; // new key replaces the selected slot
            } else {
                boolean prefer = (bound == BOUND_EXACT)
                        || (depth >= existingDepth - 2)
                        || (adjScore != SCORE_VOID && bodyScore == 0)
                        || (ageDelta > 0);
                replace = prefer;
            }

            if (replace) {
                bodyDepth = (byte) clamp(depth, 0, 255);
                boolean persistPV = isPV || ((bodyAbpv & 0xFF) != 0 && formerPV(bodyAbpv & 0xFF));
                bodyAbpv = (byte) packToTT(bound, persistPV, age & 0xFF);
                bodyScore = (short) clamp(adjScore, Short.MIN_VALUE, Short.MAX_VALUE);
                bodyEval = (short) clamp(eval, Short.MIN_VALUE, Short.MAX_VALUE);
            } else {
                // refresh generation and PV bit even if not fully replacing
                int b = boundFromTT(bodyAbpv & 0xFF);
                boolean persistPV = isPV || formerPV(bodyAbpv & 0xFF);
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
        int bestOldIdx = -1;
        int bestOldAgeDelta = -1;
        int shallowIdx = -1;
        int shallowDepth = 256;

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

            int ab = decodeAgeBoundPV(body) & 0xFF;
            int a = ageFromTT(ab);
            int ageDelta = (MAX_AGE + (age & 0xFF) - (a & 0xFF)) & AGE_MASK;
            if (ageDelta > bestOldAgeDelta) {
                bestOldAgeDelta = ageDelta;
                bestOldIdx = idx;
            }

            int d = decodeDepth(body) & 0xFF;
            if (d < shallowDepth) {
                shallowDepth = d;
                shallowIdx = idx;
            }
        }

        int idx;
        if (emptyIdx != -1) idx = emptyIdx;
        else if (bestOldIdx != -1) idx = bestOldIdx;
        else idx = (shallowIdx != -1) ? shallowIdx : base;
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