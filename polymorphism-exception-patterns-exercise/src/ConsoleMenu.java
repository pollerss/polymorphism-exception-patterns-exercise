import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * The menu-driven console interface. It only collects input and prints results;
 * every business rule is enforced by the model classes and {@link ParcelManager}.
 *
 * <p>Navigation at any question:</p>
 * <ul>
 *   <li>BACK (or 0 in a numbered submenu) goes back exactly one step. From the
 *       first question of an operation, the previous screen is the main menu.</li>
 *   <li>CANCEL abandons the whole operation and returns to the main menu.</li>
 *   <li>When a question is asked again, the earlier answer is shown and Enter keeps it.</li>
 * </ul>
 */
public class ConsoleMenu {

    // Messages required word-for-word by the specification.
    public static final String MSG_PARCEL_NOT_FOUND = "Parcel not found!";
    public static final String MSG_CHANGE_ALREADY_DELIVERED = "Parcel already delivered!";
    public static final String MSG_MARK_ALREADY_DELIVERED = "Parcel was already delivered.";
    public static final String MSG_DELIVERED_SUCCESSFULLY = "Parcel delivered successfully!";
    public static final String MSG_ADDED_SUCCESSFULLY = "Parcel added successfully! Delivery fee: ";
    public static final String MSG_SERVICE_CHANGED = "Delivery service changed! New fee: ";
    public static final String MSG_NO_PARCELS = "No parcels found.";
    public static final String MSG_EMPTY_INVENTORY = "No parcels in inventory. Please add a parcel first.";
    public static final String MSG_GOODBYE = "Thank you for using the Parcel Delivery System. Goodbye!";
    public static final String MSG_INPUT_ENDED = "No more input. Exiting the Parcel Delivery System.";

    // Navigation messages.
    public static final String MSG_CANCELLED = "Operation cancelled. Returning to main menu...";
    public static final String MSG_BACK_TO_MAIN_MENU = "Going back to the main menu...";
    public static final String MSG_STEP_BACK = "Going back to the previous step...";
    public static final String NAVIGATION_HINT =
            "Type BACK to go to the previous step, or CANCEL to return to the main menu.";

    /** Submenu option that goes back one screen. */
    private static final int BACK_OPTION = 0;

    private static final String DOUBLE_LINE = "========================================";
    private static final String SINGLE_LINE = "----------------------------------------";

    private static final int MENU_ADD_PARCEL = 1;
    private static final int MENU_VIEW_PARCELS = 2;
    private static final int MENU_CHANGE_SERVICE = 3;
    private static final int MENU_MARK_DELIVERED = 4;
    private static final int MENU_EXIT = 5;

    private static final int TYPE_DOCUMENT = 1;
    private static final int TYPE_BOX = 2;
    private static final int TYPE_FRAGILE = 3;

    /** Longer receiver names are shortened with "..." in the table only. */
    static final int MAX_RECEIVER_COLUMN_WIDTH = 25;

    /** The questions of Add Parcel, in the order they are asked. */
    private enum AddStep {
        PARCEL_TYPE, TRACKING_NUMBER, RECEIVER_NAME, WEIGHT,
        PAGES, LENGTH, WIDTH, HEIGHT, HANDLING_NOTE, DELIVERY_SERVICE
    }

    /**
     * The answers collected so far while adding a parcel. It is only a scratch
     * pad: it keeps answers while the user moves back and forth, and nothing in it
     * is saved until a complete, valid parcel is built from it at the very last step.
     */
    private static final class ParcelDraft {
        Integer type;
        String trackingNumber;
        String receiverName;
        BigDecimal weightKg;
        Integer pages;
        Integer lengthCm;
        Integer widthCm;
        Integer heightCm;
        String handlingNote;

