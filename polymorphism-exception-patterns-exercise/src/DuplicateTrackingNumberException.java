/**
 * Thrown when a tracking number is already used by a registered parcel
 * (including parcels that have already been delivered).
 */
public class DuplicateTrackingNumberException extends ParcelSystemException {

    public DuplicateTrackingNumberException(String trackingNumber) {
        super("Tracking number " + trackingNumber + " already exists. Please use a different tracking number.");
    }
}
