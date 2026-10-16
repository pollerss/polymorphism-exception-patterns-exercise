/**
 * Thrown when an operation would modify a parcel that is already DELIVERED
 * (changing its service, or marking it delivered a second time).
 */
public class ParcelAlreadyDeliveredException extends ParcelSystemException {

    public ParcelAlreadyDeliveredException(String trackingNumber) {
        super("Parcel " + trackingNumber + " has already been delivered and can no longer be changed.");
    }
}
