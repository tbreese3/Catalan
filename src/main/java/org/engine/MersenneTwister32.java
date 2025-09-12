package org.engine;

public final class MersenneTwister32 {
    private static final int N = 624;
    private static final int M = 397;
    private static final long MATRIX_A = 0x9908b0dfL;
    private static final long UPPER_MASK = 0x80000000L;
    private static final long LOWER_MASK = 0x7fffffffL;

    private final long[] mt = new long[N];
    private int mti = N + 1;

    public MersenneTwister32(long seed) {
        initGenRand(seed);
    }

    private void initGenRand(long s) {
        s &= 0xFFFFFFFFL;
        mt[0] = s;
        for (mti = 1; mti < N; mti++) {
            mt[mti] = (1812433253L * (mt[mti - 1] ^ (mt[mti - 1] >>> 30)) + mti);
            mt[mti] &= 0xffffffffL;
        }
    }

    private long next32() {
        long y;
        long[] mag01 = {0x0L, MATRIX_A};

        if (mti >= N) {
            int kk;
            if (mti == N + 1)
                initGenRand(5489L);

            for (kk = 0; kk < N - M; kk++) {
                y = (mt[kk] & UPPER_MASK) | (mt[kk + 1] & LOWER_MASK);
                mt[kk] = mt[kk + M] ^ (y >>> 1) ^ mag01[(int) (y & 1L)];
            }
            for (; kk < N - 1; kk++) {
                y = (mt[kk] & UPPER_MASK) | (mt[kk + 1] & LOWER_MASK);
                mt[kk] = mt[kk + (M - N)] ^ (y >>> 1) ^ mag01[(int) (y & 1L)];
            }
            y = (mt[N - 1] & UPPER_MASK) | (mt[0] & LOWER_MASK);
            mt[N - 1] = mt[M - 1] ^ (y >>> 1) ^ mag01[(int) (y & 1L)];

            mti = 0;
        }

        y = mt[mti++];

        y ^= (y >>> 11);
        y ^= (y << 7) & 0x9d2c5680L;
        y ^= (y << 15) & 0xefc60000L;
        y ^= (y >>> 18);

        return y & 0xFFFFFFFFL;
    }

    public long nextLong() {
        long lower = next32();
        long upper = next32();
        return (upper << 32) | lower;
    }
}
