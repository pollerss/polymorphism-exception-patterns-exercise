import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Common superclass of every parcel type.
 *
 * <h2>Polymorphism</h2>
 * Each subclass supplies its own {@link #getTypeName()}, {@link #getChargeableWeight()}
 * and {@link #getBaseFee()}. Code that holds a plain {@code Parcel} reference (the
 * manager, the table printer, {@link #getDeliveryFee()}) automatically runs the
 * correct subclass version at runtime, with no {@code instanceof} chains.
 *
 * <h2>Always valid</h2>
 * The class is {@code sealed}: only {@link Document} and {@link Box} (and through
 * Box, {@link Fragile}) can extend it, so nobody can write a subclass that skips
 * the rules. Every rule is checked in the constructors, so an invalid parcel can
 * never exist, no matter whether it is created from the menu or directly in code.
 *
 * <h2>Controlled state changes</h2>
 * Tracking number, receiver, and weight are {@code final}. The only things that can
 * change are the delivery service (through {@link #changeDeliveryService}) and the
 * status (through {@link #markAsDelivered}), and both refuse to touch a delivered
 * parcel. There are no public setters.
 */
public abstract sealed class Parcel permits Document, Box {

    /** "ABC" followed by exactly four ASCII digits; nothing before or after. */
    private static final Pattern TRACKING_NUMBER_FORMAT = Pattern.compile("^ABC[0-9]{4}$");

    public static final int MAX_RECEIVER_NAME_LENGTH = 100;
    /** Weights are accepted down to the gram: at most 3 decimal places. */
    public static final int MAX_WEIGHT_DECIMAL_PLACES = 3;

    private final String trackingNumber;
    private final String receiverName;
    private final BigDecimal actualWeightKg;

    private ParcelStatus status;
    // The service and the strategy that implements it are always changed together
    // (see applyDeliveryService), so they can never disagree.
    private DeliveryService deliveryService;
    private DeliveryFeeStrategy deliveryFeeStrategy;

    /**
     * Not public, and Parcel is abstract and sealed, so only the Document and
     * Box constructors can ever call it.
     *
     * @param maxActualWeightKg the actual-weight limit of the concrete parcel type
     */
    Parcel(String trackingNumber, String receiverName, BigDecimal actualWeightKg,
           BigDecimal maxActualWeightKg) throws InvalidParcelException {
        this.trackingNumber = validateTrackingNumber(trackingNumber);
        this.receiverName = validateReceiverName(receiverName);
        this.actualWeightKg = validateActualWeight(actualWeightKg, maxActualWeightKg);
        this.status = ParcelStatus.PENDING; // new parcels are always PENDING
    }

    // ------------------------------------------------------------------
    // Polymorphic behaviour, implemented differently by each subclass
    // ------------------------------------------------------------------

    /** Human-readable type, e.g. "Document", "Box", "Fragile". */
    public abstract String getTypeName();

    /** The weight (kg) used for pricing and for the Same Day limit. */
    public abstract BigDecimal getChargeableWeight();

    /** The fee before the delivery service is applied (2 decimal places). */
    public abstract BigDecimal getBaseFee();

    /**
     * The final fee: the current delivery strategy applied to the base fee.
     * It is recalculated from the base fee every time, so switching services
     * back and forth can never multiply an already-multiplied amount.
     */
    public final BigDecimal getDeliveryFee() {
        return deliveryFeeStrategy.calculateFee(getBaseFee());
    }

    // ------------------------------------------------------------------
    // Controlled state changes
    // ------------------------------------------------------------------

    /**
     * Sets the first delivery service. Called exactly once, at the end of the
     * concrete constructors, after every field that affects the chargeable weight
     * has been validated and assigned.
     */
    final void initializeDeliveryService(DeliveryService service) throws InvalidDeliveryServiceException {
        if (deliveryFeeStrategy != null) {
            throw new IllegalStateException("The delivery service has already been initialized.");
        }
        applyDeliveryService(service);
    }

    /**
     * Switches to another delivery service. If anything is wrong (parcel delivered,
     * no service given, Same Day not allowed) an exception is thrown and the
     * parcel keeps its previous service and fee unchanged.
     */
    public final void changeDeliveryService(DeliveryService newService)
            throws ParcelAlreadyDeliveredException, InvalidDeliveryServiceException {
        if (isDelivered()) {
            throw new ParcelAlreadyDeliveredException(trackingNumber);
        }
        applyDeliveryService(newService);
    }

    /** PENDING -> DELIVERED. A delivered parcel cannot be delivered again. */
    public final void markAsDelivered() throws ParcelAlreadyDeliveredException {
        if (isDelivered()) {
            throw new ParcelAlreadyDeliveredException(trackingNumber);
        }
        status = ParcelStatus.DELIVERED;
    }

    /** Validate first, assign afterwards: a rejected service never replaces the current one. */
    private void applyDeliveryService(DeliveryService newService) throws InvalidDeliveryServiceException {
        DeliveryFeeStrategy newStrategy = DeliveryStrategyResolver.resolve(newService);
        newStrategy.checkEligibility(getChargeableWeight());
        this.deliveryService = newService;
        this.deliveryFeeStrategy = newStrategy;
    }

    // ------------------------------------------------------------------
    // Getters (no setters on purpose)
    // ------------------------------------------------------------------

    public final String getTrackingNumber() {
        return trackingNumber;
    }

    public final String getReceiverName() {
        return receiverName;
    }

    public final BigDecimal getActualWeight() {
        return actualWeightKg;
    }

    public final ParcelStatus getStatus() {
        return status;
    }

    public final boolean isDelivered() {
        return status == ParcelStatus.DELIVERED;
    }

    public final DeliveryService getDeliveryService() {
        return deliveryService;
    }

    // ------------------------------------------------------------------
    // Reusable validation rules. The constructors use them, and the console
    // uses the very same methods to re-prompt one field at a time, so each
    // rule is written exactly once.
    // ------------------------------------------------------------------

    /**
     * Accepts only the exact format {@code ABC} + four digits. The value is NOT
     * trimmed or upper-cased: " ABC0001" and "abc0001" are rejected, not "fixed".
     */
    public static String validateTrackingNumber(String trackingNumber) throws InvalidParcelException {
        if (trackingNumber == null || trackingNumber.isEmpty()) {
            throw new InvalidParcelException("Tracking number cannot be empty.");
        }
        if (!TRACKING_NUMBER_FORMAT.matcher(trackingNumber).matches()) {
            throw new InvalidParcelException("Invalid tracking number. Use uppercase ABC followed by exactly "
                    + "four digits, with no spaces (example: ABC0001).");
        }
        return trackingNumber;
    }

    /** Trims surrounding spaces and rejects blank names. Apostrophes, hyphens etc. are allowed. */
    public static String validateReceiverName(String receiverName) throws InvalidParcelException {
        return validateRequiredText(receiverName, "Receiver name", MAX_RECEIVER_NAME_LENGTH);
    }

    /**
     * Checks an actual weight against a parcel type's limit.
     * Must be &gt; 0, at most {@value #MAX_WEIGHT_DECIMAL_PLACES} decimal places,
     * and &lt;= {@code maxActualWeightKg}. {@link BigDecimal} cannot hold NaN or
     * Infinity, and comparisons are exact, so 0.5 is never confused with 0.50000001.
     */
    public static BigDecimal validateActualWeight(BigDecimal weightKg, BigDecimal maxActualWeightKg)
            throws InvalidParcelException {
        Objects.requireNonNull(maxActualWeightKg, "maxActualWeightKg");
        if (weightKg == null) {
            throw new InvalidParcelException("Weight is required.");
        }
        if (weightKg.signum() <= 0) {
            throw new InvalidParcelException("Weight must be greater than 0 kg.");
        }
        if (weightKg.stripTrailingZeros().scale() > MAX_WEIGHT_DECIMAL_PLACES) {
            throw new InvalidParcelException("Weight can have at most " + MAX_WEIGHT_DECIMAL_PLACES
                    + " decimal places (1 gram precision).");
        }
        if (weightKg.compareTo(maxActualWeightKg) > 0) {
            throw new InvalidParcelException("Weight must not exceed "
                    + maxActualWeightKg.stripTrailingZeros().toPlainString() + " kg for this parcel type.");
        }
        return weightKg;
    }

    /**
     * Shared rule for free-text fields (receiver name, handling note):
     * trimmed, not blank, no control characters such as tabs, bounded length.
     */
    static String validateRequiredText(String value, String fieldName, int maxLength) throws InvalidParcelException {
        if (value == null) {
            throw new InvalidParcelException(fieldName + " cannot be empty.");
        }
        String trimmed = trimSpaces(value);
        boolean hasVisibleCharacter = trimmed.codePoints().anyMatch(Parcel::isVisible);
        if (!hasVisibleCharacter) {
            throw new InvalidParcelException(fieldName + " cannot be empty or contain only spaces.");
        }
        if (trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw new InvalidParcelException(fieldName + " must not contain tabs or other control characters.");
        }
        if (trimmed.codePointCount(0, trimmed.length()) > maxLength) {
            throw new InvalidParcelException(fieldName + " must be at most " + maxLength + " characters long.");
        }
        return trimmed;
    }

    /** Space-like characters, including non-breaking spaces that String.trim() ignores. */
    private static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    /** A character that actually shows up on screen (not a space, control or invisible format character). */
    private static boolean isVisible(int codePoint) {
        return !isSpace(codePoint)
                && !Character.isISOControl(codePoint)
                && Character.getType(codePoint) != Character.FORMAT;
    }

    private static String trimSpaces(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }
}
