/**
 * Thrown when no registered parcel has the requested tracking number.
 */
public class ParcelNotFoundException extends ParcelSystemException {

    public ParcelNotFoundException(String trackingNumber) {
        super("No parcel is registered with tracking number " + trackingNumber + ".");
    }
}
