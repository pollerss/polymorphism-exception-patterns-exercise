/**
 * Thrown when a parcel attribute breaks a business rule, for example a badly
 * formatted tracking number, a blank receiver name, or a weight above the limit
 * for the parcel type.
 */
public class InvalidParcelException extends ParcelSystemException {

    public InvalidParcelException(String message) {
        super(message);
    }
}
