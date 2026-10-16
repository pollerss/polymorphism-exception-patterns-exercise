import java.math.BigDecimal;

/**
 * A fragile box. It IS a {@link Box}: it inherits the dimensions, volumetric
 * weight and chargeable-weight calculation unchanged, and only overrides what is
 * different:
 * <ul>
 *   <li>Actual weight limit: 30 kg (instead of 50 kg).</li>
 *   <li>Extra attribute: a non-blank handling note.</li>
 *   <li>Base fee: the Box base fee + Php 100.00 handling fee.</li>
 * </ul>
 */
public final class Fragile extends Box {

    public static final BigDecimal MAX_FRAGILE_WEIGHT_KG = new BigDecimal("30");
    public static final BigDecimal HANDLING_FEE = Money.peso("100.00");
    public static final int MAX_HANDLING_NOTE_LENGTH = 200;

    private final String handlingNote;

    public Fragile(String trackingNumber, String receiverName, BigDecimal actualWeightKg,
                   int lengthCm, int widthCm, int heightCm, String handlingNote,
                   DeliveryService deliveryService)
            throws InvalidParcelException, InvalidDeliveryServiceException {
        // The stricter 30 kg limit is checked first; the Box constructor then
        // applies all the normal Box rules (including its own 50 kg limit).
        super(trackingNumber, receiverName, validateActualWeight(actualWeightKg, MAX_FRAGILE_WEIGHT_KG),
                lengthCm, widthCm, heightCm, deliveryService);
        this.handlingNote = validateHandlingNote(handlingNote);
    }

    /** Trims surrounding spaces and rejects a blank note. */
    public static String validateHandlingNote(String handlingNote) throws InvalidParcelException {
        return validateRequiredText(handlingNote, "Handling note", MAX_HANDLING_NOTE_LENGTH);
    }

    public String getHandlingNote() {
        return handlingNote;
    }

    @Override
    public String getTypeName() {
        return "Fragile";
    }

    /** Box base fee (Php 40.00 x chargeable weight) plus the Php 100.00 handling fee. */
    @Override
    public BigDecimal getBaseFee() {
        return Money.round(super.getBaseFee().add(HANDLING_FEE));
    }
}
