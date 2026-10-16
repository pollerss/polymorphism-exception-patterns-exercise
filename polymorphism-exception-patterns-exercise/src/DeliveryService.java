/**
 * The delivery services a customer can choose.
 *
 * <p>The enum only names the service. How the fee is calculated (and whether a
 * parcel is eligible) is the job of the matching strategy object, see
 * {@link DeliveryStrategyResolver}.</p>
 */
public enum DeliveryService {
    STANDARD("STANDARD"),
    EXPRESS("EXPRESS"),
    SAME_DAY("SAME DAY");

    private final String displayName;

    DeliveryService(String displayName) {
        this.displayName = displayName;
    }

    /** The name shown to users, e.g. {@code SAME DAY}. */
    public String getDisplayName() {
        return displayName;
    }
}