        /** Creates the real parcel. The constructors re-check every rule. */
        Parcel build(DeliveryService service) throws InvalidParcelException, InvalidDeliveryServiceException {
            return switch (type) {
                case TYPE_DOCUMENT -> new Document(trackingNumber, receiverName, weightKg, pages, service);
                case TYPE_BOX -> new Box(trackingNumber, receiverName, weightKg,
                        lengthCm, widthCm, heightCm, service);
                case TYPE_FRAGILE -> new Fragile(trackingNumber, receiverName, weightKg,
                        lengthCm, widthCm, heightCm, handlingNote, service);
                default -> throw new IllegalStateException("Unexpected parcel type: " + type);
            };
        }
    }

    private final InputValidator input;
    private final PrintStream out;
    private final ParcelManager manager;

    public ConsoleMenu(Scanner scanner, PrintStream out) {
        this.input = new InputValidator(scanner, out);
        this.out = out;
        this.manager = ParcelManager.getInstance(); // the same instance for every operation
    }

    /** Shows the menu until the user exits or the input ends. */
    public void run() {
        try {
            boolean running = true;
            while (running) {
                printMainMenu(); // recalculated every time, so the summary is never out of date
                int choice = input.promptUntilValid("Enter your choice: ",
                        raw -> InputValidator.parseMenuChoice(raw, MENU_ADD_PARCEL, MENU_EXIT));
                try {
                    switch (choice) {
                        case MENU_ADD_PARCEL -> addParcel();
                        case MENU_VIEW_PARCELS -> viewAllParcels();
                        case MENU_CHANGE_SERVICE -> changeDeliveryService();
                        case MENU_MARK_DELIVERED -> markAsDelivered();
                        case MENU_EXIT -> {
                            out.println(MSG_GOODBYE);
                            running = false;
                        }
                        default -> throw new IllegalStateException("Unexpected menu choice: " + choice);
                    }
                } catch (OperationCancelledException cancelled) {
                    // The user typed CANCEL. Nothing has been saved or changed.
                    out.println(MSG_CANCELLED);
                }
            }
        } catch (EndOfInputException endOfInput) {
            // Whatever operation was in progress has saved nothing yet; just leave.
            out.println();
            out.println(MSG_INPUT_ENDED);
        }
    }

    private void printMainMenu() {
        // Options 3 and 4 need at least one parcel (pending or delivered).
        String availability = manager.hasParcels() ? "" : " [UNAVAILABLE]";
        out.println();
        out.println(DOUBLE_LINE);
        out.println("       PARCEL DELIVERY SYSTEM");
        out.println(DOUBLE_LINE);
        printInventorySummary("INVENTORY SUMMARY");
        out.println(SINGLE_LINE);
        out.println("1. Add Parcel");
        out.println("2. View Inventory / All Parcels");
        out.println("3. Change Delivery Service" + availability);
        out.println("4. Mark as Delivered" + availability);
        out.println("5. Exit");
        out.println(DOUBLE_LINE);
    }

    /** Counts come straight from the manager's list, never from separate counters. */
    private void printInventorySummary(String title) {
        out.println(title);
        out.println("Total Parcels: " + manager.getParcelCount());
        out.println("Pending: " + manager.countByStatus(ParcelStatus.PENDING));
        out.println("Delivered: " + manager.countByStatus(ParcelStatus.DELIVERED));
    }

    /**
     * Builds a question. Without an earlier answer: "Enter receiver name (BACK/CANCEL): ".
     * With one: "Enter receiver name [Ana] (Enter = keep, BACK/CANCEL): ".
     */
    private static String question(String text, String previousAnswer) {
        if (previousAnswer == null) {
            return text + " (BACK/CANCEL): ";
        }
        return text + " [" + previousAnswer + "] (Enter = keep, BACK/CANCEL): ";
    }

    /** The text of an earlier answer, or null if there is none yet. */
    private static String text(Object answer) {
        return answer == null ? null : answer.toString();
    }

    // ------------------------------------------------------------------
    // 1. Add Parcel
    // ------------------------------------------------------------------

