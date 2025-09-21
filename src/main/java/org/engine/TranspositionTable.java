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

    public static final class Entry {
        public final short packedMove;
        public final int score;
        public final int eval;
        public final int depth;
        public final int bound;
        public final boolean pv;

        public Entry(short packedMove, int score, int eval, int depth, int bound, boolean pv) {
            this.packedMove = packedMove;
            this.score = score;
            this.eval = eval;
            this.depth = depth;
            this.bound = bound;
            this.pv = pv;
        }
    }

    public static final class ReadResult {
        public final boolean hit;
        public final int slotIndex;
        public final Entry entry;

        public ReadResult(boolean hit, int slotIndex, Entry entry) {
            this.hit = hit;
            this.slotIndex = slotIndex;
            this.entry = entry;
        }
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
        int toSample = (int) Math.min(1000L, numBuckets);
        int hit = 0;
        int curAge = age & 0xFF;
        for (int i = 0; i < toSample; i++) {
            int base = bucketBase(i);
            for (int slot = 0; slot < ENTRIES_PER_BUCKET; slot++) {
                int idx = base + slot;
                long body = bodies[idx];
                int abpv = decodeAgeBoundPV(body) & 0xFF;
                boolean valid = (boundFromTT(abpv) != BOUND_NONE);
                if (valid && (ageFromTT(abpv) & 0xFF) == curAge) hit++;
            }
        }
        return hit / ENTRIES_PER_BUCKET;
    }

    public void updateTableAge() {
        age = (byte) ((age + 1) & AGE_MASK);
    }

    public ReadResult read(long posKey, int halfmoveClock, int ply) {
        if (bodies == null || numBuckets == 0) return new ReadResult(false, -1, null);

        int bucket = (int) index(posKey);
        int base = bucketBase(bucket);
        int wantKey = (int) (posKey & 0xFFFFL);

        for (int slot = 0; slot < ENTRIES_PER_BUCKET; slot++) {
            int idx = base + slot;
            if ((keys[idx] & 0xFFFF) == wantKey) {
                long body = bodies[idx];
                int depth = decodeDepth(body) & 0xFF;
                if (depth != 0) {
                    short mv = decodePackedMove(body);
                    int stored = decodeScore(body);
                    int score = scoreFromTT(stored, ply);
                    int eval = (short) decodeEval(body);
                    int abpv = decodeAgeBoundPV(body) & 0xFF;
                    int bound = boundFromTT(abpv);
                    boolean pv = formerPV(abpv);
                    int apiDepth = (depth == 255) ? -1 : depth;
                    return new ReadResult(true, idx, new Entry(mv, score, eval, apiDepth, bound, pv));
                }
            }
        }

        int bestSlot = 0;
        int bestMetric = Integer.MAX_VALUE;
        int tableAge = age & 0xFF;
        for (int slot = 0; slot < ENTRIES_PER_BUCKET; slot++) {
            int idx = base + slot;
            long body = bodies[idx];
            int entryDepth = decodeDepth(body) & 0xFF;
            int entryAge = ageFromTT(decodeAgeBoundPV(body) & 0xFF) & 0xFF;
            int relative = (MAX_AGE + tableAge - entryAge) & AGE_MASK;
            int metric = entryDepth - 4 * relative;
            if (slot == 0 || metric < bestMetric) { bestMetric = metric; bestSlot = slot; }
        }

        return new ReadResult(false, base + bestSlot, null);
    }

    public void writeAt(int slotIndex,
                        long posKey,
                        int depth,
                        int eval,
                        int score,
                        int bound,
                        short packedMove,
                        boolean pvAggregated) {
        if (bodies == null || numBuckets == 0 || slotIndex < 0) return;

        int wantKey = (int) (posKey & 0xFFFFL);
        int tableAge = age & 0xFF;

        long body = bodies[slotIndex];
        short existingKey = keys[slotIndex];

        short curMove = decodePackedMove(body);
        short curScore = decodeScore(body);
        short curEval = decodeEval(body);
        int curDepth = decodeDepth(body) & 0xFF;
        int curAbpv = decodeAgeBoundPV(body) & 0xFF;

        short newMove = curMove;
        if ((packedMove & 0xFFFF) != 0 || (existingKey & 0xFFFF) != wantKey) {
            newMove = packedMove;
        }

        boolean keyMismatch = (existingKey & 0xFFFF) != wantKey;
        int writeDepthPriority = (depth == 255) ? -1 : depth;
        boolean overwrite = keyMismatch
                || bound == BOUND_EXACT
                || (writeDepthPriority + 4 + (pvAggregated ? 2 : 0) > curDepth)
                || ((ageFromTT(curAbpv) & 0xFF) != tableAge);

        if (!overwrite) {
            int curBound = boundFromTT(curAbpv);
            if (curDepth >= 5 && curBound != BOUND_EXACT) {
                curDepth = Math.max(0, curDepth - 1);
            }
            long keep = encodeBody(newMove, curScore, curEval, (byte) curDepth, (byte) curAbpv);
            bodies[slotIndex] = keep;
            keys[slotIndex] = (short) wantKey;
            return;
        }

        int clampedDepth = clamp(depth, 0, 255);
        int abpv = packToTT(bound, pvAggregated, tableAge);
        short s = (short) clamp(score, Short.MIN_VALUE, Short.MAX_VALUE);
        short e = (short) clamp(eval, Short.MIN_VALUE, Short.MAX_VALUE);
        long encoded = encodeBody(newMove, s, e, (byte) clampedDepth, (byte) abpv);
        bodies[slotIndex] = encoded;
        keys[slotIndex] = (short) wantKey;
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


