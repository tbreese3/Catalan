package org.engine;

public final class MoveFactory {

  public static final int FLAG_NORMAL     = 0;
  public static final int FLAG_PROMOTION  = 1;
  public static final int FLAG_EN_PASSANT = 2;
  public static final int FLAG_CASTLE     = 3;

  private MoveFactory() {}

  public static int Create(int from, int to, int flags) {
    return Create(from, to, flags, 0);
  }

  public static int Create(int from, int to, int flags, int promotion) {
    return (from << 6) | to | (promotion << 12) | (flags << 14);
  }

  public static int GetFrom(int mv) {
    return (mv >>> 6) & 0x3F;
  }

  public static int GetTo(int mv) {
    return mv & 0x3F;
  }

  public static int GetPromotion(int mv) {
    return (mv >>> 12) & 0x3;
  }

  public static int GetFlags(int mv) {
    return (mv >>> 14) & 0x3;
  }
}

