/**
 * Base class for every recoverable, expected error in the Parcel Delivery System
 * (bad input, broken business rule, unknown parcel, ...).
 *
 * <p>It is a checked exception, so the compiler forces callers to decide how to
 * recover. Programming errors (bugs) are NOT represented by this class; they stay
 * as unchecked exceptions such as {@link IllegalStateException} so they are never
 * hidden by the menu's error handling.</p>
 */
public abstract class ParcelSystemException extends Exception {

    protected ParcelSystemException(String message) {
        super(message);
    }
}
