package org.engine;

public final class MoveFactory {

  public static final int MOVE_NONE      = 0;
  public static final int FLAG_NORMAL      = 0;
  public static final int FLAG_PROMOTION   = 1;
  public static final int FLAG_EN_PASSANT  = 2;
  public static final int FLAG_CASTLE      = 3;

  public static final int PROMOTION_KNIGHT = 0;
  public static final int PROMOTION_BISHOP = 1;
  public static final int PROMOTION_ROOK   = 2;
  public static final int PROMOTION_QUEEN  = 3;

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

  public static int intToMove(int raw) {
    return raw & 0xFFFF;
  }

  public static boolean isNone(int mv) {
    return (mv & 0xFFFF) == 0;
  }

  public static String moveToUci(int mv) {
    int from = GetFrom(mv);
    int to = GetTo(mv);
    int promo = GetPromotion(mv);
    int flags = GetFlags(mv);
    char a = (char) ('a' + (from & 7));
    char b = (char) ('1' + (from >>> 3));
    char c = (char) ('a' + (to & 7));
    char d = (char) ('1' + (to >>> 3));
    if (flags != FLAG_PROMOTION) return "" + a + b + c + d;
    char p = switch (promo) {
      case PROMOTION_KNIGHT -> 'n';
      case PROMOTION_BISHOP -> 'b';
      case PROMOTION_ROOK -> 'r';
      case PROMOTION_QUEEN -> 'q';
      default -> 'q'; };
    return "" + a + b + c + d + p;
  }
}

