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

        // Move handling: keep existing unless a move is provided or key changes
        short newPackedMove = curMove;
        if (((move & 0xFFFF) != 0) || keyMismatch) {
            newPackedMove = (short) (move & 0xFFFF);
        }

        int adjScore = (score == SCORE_VOID) ? SCORE_VOID : scoreToTT(score, ply);

        int entryAge = ageFromTT(curAbpv & 0xFF);
        int ageDelta = (MAX_AGE + (age & 0xFF) - entryAge) & AGE_MASK;
        int existingDepth = curDepth & 0xFF;
        int newDepth = clamp(depth, 0, 255);

        // Dynamic tiered overwrite: collisions/empty/exact/gen-change OR depth lead meets tiered margin
        boolean overwrite = keyMismatch || emptySlot || (bound == BOUND_EXACT) || (entryAge != (age & 0xFF));
        if (!overwrite) {
            int existingBound = boundFromTT(curAbpv & 0xFF);
            boolean existingPv = formerPV(curAbpv & 0xFF);

            int tierSpan = Math.max(1, MAX_AGE / 8);
            int ageTier = Math.min(3, ageDelta / tierSpan); // 0..3

            int requiredLead = (SLOTS_PER_SET + 1);
            if (isPV) requiredLead -= 1;
            if (ageTier >= 2) requiredLead -= 1;
            if (requiredLead < 0) requiredLead = 0;

            int lead = newDepth - existingDepth;
            if (lead >= requiredLead) overwrite = true;
            else if (lead >= 0) {
                int incomingBoundPrio = (bound == BOUND_EXACT) ? 3
                        : (bound == BOUND_LOWER) ? 2
                        : (bound == BOUND_UPPER) ? 1 : 0;
                int existBoundPrio = (existingBound == BOUND_EXACT) ? 3
                        : (existingBound == BOUND_LOWER) ? 2
                        : (existingBound == BOUND_UPPER) ? 1 : 0;
                if (incomingBoundPrio > existBoundPrio) overwrite = true;
                else if (incomingBoundPrio == existBoundPrio && isPV && !existingPv) overwrite = true;
            }
        }

        if (overwrite) {
            boolean persistPV = isPV || ((curAbpv & 0xFF) != 0 && formerPV(curAbpv & 0xFF));
            byte newAbpv = (byte) packToTT(bound, persistPV, age & 0xFF);
            long newBody = encodeBody(
                    newPackedMove,
                    (short) clamp(adjScore, Short.MIN_VALUE, Short.MAX_VALUE),
                    (short) clamp(eval, Short.MIN_VALUE, Short.MAX_VALUE),
                    (byte) newDepth,
                    newAbpv
            );
            bodies[index] = newBody;
            keys[index] = (short) wantKey;
        } else if (!keyMismatch && newPackedMove != curMove) {
            long newBody = encodeBody(newPackedMove, curScore, curEval, curDepth, curAbpv);
            bodies[index] = newBody;
        }
    }

    public ProbeResult probe(long key) {
        if (bodies == null || numBuckets == 0) return new ProbeResult(0, false);
        int bucket = (int) index(key);
        int base = setBase(bucket);
        int wantKey = (int) (key & 0xFFFFL);

        int bestSlot = 0;
        int bestCode = Integer.MAX_VALUE;

        for (int slot = 0; slot < SLOTS_PER_SET; slot++) {
            int idx = base + slot;
            if ((keys[idx] & 0xFFFF) == wantKey) {
                long body = bodies[idx];
                boolean hit = !isBodyEmpty(body);
                return new ProbeResult(idx, hit);
            }

            long body = bodies[idx];
            if (isBodyEmpty(body)) {
                return new ProbeResult(idx, false);
            }

            byte abpv = decodeAgeBoundPV(body);
            int entryAge = ageFromTT(abpv & 0xFF);
            int ageDelta = (MAX_AGE + (age & 0xFF) - entryAge) & AGE_MASK;
            int entryDepth = decodeDepth(body) & 0xFF;
            int bound = boundFromTT(abpv & 0xFF);
            boolean pv = formerPV(abpv & 0xFF);

            int tierSpan = Math.max(1, MAX_AGE / 8);
            int ageTier = Math.min(3, ageDelta / tierSpan); // 0..3 (older -> higher tier)
            int boundRank = (bound == BOUND_EXACT) ? 3 : (bound == BOUND_LOWER) ? 2 : (bound == BOUND_UPPER) ? 1 : 0;

            int W1 = SLOTS_PER_SET * 64; // age tier weight
            int W2 = SLOTS_PER_SET * 8;  // bound weight
            int W3 = SLOTS_PER_SET * 4;  // PV weight

            int agePenalty = ageTier * W1;                         // older (higher tier) -> larger penalty? invert to reward
            int ageCode = (3 - ageTier) * W1;                      // reward older (smaller code)
            int boundCode = (3 - boundRank) * W2;                  // reward stronger bound
            int pvCode = (!pv ? W3 : 0);                           // reward PV

            int code = ageCode + boundCode + pvCode + (entryDepth & 0xFF);

            if (slot == 0 || code < bestCode) {
                bestCode = code;
                bestSlot = slot;
            }
        }

        return new ProbeResult(base + bestSlot, false);
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