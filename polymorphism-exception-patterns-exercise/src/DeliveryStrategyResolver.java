import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Small factory that maps each {@link DeliveryService} to its strategy object.
 *
 * <p>The strategies hold no state, so one shared instance per service is enough.
 * The map is filled once and can never be changed afterwards.</p>
 */
public final class DeliveryStrategyResolver {

    private static final Map<DeliveryService, DeliveryFeeStrategy> STRATEGIES;

    static {
        Map<DeliveryService, DeliveryFeeStrategy> strategies = new EnumMap<>(DeliveryService.class);
        strategies.put(DeliveryService.STANDARD, new StandardDeliveryStrategy());
        strategies.put(DeliveryService.EXPRESS, new ExpressDeliveryStrategy());
        strategies.put(DeliveryService.SAME_DAY, new SameDayDeliveryStrategy());
        // Fail fast if someone adds a new service to the enum but forgets its strategy.
        for (DeliveryService service : DeliveryService.values()) {
            if (!strategies.containsKey(service)) {
                throw new IllegalStateException("No delivery fee strategy registered for " + service);
            }
        }
        STRATEGIES = Collections.unmodifiableMap(strategies);
    }

    private DeliveryStrategyResolver() {
        // utility class: no instances
    }

    /** Returns the strategy for a service, or throws if no service was given. */
    public static DeliveryFeeStrategy resolve(DeliveryService service) throws InvalidDeliveryServiceException {
        if (service == null) {
            throw new InvalidDeliveryServiceException("A delivery service must be selected.");
        }
        return STRATEGIES.get(service);
    }
}
