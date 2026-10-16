/**
 * Thrown when console text cannot be interpreted at all, for example letters typed
 * where a number is expected, a decimal typed where a whole number is expected,
 * or a menu choice outside the listed options.
 */
public class InvalidInputException extends ParcelSystemException {

    public InvalidInputException(String message) {
        super(message);
    }
}
