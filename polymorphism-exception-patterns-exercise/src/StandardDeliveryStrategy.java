import java.math.BigDecimal;

/** STANDARD: Delivery Fee = Base Fee. */
public final class StandardDeliveryStrategy implements DeliveryFeeStrategy {

    @Override
    public BigDecimal calculateFee(BigDecimal baseFee) {
        Money.requireNonNegative(baseFee, "Base fee");
        return Money.round(baseFee);
    }
}
