/**
 * Thrown when a delivery service cannot be used for a parcel: no service was
 * given, or the parcel is not eligible (Same Day for parcels above 10 kg
 * chargeable weight).
 */
public class InvalidDeliveryServiceException extends ParcelSystemException {

    public InvalidDeliveryServiceException(String message) {
        super(message);
    }
}