    /**
     * Asks the questions in {@link AddStep} order. A valid answer moves forward one
     * step; BACK moves back one step and keeps the answers given so far. The parcel
     * is only built and saved at the last step, after a delivery service is accepted.
     */
    private void addParcel() {
        out.println();
        out.println("--- Add Parcel ---");
        out.println(NAVIGATION_HINT);
        ParcelDraft draft = new ParcelDraft();
        List<AddStep> steps = List.of(AddStep.PARCEL_TYPE); // the rest depends on the type
        int current = 0;

        while (true) {
            AddStep step = steps.get(current);

            if (step == AddStep.DELIVERY_SERVICE) {
                DeliveryService service = readDeliveryService();
                if (service == null) {
                    out.println(MSG_STEP_BACK);
                    current--;
                } else if (saveParcel(draft, service)) {
                    return; // finished
                }
                continue; // Same Day refused: the service menu is shown again
            }

            boolean answered = askAddStep(step, draft);

            if (step == AddStep.PARCEL_TYPE) {
                if (!answered) {
                    out.println(MSG_BACK_TO_MAIN_MENU); // the previous screen is the main menu
                    return;
                }
                steps = stepsFor(draft.type);
            }
            if (step == AddStep.HEIGHT && answered && !dimensionsAreUsable(draft)) {
                draft.lengthCm = null; // the three values do not work together,
                draft.widthCm = null;  // so all three are asked again
                draft.heightCm = null;
                current = steps.indexOf(AddStep.LENGTH);
                continue;
            }

            if (answered) {
                current++;
            } else {
                out.println(MSG_STEP_BACK);
                current--;
            }
        }
    }

    /**
     * Asks one Add Parcel question, offering the earlier answer (if any) as the
     * default. Returns false if the user typed BACK; the earlier answer is then kept.
     */
    private boolean askAddStep(AddStep step, ParcelDraft draft) {
        switch (step) {
            case PARCEL_TYPE -> {
                Integer type = readParcelType(draft.type);
                if (type == null) {
                    return false;
                }
                draft.type = type;
            }
            case TRACKING_NUMBER -> {
                String trackingNumber = readNewTrackingNumber(draft.trackingNumber);
                if (trackingNumber == null) {
                    return false;
                }
                draft.trackingNumber = trackingNumber;
            }
            case RECEIVER_NAME -> {
                String name = input.promptUntilValidOrBack(question("Enter receiver name", draft.receiverName),
                        draft.receiverName, Parcel::validateReceiverName);
                if (name == null) {
                    return false;
                }
                draft.receiverName = name;
            }
            case WEIGHT -> {
                BigDecimal weight = readActualWeight(maxWeightFor(draft.type), draft.weightKg);
                if (weight == null) {
                    return false;
                }
                draft.weightKg = weight;
            }
            case PAGES -> {
                String range = Document.MIN_PAGES + "-" + Document.MAX_PAGES;
                Integer pages = input.promptUntilValidOrBack(
                        question("Enter number of pages, " + range, text(draft.pages)), text(draft.pages),
                        raw -> Document.validateNumberOfPages(InputValidator.parseWholeNumber(raw, "Number of pages")));
                if (pages == null) {
                    return false;
                }
                draft.pages = pages;
            }
            case LENGTH -> {
                Integer length = readDimension("Length", draft.lengthCm);
                if (length == null) {
                    return false;
                }
                draft.lengthCm = length;
            }
            case WIDTH -> {
                Integer width = readDimension("Width", draft.widthCm);
                if (width == null) {
                    return false;
                }
                draft.widthCm = width;
            }
            case HEIGHT -> {
                Integer height = readDimension("Height", draft.heightCm);
                if (height == null) {
                    return false;
                }
                draft.heightCm = height;
            }
            case HANDLING_NOTE -> {
                String note = input.promptUntilValidOrBack(question("Enter handling note", draft.handlingNote),
                        draft.handlingNote, Fragile::validateHandlingNote);
                if (note == null) {
                    return false;
                }
                draft.handlingNote = note;
            }
            default -> throw new IllegalStateException("Not a data-entry step: " + step);
        }
        return true;
    }

