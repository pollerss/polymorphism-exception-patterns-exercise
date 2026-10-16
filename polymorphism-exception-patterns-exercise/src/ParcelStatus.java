/**
 * Lifecycle of a parcel. A parcel always starts as {@link #PENDING} and can move
 * to {@link #DELIVERED} exactly once; there is no way back.
 */
public enum ParcelStatus {
    PENDING,
    DELIVERED
}
