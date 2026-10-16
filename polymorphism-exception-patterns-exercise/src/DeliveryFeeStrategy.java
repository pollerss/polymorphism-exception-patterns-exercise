import java.math.BigDecimal;

/**
 * Strategy pattern: one interchangeable object per delivery service.
 *
 * <p>A parcel holds a reference to one strategy and simply asks it for the fee.
 * Swapping the delivery service means swapping the strategy object; the parcel
 * classes never contain a switch over the services.</p>
 */
public interface DeliveryFeeStrategy {

    /**
     * Calculates the final delivery fee from a parcel's base fee.
     * The result follows the {@link Money} policy (2 decimals, HALF_UP).
     *
     * @throws IllegalArgumentException if {@code baseFee} is null or negative (a programming error)
     */
    BigDecimal calculateFee(BigDecimal baseFee);

    /**
     * Checks whether a parcel with this chargeable weight may use the service.
     * Most services accept every parcel, so the default does nothing; a strategy
     * with a restriction (Same Day) overrides it.
     */
    default void checkEligibility(BigDecimal chargeableWeightKg) throws InvalidDeliveryServiceException {
        // no restriction by default
    }
}
