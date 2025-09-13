package org.engine;

import java.util.Arrays;

public final class TranspositionTable {

    public static final int ENTRIES_PER_BUCKET = 3;

    private static final int ENTRY_SIZE_BYTES = 10;
    private static final int BUCKET_SIZE_BYTES_NO_PADDING = ENTRIES_PER_BUCKET * ENTRY_SIZE_BYTES; // 30 bytes

    private static final int MAX_AGE = 1 << 5;
    private static final int AGE_MASK = MAX_AGE - 1;

    public static final int BOUND_NONE  = 0;
    public static final int BOUND_LOWER = 1;
    public static final int BOUND_UPPER = 2;
    public static final int BOUND_EXACT = 3;

    private static final int MATE_VALUE = 32000;
    private static final int MATE_THRESHOLD = 31000;

    public static final short SCORE_NONE_TT = (short) 0x7FFF;

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

        this.numBuckets = hashSize / BUCKET_SIZE_BYTES_NO_PADDING;

        long numEntriesLong = this.numBuckets * ENTRIES_PER_BUCKET;
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

    public int probeBucket(long zobristKey) {
        if (bodies == null || numBuckets == 0) return -1;
        return (int) index(zobristKey);
    }

    public int findSlotInBucket(int bucketIndex, long zobristKey) {
        if (bodies == null || numBuckets == 0) return -1;
        int base = bucketBase(bucketIndex);
        int wantKey = (int) (zobristKey & 0xFFFFL);
        for (int slot = 0; slot < ENTRIES_PER_BUCKET; slot++) {
            int idx = base + slot;
            short k = keys[idx];
            if ((k & 0xFFFF) == wantKey) return slot;
        }
        return -1;
    }

    public short readPackedMove(int bucketIndex, int slot) {
        int idx = bucketBase(bucketIndex) + slot;
        long body = bodies[idx];
        return decodePackedMove(body);
    }

    public int readScore(int bucketIndex, int slot) {
        int idx = bucketBase(bucketIndex) + slot;
        long body = bodies[idx];
        return decodeScore(body);
    }

    public int readEval(int bucketIndex, int slot) {
        int idx = bucketBase(bucketIndex) + slot;
        long body = bodies[idx];
        return decodeEval(body);
    }

    public int readDepth(int bucketIndex, int slot) {
        int idx = bucketBase(bucketIndex) + slot;
        long body = bodies[idx];
        return decodeDepth(body) & 0xFF;
    }

    public int readAgeBoundPV(int bucketIndex, int slot) {
        int idx = bucketBase(bucketIndex) + slot;
        long body = bodies[idx];
        return decodeAgeBoundPV(body) & 0xFF;
    }

    public int readBound(int bucketIndex, int slot) {
        return boundFromTT(readAgeBoundPV(bucketIndex, slot));
    }

    public boolean readWasPV(int bucketIndex, int slot) {
        return formerPV(readAgeBoundPV(bucketIndex, slot));
    }

    public int readAge(int bucketIndex, int slot) {
        return ageFromTT(readAgeBoundPV(bucketIndex, slot));
    }

    public int readKey16(int bucketIndex, int slot) {
        int idx = bucketBase(bucketIndex) + slot;
        return keys[idx] & 0xFFFF;
    }

    public void store(long zobristKey, short packedMove, int score, int eval, int bound, int depth, boolean pvNode, boolean wasPV) {
        if (numBuckets == 0) return;
        storeByKey(zobristKey, packedMove, score, eval, bound, depth, pvNode, wasPV);
    }

    public int getHashfull() {
        if (bodies == null || numBuckets == 0) return 0;
        int toSample = (int) Math.min(2000L, numBuckets);
        int hit = 0;
        for (int i = 0; i < toSample; i++) {
            int base = bucketBase(i);
            for (int slot = 0; slot < ENTRIES_PER_BUCKET; slot++) {
                int idx = base + slot;
                short key = keys[idx];
                if ((key & 0xFFFF) != 0) {
                    long body = bodies[idx];
                    byte abpv = decodeAgeBoundPV(body);
                    if (ageFromTT(abpv) == age) hit++;
                }
            }
        }
        return hit / (2 * ENTRIES_PER_BUCKET);
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

    private void storeByKey(long posKey, short move, int score, int eval, int bound, int depth, boolean pvNode, boolean wasPV) {
        int bucket = (int) index(posKey);
        int base = bucketBase(bucket);
        int wantKey = (int) (posKey & 0xFFFFL);
        byte tableAge = this.age;

        int bestSlot = 0;
        int bestMetric = Integer.MAX_VALUE;
        boolean found = false;

        for (int slot = 0; slot < ENTRIES_PER_BUCKET; slot++) {
            int offIdx = base + slot;
            short key = keys[offIdx];
            if ((key & 0xFFFF) == wantKey) {
                bestSlot = slot;
                found = true;
                break;
            }

            long body = bodies[offIdx];
            byte abpv = decodeAgeBoundPV(body);
            byte entryDepth = decodeDepth(body);

            int ageDelta = (MAX_AGE + (tableAge & 0xFF) - ageFromTT(abpv & 0xFF)) & AGE_MASK;
            int metric = (entryDepth & 0xFF) - ageDelta * 4;
            if (slot == 0 || metric < bestMetric) {
                bestMetric = metric;
                bestSlot = slot;
            }
        }

        int offIdx = base + bestSlot;
        long body = bodies[offIdx];
        short existingKey = keys[offIdx];

        short bodyMove = decodePackedMove(body);
        short bodyScore = decodeScore(body);
        short bodyEval = decodeEval(body);
        byte bodyDepth = decodeDepth(body);
        byte bodyAbpv = decodeAgeBoundPV(body);

        short newPackedMove = bodyMove;
        if ((move & 0xFFFF) != 0 || (existingKey & 0xFFFF) != wantKey) {
            newPackedMove = move;
        }

        boolean keyMismatch = (existingKey & 0xFFFF) != wantKey;
        boolean overwrite = (bound == BOUND_EXACT)
                || keyMismatch
                || (depth + 5 + (pvNode ? 2 : 0) > (bodyDepth & 0xFF))
                || (ageFromTT(bodyAbpv & 0xFF) != (tableAge & 0xFF));

        if (overwrite) {
            bodyDepth = (byte) clamp(depth, 0, 255);
            bodyAbpv = (byte) packToTT(bound, wasPV, tableAge & 0xFF);
            bodyScore = (short) clamp(score, Short.MIN_VALUE, Short.MAX_VALUE);
            bodyEval = (short) clamp(eval, Short.MIN_VALUE, Short.MAX_VALUE);
        }

        long newBody = encodeBody(newPackedMove, bodyScore, bodyEval, bodyDepth, bodyAbpv);
        bodies[offIdx] = newBody;
        keys[offIdx] = (short) wantKey;
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

    private static int bucketBase(int bucketIndex) {
        return bucketIndex * ENTRIES_PER_BUCKET;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}