    /**
     * Builds and saves the parcel. Returns true when Add Parcel is finished (saved, or
     * stopped by an error), false when Same Day was refused and another service is needed.
     */
    private boolean saveParcel(ParcelDraft draft, DeliveryService service) {
        try {
            Parcel parcel = draft.build(service);
            manager.addParcel(parcel);
            out.println(MSG_ADDED_SUCCESSFULLY + Money.format(parcel.getDeliveryFee()));
        } catch (InvalidDeliveryServiceException e) {
            out.println(e.getMessage()); // Same Day not allowed
            return false;
        } catch (InvalidParcelException | DuplicateTrackingNumberException e) {
            out.println("Error: " + e.getMessage() + " The parcel was not added.");
        }
        return true;
    }

    /** The questions asked for each parcel type. */
    private static List<AddStep> stepsFor(int type) {
        List<AddStep> steps = new ArrayList<>(List.of(
                AddStep.PARCEL_TYPE, AddStep.TRACKING_NUMBER, AddStep.RECEIVER_NAME, AddStep.WEIGHT));
        if (type == TYPE_DOCUMENT) {
            steps.add(AddStep.PAGES);
        } else {
            steps.add(AddStep.LENGTH);
            steps.add(AddStep.WIDTH);
            steps.add(AddStep.HEIGHT);
            if (type == TYPE_FRAGILE) {
                steps.add(AddStep.HANDLING_NOTE);
            }
        }
        steps.add(AddStep.DELIVERY_SERVICE);
        return steps;
    }

    private static BigDecimal maxWeightFor(int type) {
        return switch (type) {
            case TYPE_DOCUMENT -> Document.MAX_DOCUMENT_WEIGHT_KG;
            case TYPE_BOX -> Box.MAX_BOX_WEIGHT_KG;
            case TYPE_FRAGILE -> Fragile.MAX_FRAGILE_WEIGHT_KG;
            default -> throw new IllegalStateException("Unexpected parcel type: " + type);
        };
    }

    /** Returns 1-3, or null for BACK / 0. */
    private Integer readParcelType(Integer previousType) {
        out.println("===== SELECT PARCEL TYPE =====");
        out.println("1. Document");
        out.println("2. Box");
        out.println("3. Fragile");
        out.println(BACK_OPTION + ". Back");
        return readSubmenuChoice(TYPE_FRAGILE, previousType);
    }

    /** Format is checked first, then uniqueness (including delivered parcels). Null means BACK. */
    private String readNewTrackingNumber(String previousAnswer) {
        return input.promptUntilValidOrBack(question("Enter tracking number, e.g. ABC0001", previousAnswer),
                previousAnswer, raw -> {
                    String trackingNumber = Parcel.validateTrackingNumber(raw);
                    if (manager.isTrackingNumberRegistered(trackingNumber)) {
                        throw new DuplicateTrackingNumberException(trackingNumber);
                    }
                    return trackingNumber;
                });
    }

    /** Null means BACK. A kept weight is re-checked against the current type's limit. */
    private BigDecimal readActualWeight(BigDecimal maxWeightKg, BigDecimal previousWeight) {
        String previousAnswer = previousWeight == null ? null : previousWeight.toPlainString();
        String text = "Enter actual weight in kg, more than 0 and max " + maxWeightKg.stripTrailingZeros().toPlainString();
        return input.promptUntilValidOrBack(question(text, previousAnswer), previousAnswer,
                raw -> Parcel.validateActualWeight(InputValidator.parseDecimal(raw, "Weight"), maxWeightKg));
    }

    /** Null means BACK. */
    private Integer readDimension(String dimensionName, Integer previousValue) {
        String text = "Enter " + dimensionName.toLowerCase() + " in cm, whole number";
        return input.promptUntilValidOrBack(question(text, text(previousValue)), text(previousValue),
                raw -> Box.validateDimension(InputValidator.parseWholeNumber(raw, dimensionName), dimensionName));
    }

    /** Checks that length x width x height can be calculated (no overflow). */
    private boolean dimensionsAreUsable(ParcelDraft draft) {
        try {
            Box.calculateVolume(draft.lengthCm, draft.widthCm, draft.heightCm);
            return true;
        } catch (InvalidParcelException e) {
            out.println("Error: " + e.getMessage() + " Please enter the dimensions again.");
            return false;
        }
    }

