import java.math.BigDecimal;

/**
 * SAME DAY: Delivery Fee = (Base Fee x 2) + Php 100.00.
 * Only allowed when the parcel's CHARGEABLE weight is at most 10 kg.
 */
public final class SameDayDeliveryStrategy implements DeliveryFeeStrategy {

    public static final BigDecimal MULTIPLIER = new BigDecimal("2");
    public static final BigDecimal SURCHARGE = Money.peso("100.00");
    public static final BigDecimal MAX_CHARGEABLE_WEIGHT_KG = new BigDecimal("10");
    public static final String NOT_ELIGIBLE_MESSAGE = "Same Day delivery is only for parcels up to 10 kg!";

    @Override
    public BigDecimal calculateFee(BigDecimal baseFee) {
        Money.requireNonNegative(baseFee, "Base fee");
        return Money.round(baseFee.multiply(MULTIPLIER).add(SURCHARGE));
    }

    @Override
    public void checkEligibility(BigDecimal chargeableWeightKg) throws InvalidDeliveryServiceException {
        if (chargeableWeightKg == null || chargeableWeightKg.signum() <= 0) {
            throw new IllegalArgumentException("Chargeable weight must be a positive number.");
        }
        if (chargeableWeightKg.compareTo(MAX_CHARGEABLE_WEIGHT_KG) > 0) {
            throw new InvalidDeliveryServiceException(NOT_ELIGIBLE_MESSAGE);
        }
    }
}
