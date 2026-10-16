import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The single money policy of the application (Philippine peso).
 *
 * <ul>
 *   <li>All amounts are {@link BigDecimal}, never {@code double}.</li>
 *   <li>Every stored or displayed amount has exactly 2 decimal places.</li>
 *   <li>When a calculation produces more than 2 decimal places (for example
 *       Express: Php 0.01 x 1.5 = 0.015) it is rounded once, at the end,
 *       using {@link RoundingMode#HALF_UP} (0.015 becomes 0.02).</li>
 * </ul>
 */
public final class Money {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;
    private static final String CURRENCY_PREFIX = "Php ";

    private Money() {
        // utility class: no instances
    }

    /** Creates a peso amount from text, e.g. {@code peso("40.00")}. */
    public static BigDecimal peso(String amount) {
        return round(new BigDecimal(amount));
    }

    /** Applies the money policy: 2 decimal places, HALF_UP. */
    public static BigDecimal round(BigDecimal amount) {
        return requireNonNegative(amount, "Amount").setScale(SCALE, ROUNDING_MODE);
    }

    /** Formats an amount for display, always with 2 decimals, e.g. {@code Php 330.00}. */
    public static String format(BigDecimal amount) {
        return CURRENCY_PREFIX + round(amount).toPlainString();
    }

    /**
     * Guards against programming errors (a null or negative amount reaching a fee
     * calculation). These can never come from user input, so they are unchecked.
     */
    public static BigDecimal requireNonNegative(BigDecimal amount, String label) {
        if (amount == null) {
            throw new IllegalArgumentException(label + " must not be null.");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException(label + " must not be negative.");
        }
        return amount;
    }
}
