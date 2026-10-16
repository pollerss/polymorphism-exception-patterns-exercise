import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A box measured in whole centimetres.
 * <ul>
 *   <li>Actual weight: up to 50 kg.</li>
 *   <li>Volumetric weight: (L x W x H) / 3500.0.</li>
 *   <li>Chargeable weight: ceil(max(actual weight, volumetric weight)).</li>
 *   <li>Base fee: Php 40.00 x chargeable weight.</li>
 * </ul>
 * {@link Fragile} is the only permitted subclass and reuses all of this.
 */
public sealed class Box extends Parcel permits Fragile {

    public static final BigDecimal MAX_BOX_WEIGHT_KG = new BigDecimal("50");
    /** cm^3 per chargeable kg. */
    public static final long VOLUMETRIC_DIVISOR = 3500L;
    public static final BigDecimal RATE_PER_CHARGEABLE_KG = Money.peso("40.00");

    private final int lengthCm;
    private final int widthCm;
    private final int heightCm;
    private final long volumeCm3;

    public Box(String trackingNumber, String receiverName, BigDecimal actualWeightKg,
               int lengthCm, int widthCm, int heightCm, DeliveryService deliveryService)
            throws InvalidParcelException, InvalidDeliveryServiceException {
        super(trackingNumber, receiverName, actualWeightKg, MAX_BOX_WEIGHT_KG);
        this.volumeCm3 = calculateVolume(lengthCm, widthCm, heightCm);
        this.lengthCm = lengthCm;
        this.widthCm = widthCm;
        this.heightCm = heightCm;
        // Last step: dimensions are known, so the chargeable weight (and the
        // Same Day check that depends on it) can now be evaluated safely.
        initializeDeliveryService(deliveryService);
    }

    /** Each dimension must be a positive whole number of centimetres. */
    public static int validateDimension(int valueCm, String dimensionName) throws InvalidParcelException {
        if (valueCm <= 0) {
            throw new InvalidParcelException(dimensionName + " must be greater than 0 cm.");
        }
        return valueCm;
    }

    /**
     * Validates the three dimensions and returns L x W x H in cm^3.
     * The product is computed in {@code long} with {@link Math#multiplyExact}, so a
     * combination too large to represent is rejected instead of silently wrapping
     * around to a wrong (even negative) number.
     */
    public static long calculateVolume(int lengthCm, int widthCm, int heightCm) throws InvalidParcelException {
        validateDimension(lengthCm, "Length");
        validateDimension(widthCm, "Width");
        validateDimension(heightCm, "Height");
        try {
            return Math.multiplyExact(Math.multiplyExact((long) lengthCm, widthCm), (long) heightCm);
        } catch (ArithmeticException overflow) {
            throw new InvalidParcelException("The box dimensions are too large: "
                    + "length x width x height exceeds the supported range.");
        }
    }

    public int getLengthCm() {
        return lengthCm;
    }

    public int getWidthCm() {
        return widthCm;
    }

    public int getHeightCm() {
        return heightCm;
    }

    /** Volumetric weight in kg, using floating-point division: (L x W x H) / 3500.0. */
    public double getVolumetricWeight() {
        return volumeCm3 / (double) VOLUMETRIC_DIVISOR;
    }

    @Override
    public String getTypeName() {
        return "Box";
    }

    /**
     * ceil(max(actual, volumetric)), returned as a whole number of kg.
     *
     * <p>Because ceil(max(a, b)) = max(ceil(a), ceil(b)), each side is rounded up
     * exactly: the actual weight with BigDecimal, and the volumetric weight with
     * whole-number division (volume / 3500 rounded up). This gives the same result
     * as {@code Math.ceil(Math.max(actual, volume / 3500.0))} but cannot be fooled
     * by floating-point error on huge volumes.</p>
     *
     * <p>{@code final}: Fragile must use exactly this calculation.</p>
     */
    @Override
    public final BigDecimal getChargeableWeight() {
        long actualRoundedUp = getActualWeight().setScale(0, RoundingMode.CEILING).longValueExact();
        long volumetricRoundedUp = volumeCm3 / VOLUMETRIC_DIVISOR
                + (volumeCm3 % VOLUMETRIC_DIVISOR == 0 ? 0 : 1);
        return BigDecimal.valueOf(Math.max(actualRoundedUp, volumetricRoundedUp));
    }

    /** Php 40.00 x chargeable weight. */
    @Override
    public BigDecimal getBaseFee() {
        return Money.round(RATE_PER_CHARGEABLE_KG.multiply(getChargeableWeight()));
    }
}
