import java.util.ArrayList;
import java.util.List;

/**
 * Singleton that owns the one application-wide list of parcels.
 *
 * <ul>
 *   <li>Eager initialization: the single instance is created when the class is
 *       loaded, which is simple and thread-safe.</li>
 *   <li>The constructor is private, so {@code new ParcelManager()} does not compile
 *       outside this class. It also refuses to run a second time, so even a
 *       reflection trick cannot create another instance.</li>
 *   <li>The internal list is never handed out. {@link #getAllParcels()} returns a
 *       read-only copy, so the only way to add parcels is {@link #addParcel}, which
 *       enforces unique tracking numbers.</li>
 * </ul>
 */
public final class ParcelManager {

    private static final ParcelManager INSTANCE = new ParcelManager();

    private final List<Parcel> parcels = new ArrayList<>();

    private ParcelManager() {
        if (INSTANCE != null) {
            throw new IllegalStateException("ParcelManager is a singleton. Use ParcelManager.getInstance().");
        }
    }

    /** The one and only access point. */
    public static ParcelManager getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a fully built parcel. Parcels can only be constructed in a valid
     * state, so a parcel that reaches this method is already complete.
     * Tracking numbers must be unique, including against delivered parcels.
     */
    public synchronized void addParcel(Parcel parcel) throws InvalidParcelException, DuplicateTrackingNumberException {
        if (parcel == null) {
            throw new InvalidParcelException("Parcel cannot be empty.");
        }
        if (isTrackingNumberRegistered(parcel.getTrackingNumber())) {
            throw new DuplicateTrackingNumberException(parcel.getTrackingNumber());
        }
        parcels.add(parcel);
    }

    public synchronized boolean isTrackingNumberRegistered(String trackingNumber) {
        return findParcel(trackingNumber) != null;
    }

    public synchronized Parcel getParcel(String trackingNumber) throws ParcelNotFoundException {
        Parcel parcel = findParcel(trackingNumber);
        if (parcel == null) {
            throw new ParcelNotFoundException(trackingNumber);
        }
        return parcel;
    }

    /** True when at least one parcel (pending or delivered) is registered. */
    public synchronized boolean hasParcels() {
        return !parcels.isEmpty();
    }

    /** Total number of registered parcels, delivered ones included. */
    public synchronized int getParcelCount() {
        return parcels.size();
    }

    /**
     * Counts the parcels with the given status. It is worked out from the list
     * every time (no separate counters), so it can never be out of date.
     */
    public synchronized int countByStatus(ParcelStatus status) {
        int count = 0;
        for (Parcel parcel : parcels) {
            if (parcel.getStatus() == status) {
                count++;
            }
        }
        return count;
    }

    /** A read-only snapshot. Adding or removing through it throws UnsupportedOperationException. */
    public synchronized List<Parcel> getAllParcels() {
        return List.copyOf(parcels);
    }

    /**
     * Changes the service of a registered parcel. The parcel itself refuses the
     * change if it is delivered or not eligible, leaving the old service and fee.
     */
    public synchronized Parcel changeDeliveryService(String trackingNumber, DeliveryService newService)
            throws ParcelNotFoundException, ParcelAlreadyDeliveredException, InvalidDeliveryServiceException {
        Parcel parcel = getParcel(trackingNumber);
        parcel.changeDeliveryService(newService);
        return parcel;
    }

    /** Marks a registered parcel as delivered. It stays in the list afterwards. */
    public synchronized Parcel markAsDelivered(String trackingNumber)
            throws ParcelNotFoundException, ParcelAlreadyDeliveredException {
        Parcel parcel = getParcel(trackingNumber);
        parcel.markAsDelivered();
        return parcel;
    }

    private Parcel findParcel(String trackingNumber) {
        if (trackingNumber == null) {
            return null;
        }
        for (Parcel parcel : parcels) {
            if (parcel.getTrackingNumber().equals(trackingNumber)) {
                return parcel;
            }
        }
        return null;
    }
}
