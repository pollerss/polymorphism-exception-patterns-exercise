import java.math.BigDecimal;

/** EXPRESS: Delivery Fee = Base Fee x 1.5. */
public final class ExpressDeliveryStrategy implements DeliveryFeeStrategy {

    public static final BigDecimal MULTIPLIER = new BigDecimal("1.5");

    @Override
    public BigDecimal calculateFee(BigDecimal baseFee) {
        Money.requireNonNegative(baseFee, "Base fee");
        return Money.round(baseFee.multiply(MULTIPLIER));
    }
}
