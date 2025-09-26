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
        short existingKey = keys[entryIndex];
        int wantKey = (int) (key & 0xFFFFL);
        boolean keyMatch = (existingKey & 0xFFFF) == wantKey;

        long existingBody = keyMatch ? bodies[entryIndex] : 0L;
        byte existingAbpv = keyMatch ? decodeAgeBoundPV(existingBody) : 0;
        byte existingDepth = keyMatch ? decodeDepth(existingBody) : 0;
        int existingBound = keyMatch ? boundFromTT(existingAbpv & 0xFF) : BOUND_NONE;
        short existingMove = keyMatch ? decodePackedMove(existingBody) : 0;
        short existingScore = keyMatch ? decodeScore(existingBody) : 0;
        boolean existingPV = keyMatch && formerPV(existingAbpv & 0xFF);

        // Candidate move to store: prefer current, otherwise keep old
        short bestMove = (short) (move & 0xFFFF);
        if (bestMove == 0 && existingMove != 0) bestMove = existingMove;

        // Compute new packed fields
        byte newDepth = (byte) clamp(depth, 0, 255);
        boolean newPV = isPV || existingPV; // PV flag is sticky
        int currentGen = age & 0xFF;
        byte newAbpv = (byte) packToTT(bound, newPV, currentGen);

        // Handle mate scores - adjust relative to root
        int adjScore = (score == SCORE_VOID) ? score : scoreToTT(score, ply);
        short newScore = (short) clamp(adjScore, Short.MIN_VALUE, Short.MAX_VALUE);
        short newEval = (short) clamp(eval, Short.MIN_VALUE, Short.MAX_VALUE);

        if (keyMatch) {
            // Avoid degrading a deeper exact entry with a shallow non-exact one
            boolean avoidDegradingExact = (existingBound == BOUND_EXACT) && (bound != BOUND_EXACT) && (depth + 2 < (existingDepth & 0xFF));
            boolean evalOnlyIncoming = (newScore == SCORE_VOID);
            boolean existingHasSearch = (existingScore != SCORE_VOID);

            boolean shouldUpdate = !avoidDegradingExact && (
                    bound == BOUND_EXACT ||
                    depth + 2 >= (existingDepth & 0xFF) ||
                    existingBound == BOUND_UPPER
            );

            // Do not replace a searched entry with an eval-only update
            if (evalOnlyIncoming && existingHasSearch) shouldUpdate = false;

            if (shouldUpdate) {
                long newBody = encodeBody(bestMove, newScore, newEval, newDepth, newAbpv);
                bodies[entryIndex] = newBody;
                keys[entryIndex] = (short) wantKey;
                return;
            } else {
                // Partial refresh: update generation, preserve strong info, optionally add move/eval
                short keptMove = existingMove != 0 ? existingMove : bestMove;
                short keptScore = existingScore; // keep searched result if present
                short keptEval = (evalOnlyIncoming ? newEval : (short) decodeEval(existingBody));
                byte refreshedAbpv = (byte) packToTT(existingBound, newPV, currentGen);
                long refreshedBody = encodeBody(keptMove, keptScore, keptEval, existingDepth, refreshedAbpv);
                bodies[entryIndex] = refreshedBody;
                // key unchanged
                return;
            }
        } else {
            // No key match; victim index already chosen by probe()
            long newBody = encodeBody(bestMove, newScore, newEval, newDepth, newAbpv);
            bodies[entryIndex] = newBody;
            keys[entryIndex] = (short) wantKey;
            return;
        }
    }

    public ProbeResult probe(long key) {
        if (bodies == null || numBuckets == 0) return new ProbeResult(0, false);

        int bucket = (int) index(key);
        int base = setBase(bucket);
        int wantKey = (int) (key & 0xFFFFL);

        // First pass: look for exact key match and refresh generation on hit
        for (int slot = 0; slot < SLOTS_PER_SET; slot++) {
            int idx = base + slot;
            short k = keys[idx];

            if ((k & 0xFFFF) == wantKey) {
                long body = bodies[idx];
                if (body == 0L) {
                    // Corrupted or cleared entry - treat as miss but keep slot
                    return new ProbeResult(idx, false);
                }
                byte abpv = decodeAgeBoundPV(body);
                int entryAge = ageFromTT(abpv & 0xFF);
                int currentGen = age & 0xFF;
                if (entryAge != currentGen) {
                    int bound = boundFromTT(abpv & 0xFF);
                    boolean wasPv = formerPV(abpv & 0xFF);
                    byte refreshedAbpv = (byte) packToTT(bound, wasPv, currentGen);
                    long refreshed = (body & ~(0xFFL << 56)) | ((refreshedAbpv & 0xFFL) << 56);
                    bodies[idx] = refreshed;
                }
                return new ProbeResult(idx, true);
            }
        }

        // No key match found - select victim for replacement
        // Replace entry with minimum value = depth - 8*relative_age, with small bonuses
        int replaceIdx = base;
        int minValue = Integer.MAX_VALUE;

        for (int slot = 0; slot < SLOTS_PER_SET; slot++) {
            int idx = base + slot;
            short k = keys[idx];

            // Empty or cleared slot - use immediately
            if (k == 0 || bodies[idx] == 0L) {
                return new ProbeResult(idx, false);
            }

            long body = bodies[idx];
            byte abpv = decodeAgeBoundPV(body);
            int entryDepth = decodeDepth(body) & 0xFF;
            int entryAge = ageFromTT(abpv & 0xFF);
            int relativeAge = ((age - entryAge) & AGE_MASK);

            int value = entryDepth - (relativeAge << 3);

            short score = decodeScore(body);
            int bound = boundFromTT(abpv & 0xFF);
            boolean wasPv = formerPV(abpv & 0xFF);

            if (score == SCORE_VOID) value -= 8; // less valuable
            if (bound == BOUND_EXACT) value += 1;
            if (wasPv) value += 1;

            if (value < minValue) {
                minValue = value;
                replaceIdx = idx;
            }
        }

        return new ProbeResult(replaceIdx, false);
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