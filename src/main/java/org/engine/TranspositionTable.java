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
        
        // Modern approach: simpler and more aggressive replacement
        // Key principles:
        // 1. Always replace on key match
        // 2. For different position, probe() already selected the victim
        // 3. Preserve move from previous searches when we don't have one
        
        long existingBody = bodies[entryIndex];
        byte existingAbpv = keyMatch ? decodeAgeBoundPV(existingBody) : 0;
        short existingMove = keyMatch ? decodePackedMove(existingBody) : 0;
        boolean existingPV = keyMatch && formerPV(existingAbpv & 0xFF);
        
        // Preserve best move if we don't have one from current search
        short bestMove = (short) (move & 0xFFFF);
        if (bestMove == 0 && existingMove != 0) {
            bestMove = existingMove;
        }
        
        // Modern engines use simple depth-based replacement with small adjustments
        if (keyMatch) {
            // Same position - decide whether to update
            byte existingDepth = decodeDepth(existingBody);
            int existingBound = boundFromTT(existingAbpv & 0xFF);
            
            // Update conditions (following Stockfish logic):
            // 1. New search is deeper by at least 2 plies, OR
            // 2. New search has exact bound (most valuable), OR  
            // 3. Existing entry has upper bound (least valuable)
            // This gives slight preference to exact bounds and PV nodes
            boolean shouldUpdate = 
                depth + 2 >= existingDepth ||
                bound == BOUND_EXACT ||
                existingBound == BOUND_UPPER;
                
            if (!shouldUpdate) {
                // Keep existing entry but might need to refresh generation
                int currentGen = age & 0xFF;
                int existingGen = ageFromTT(existingAbpv & 0xFF);
                if (existingGen != currentGen) {
                    // Refresh generation to protect from replacement
                    byte refreshedAbpv = (byte) packToTT(existingBound, existingPV, currentGen);
                    long refreshedBody = encodeBody(existingMove, decodeScore(existingBody), 
                                                   decodeEval(existingBody), existingDepth, refreshedAbpv);
                    bodies[entryIndex] = refreshedBody;
                }
                return;
            }
        }
        
        // Store the new entry
        byte newDepth = (byte) clamp(depth, 0, 255);
        boolean newPV = isPV || existingPV;  // PV flag is sticky - once PV, always PV
        int currentGen = age & 0xFF;
        byte newAbpv = (byte) packToTT(bound, newPV, currentGen);
        
        // Handle mate scores - adjust relative to root
        int adjScore = (score == SCORE_VOID) ? score : scoreToTT(score, ply);
        short newScore = (short) clamp(adjScore, Short.MIN_VALUE, Short.MAX_VALUE);
        short newEval = (short) clamp(eval, Short.MIN_VALUE, Short.MAX_VALUE);
        
        // Pack and store the entry
        long newBody = encodeBody(bestMove, newScore, newEval, newDepth, newAbpv);
        bodies[entryIndex] = newBody;
        keys[entryIndex] = (short) wantKey;
    }

    public ProbeResult probe(long key) {
        if (bodies == null || numBuckets == 0) return new ProbeResult(0, false);
        
        // Modern engines prefetch here to warm CPU cache, but as requested, not implementing prefetch
        // prefetch(key); // Would prefetch the bucket into L1/L2 cache
        
        int bucket = (int) index(key);
        int base = setBase(bucket);
        int wantKey = (int) (key & 0xFFFFL);

        // First pass: look for exact key match
        for (int slot = 0; slot < SLOTS_PER_SET; slot++) {
            int idx = base + slot;
            short k = keys[idx];
            
            if ((k & 0xFFFF) == wantKey) {
                // Found matching key
                long body = bodies[idx];
                if (body == 0L) {
                    // Corrupted entry - treat as miss but keep slot
                    return new ProbeResult(idx, false);
                }
                return new ProbeResult(idx, true);
            }
        }
        
        // No key match found - select victim for replacement
        // Modern replacement strategy from Stockfish: 
        // Replace entry with minimum value = depth - 8*relative_age
        int replaceIdx = base;
        int minValue = Integer.MAX_VALUE;
        
        for (int slot = 0; slot < SLOTS_PER_SET; slot++) {
            int idx = base + slot;
            short k = keys[idx];
            
            // Empty slot - use immediately
            if (k == 0) {
                return new ProbeResult(idx, false);
            }
            
            long body = bodies[idx];
            byte abpv = decodeAgeBoundPV(body);
            int entryDepth = decodeDepth(body) & 0xFF;
            int entryAge = ageFromTT(abpv & 0xFF);
            int relativeAge = ((age - entryAge) & AGE_MASK);
            
            // Core replacement value: depth - 8 * relative_age
            // This strongly prefers replacing old entries (8x weight on age)
            int value = entryDepth - (relativeAge << 3);
            
            // Additional adjustments based on entry quality
            short score = decodeScore(body);
            int bound = boundFromTT(abpv & 0xFF);
            boolean wasPv = formerPV(abpv & 0xFF);
            
            // Entries with only static eval (no search) are much less valuable
            if (score == SCORE_VOID) {
                value -= 8;  // Significant penalty for eval-only entries
            }
            
            // Small adjustments for bound type and PV status
            // These are intentionally small to not override the age-based replacement
            if (bound == BOUND_EXACT) {
                value += 1;  // Exact bounds are slightly more valuable
            }
            if (wasPv) {
                value += 1;  // PV nodes get small protection
            }
            
            // Track entry with minimum value for replacement
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