    /** Returns the chosen service, or null for BACK / 0. */
    private DeliveryService readDeliveryService() {
        DeliveryService[] services = DeliveryService.values();
        out.println("===== DELIVERY SERVICE =====");
        for (int i = 0; i < services.length; i++) {
            out.println((i + 1) + ". " + services[i].getDisplayName());
        }
        out.println(BACK_OPTION + ". Back");
        Integer choice = readSubmenuChoice(services.length, null);
        if (choice == null) {
            return null;
        }
        return services[choice - 1];
    }

    /**
     * Reads a choice from 1 to {@code lastOption} in a numbered submenu.
     * Returns null when the user chose 0 or typed BACK (go back one screen).
     */
    private Integer readSubmenuChoice(int lastOption, Integer previousChoice) {
        Integer choice = input.promptUntilValidOrBack(question("Enter your choice", text(previousChoice)),
                text(previousChoice), raw -> InputValidator.parseMenuChoice(raw, BACK_OPTION, lastOption));
        if (choice == null || choice == BACK_OPTION) {
            return null;
        }
        return choice;
    }

    // ------------------------------------------------------------------
    // 2. View Inventory / All Parcels
    // ------------------------------------------------------------------

    private void viewAllParcels() {
        out.println();
        List<Parcel> parcels = manager.getAllParcels();
        if (parcels.isEmpty()) {
            out.println(MSG_NO_PARCELS);
            return;
        }
        String table = formatParcelTable(parcels);
        out.println("===================== INVENTORY =====================");
        out.print(table);
        out.println("-----------------------------------------------------");
        printInventorySummary("Status Summary:");
        out.println("=====================================================");
    }

    /**
     * Builds an aligned text table. Column widths grow to fit the data, and long
     * receiver names are shortened so they cannot break the layout.
     * Read-only: it only calls getters.
     */
    static String formatParcelTable(List<Parcel> parcels) {
        String[] headers = {"Tracking No.", "Type", "Receiver", "Chargeable Wt", "Service", "Delivery Fee", "Status"};
        boolean[] alignRight = {false, false, false, true, false, true, false};

        List<String[]> rows = new ArrayList<>();
        for (Parcel parcel : parcels) {
            rows.add(new String[]{
                    parcel.getTrackingNumber(),
                    parcel.getTypeName(),                              // runtime type (polymorphism)
                    shorten(parcel.getReceiverName(), MAX_RECEIVER_COLUMN_WIDTH),
                    formatWeight(parcel.getChargeableWeight()),        // subclass calculation
                    parcel.getDeliveryService().getDisplayName(),
                    Money.format(parcel.getDeliveryFee()),             // strategy calculation
                    parcel.getStatus().name()
            });
        }

        int[] widths = new int[headers.length];
        for (int column = 0; column < headers.length; column++) {
            widths[column] = headers[column].length();
            for (String[] row : rows) {
                widths[column] = Math.max(widths[column], row[column].codePointCount(0, row[column].length()));
            }
        }

        StringBuilder table = new StringBuilder();
        appendRow(table, headers, widths, alignRight);
        int totalWidth = (headers.length - 1) * 3;
        for (int width : widths) {
            totalWidth += width;
        }
        table.append("-".repeat(totalWidth)).append(System.lineSeparator());
        for (String[] row : rows) {
            appendRow(table, row, widths, alignRight);
        }
        return table.toString();
    }

    private static void appendRow(StringBuilder table, String[] cells, int[] widths, boolean[] alignRight) {
        for (int column = 0; column < cells.length; column++) {
            if (column > 0) {
                table.append(" | ");
            }
            String cell = cells[column];
            String padding = " ".repeat(widths[column] - cell.codePointCount(0, cell.length()));
            table.append(alignRight[column] ? padding + cell : cell + padding);
        }
        // Trailing spaces from the last column are not useful on screen.
        int end = table.length();
        while (end > 0 && table.charAt(end - 1) == ' ') {
            end--;
        }
        table.setLength(end);
        table.append(System.lineSeparator());
    }

