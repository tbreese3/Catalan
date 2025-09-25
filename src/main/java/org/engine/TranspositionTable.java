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
        
        long body = bodies[entryIndex];
        short bodyMove = decodePackedMove(body);
        byte bodyDepth = decodeDepth(body);
        byte bodyAbpv = decodeAgeBoundPV(body);
        
        // Modern engines always store on key match or empty slot
        if (!keyMatch) {
            // Overwriting different position - this is from probe's victim selection
            bodyMove = 0;
            bodyDepth = 0;
            bodyAbpv = 0;
        }
        
        short newPackedMove = (short) (move & 0xFFFF);
        if (newPackedMove == 0 && keyMatch && bodyMove != 0) {
            newPackedMove = bodyMove;
        }

        int existingDepth = bodyDepth & 0xFF;
        int existingBound = boundFromTT(bodyAbpv & 0xFF);
        boolean existingPV = formerPV(bodyAbpv & 0xFF);
        int currentGen = age & 0xFF;
        int existingGen = ageFromTT(bodyAbpv & 0xFF);
        boolean differentGen = existingGen != currentGen;
        boolean exactBound = (bound == BOUND_EXACT);
        boolean pvChange = isPV && !existingPV; // only escalate PV, never demote
        // Replacement conditions: prefer keeping deep, exact, PV and fresh entries
        boolean depthOK = depth + 2 >= existingDepth || exactBound || pvChange || !keyMatch;
        
        // Avoid overwriting a deeper EXACT unless we improve it
        boolean existingIsValuable = (existingBound == BOUND_EXACT) || existingPV;
        boolean newImprovesValuable = exactBound || (depth > existingDepth + 1) || pvChange;

        if (!keyMatch || differentGen || depthOK || (existingIsValuable && newImprovesValuable)) {
            // Store the entry
            byte newDepth = (byte) clamp(depth, 0, 255);
            boolean newPV = isPV || (keyMatch && existingPV); // Preserve PV when matched
            byte newAbpv = (byte) packToTT(bound, newPV, currentGen);
            
            int adjScore = (score == SCORE_VOID) ? score : scoreToTT(score, ply);
            short newScore = (short) clamp(adjScore, Short.MIN_VALUE, Short.MAX_VALUE);
            short newEval = (short) clamp(eval, Short.MIN_VALUE, Short.MAX_VALUE);
            
            long newBody = encodeBody(newPackedMove, newScore, newEval, newDepth, newAbpv);
            bodies[entryIndex] = newBody;
            keys[entryIndex] = (short) wantKey;
        } else if (keyMatch && !differentGen) {
            // Refresh generation without changing data to reduce aging evictions
            byte refreshedAbpv = (byte) packToTT(existingBound, existingPV, currentGen);
            long refreshedBody = encodeBody(bodyMove, decodeScore(body), decodeEval(body), bodyDepth, refreshedAbpv);
            bodies[entryIndex] = refreshedBody;
        }
    }

    public ProbeResult probe(long key) {
        if (bodies == null || numBuckets == 0) return new ProbeResult(0, false);
        int bucket = (int) index(key);
        int base = setBase(bucket);
        int wantKey = (int) (key & 0xFFFFL);

        // First pass: look for exact key match or empty slot
        int firstEmpty = -1;
        for (int slot = 0; slot < SLOTS_PER_SET; slot++) {
            int idx = base + slot;
            short k = keys[idx];
            
            if ((k & 0xFFFF) == wantKey) {
                // Found matching key - check if valid entry
                long body = bodies[idx];
                if (body == 0L) {
                    // Matching key but empty body - treat as miss
                    return new ProbeResult(idx, false);
                }
                // Refresh generation on hit to protect from replacement
                byte abpv = decodeAgeBoundPV(body);
                int entryGen = ageFromTT(abpv & 0xFF);
                int currentGen = age & 0xFF;
                if (entryGen != currentGen) {
                    byte newAbpv = (byte) packToTT(boundFromTT(abpv & 0xFF), formerPV(abpv & 0xFF), currentGen);
                    long newBody = encodeBody(decodePackedMove(body), decodeScore(body), decodeEval(body), decodeDepth(body), newAbpv);
                    bodies[idx] = newBody;
                }
                return new ProbeResult(idx, true);
            }
            
            if (k == 0 && firstEmpty == -1) {
                firstEmpty = idx;
            }
        }
        
        // No key match found - return empty slot if available
        if (firstEmpty != -1) {
            return new ProbeResult(firstEmpty, false);
        }
        
        // Need to select victim for replacement
        // Modern replacement strategy: minimize value = depth - 8*ageDiff + bonuses
        int victimIdx = base;
        int victimValue = Integer.MAX_VALUE;
        
        for (int slot = 0; slot < SLOTS_PER_SET; slot++) {
            int idx = base + slot;
            long body = bodies[idx];
            
            byte abpv = decodeAgeBoundPV(body);
            int entryDepth = decodeDepth(body) & 0xFF;
            int entryGen = ageFromTT(abpv & 0xFF);
            int genDiff = ((age - entryGen) & AGE_MASK);
            int bound = boundFromTT(abpv & 0xFF);
            boolean wasPv = formerPV(abpv & 0xFF);
            
            // Calculate replacement value
            // Base: depth - 8 * generation_difference
            // This heavily favors replacing old entries
            int value = entryDepth - (genDiff << 3);
            // Strongly prefer evicting entries that only contain static eval (no search score)
            short s = decodeScore(body);
            if (s == SCORE_VOID) value -= 16;
            
            // Small bonuses to preserve valuable entries
            if (bound == BOUND_EXACT) value += 2;  // Exact bounds are most valuable
            if (bound == BOUND_UPPER) value -= 1;  // Upper bounds are less valuable
            if (wasPv) value += 1;                 // PV nodes are important
            
            // Track minimum value (will be replaced)
            if (value < victimValue) {
                victimValue = value;
                victimIdx = idx;
            }
        }
        
        return new ProbeResult(victimIdx, false);
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