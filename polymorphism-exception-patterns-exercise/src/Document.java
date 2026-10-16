import java.math.BigDecimal;

/**
 * A document envelope.
 * <ul>
 *   <li>Actual weight: up to 0.5 kg.</li>
 *   <li>Pages: 1 to 100.</li>
 *   <li>Chargeable weight: the exact actual weight (never rounded).</li>
 *   <li>Base fee: flat Php 50.00.</li>
 * </ul>
 */
public final class Document extends Parcel {

    public static final BigDecimal MAX_DOCUMENT_WEIGHT_KG = new BigDecimal("0.5");
    public static final int MIN_PAGES = 1;
    public static final int MAX_PAGES = 100;
    public static final BigDecimal FLAT_BASE_FEE = Money.peso("50.00");

    private final int numberOfPages;

    public Document(String trackingNumber, String receiverName, BigDecimal actualWeightKg,
                    int numberOfPages, DeliveryService deliveryService)
            throws InvalidParcelException, InvalidDeliveryServiceException {
        super(trackingNumber, receiverName, actualWeightKg, MAX_DOCUMENT_WEIGHT_KG);
        this.numberOfPages = validateNumberOfPages(numberOfPages);
        initializeDeliveryService(deliveryService);
    }

    public static int validateNumberOfPages(int numberOfPages) throws InvalidParcelException {
        if (numberOfPages < MIN_PAGES || numberOfPages > MAX_PAGES) {
            throw new InvalidParcelException("Number of pages must be from " + MIN_PAGES + " to " + MAX_PAGES + ".");
        }
        return numberOfPages;
    }

    public int getNumberOfPages() {
        return numberOfPages;
    }

    @Override
    public String getTypeName() {
        return "Document";
    }

    /** Documents are charged by their exact actual weight; no rounding up. */
    @Override
    public BigDecimal getChargeableWeight() {
        return getActualWeight();
    }

    @Override
    public BigDecimal getBaseFee() {
        return FLAT_BASE_FEE;
    }
}