    static String shorten(String text, int maxLength) {
        int length = text.codePointCount(0, text.length());
        if (length <= maxLength) {
            return text;
        }
        int cut = text.offsetByCodePoints(0, maxLength - 3);
        return text.substring(0, cut) + "...";
    }

    static String formatWeight(BigDecimal weightKg) {
        return weightKg.stripTrailingZeros().toPlainString() + " kg";
    }

    // ------------------------------------------------------------------
    // 3. Change Delivery Service
    // ------------------------------------------------------------------

    /**
     * Step 1: tracking number (BACK returns to the main menu, the previous screen).
     * Step 2: new service (BACK returns to step 1, keeping the tracking number).
     * The parcel is only changed once a valid service is chosen.
     */
    private void changeDeliveryService() {
        out.println();
        if (!manager.hasParcels()) {
            out.println(MSG_EMPTY_INVENTORY);
            return;
        }
        out.println("--- Change Delivery Service ---");
        out.println(NAVIGATION_HINT);
        String trackingNumber = null;
        while (true) {
            String entered = readExistingTrackingNumber(trackingNumber);
            if (entered == null) {
                out.println(MSG_BACK_TO_MAIN_MENU);
                return;
            }
            trackingNumber = entered;

            Parcel parcel;
            try {
                parcel = manager.getParcel(trackingNumber);
            } catch (ParcelNotFoundException e) {
                out.println(MSG_PARCEL_NOT_FOUND);
                return;
            }
            if (parcel.isDelivered()) {
                out.println(MSG_CHANGE_ALREADY_DELIVERED);
                return;
            }

            out.println("Current service: " + parcel.getDeliveryService().getDisplayName()
                    + " (fee: " + Money.format(parcel.getDeliveryFee()) + ")");
            if (chooseNewService(trackingNumber)) {
                return; // finished
            }
            out.println(MSG_STEP_BACK); // BACK at the service menu: ask for the tracking number again
        }
    }

    /**
     * Asks for the new service until it is accepted. Returns true when the operation
     * is finished, false when the user went back.
     */
    private boolean chooseNewService(String trackingNumber) {
        while (true) {
            DeliveryService newService = readDeliveryService();
            if (newService == null) {
                return false;
            }
            try {
                Parcel updated = manager.changeDeliveryService(trackingNumber, newService);
                out.println(MSG_SERVICE_CHANGED + Money.format(updated.getDeliveryFee()));
                return true;
            } catch (InvalidDeliveryServiceException e) {
                // Old service and fee are untouched; let the user choose again.
                out.println(e.getMessage());
            } catch (ParcelNotFoundException e) {
                out.println(MSG_PARCEL_NOT_FOUND);
                return true;
            } catch (ParcelAlreadyDeliveredException e) {
                out.println(MSG_CHANGE_ALREADY_DELIVERED);
                return true;
            }
        }
    }

    /** Asks for a tracking number to look up (format only). Null means BACK. */
    private String readExistingTrackingNumber(String previousAnswer) {
        return input.promptUntilValidOrBack(question("Enter tracking number", previousAnswer),
                previousAnswer, Parcel::validateTrackingNumber);
    }

    // ------------------------------------------------------------------
    // 4. Mark as Delivered
    // ------------------------------------------------------------------

    /** Only one question, so BACK returns to the main menu (the previous screen). */
    private void markAsDelivered() {
        out.println();
        if (!manager.hasParcels()) {
            out.println(MSG_EMPTY_INVENTORY);
            return;
        }
        out.println("--- Mark as Delivered ---");
        out.println(NAVIGATION_HINT);
        String trackingNumber = readExistingTrackingNumber(null);
        if (trackingNumber == null) {
            out.println(MSG_BACK_TO_MAIN_MENU);
            return;
        }
        try {
            manager.markAsDelivered(trackingNumber);
            out.println(MSG_DELIVERED_SUCCESSFULLY);
        } catch (ParcelNotFoundException e) {
            out.println(MSG_PARCEL_NOT_FOUND);
        } catch (ParcelAlreadyDeliveredException e) {
            out.println(MSG_MARK_ALREADY_DELIVERED);
        }
    }
}
