import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Scanner;
import java.util.regex.Pattern;

/**
 * Reusable console input helpers.
 *
 * <ul>
 *   <li>Every line is read with {@link Scanner#nextLine()} and parsed explicitly,
 *       which avoids the classic nextInt()/nextLine() leftover-newline bug.</li>
 *   <li>{@link #promptUntilValid} asks for one field until it is valid, showing
 *       the error each time, so earlier valid answers are never lost.</li>
 *   <li>When input ends (EOF) an {@link EndOfInputException} is thrown instead of
 *       looping forever.</li>
 *   <li>At a question, BACK returns {@code null} so the menu can go back one step,
 *       CANCEL abandons the whole operation, and Enter can keep an earlier answer.</li>
 * </ul>
 * Parsing (text to number) happens here; business rules (limits, formats) live
 * in the model classes and are passed in as {@link FieldParser}s.
 */
public final class InputValidator {

    /** Typed at a question to go back one step. */
    public static final String BACK_COMMAND = "BACK";
    /** Typed at a question to abandon the whole operation and return to the main menu. */
    public static final String CANCEL_COMMAND = "CANCEL";

    /** Any longer line is rejected before parsing. */
    public static final int MAX_INPUT_LENGTH = 500;
    /** Longest numeric text accepted; longer numbers are certainly out of range. */
    private static final int MAX_NUMBER_LENGTH = 30;

    /**
     * Strict whole number: "0", or a non-zero digit followed by digits ("1", "12").
     * No "+" sign and no leading zeros ("01", "001" are rejected). A "-" is still
     * read so that "-5" gets the clearer "must be greater than 0" message.
     */
    private static final Pattern WHOLE_NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)");
    /** A number with unnecessary leading zeros, such as "01", "001", "-01". */
    private static final Pattern LEADING_ZEROS = Pattern.compile("[+-]?0[0-9]+");
    /**
     * Plain decimal notation only: "1.8", "10", ".5", "-2.5".
     * Exponents (1e3), hex (0x1p3), type suffixes (1.5f), NaN and Infinity are
     * all rejected, unlike Double.parseDouble which would accept them.
     */
    private static final Pattern DECIMAL_NUMBER = Pattern.compile("[+-]?([0-9]+(\\.[0-9]+)?|\\.[0-9]+)");
    /** The words Java would turn into a non-finite double, matched exactly so "banana" is not mistaken for NaN. */
    private static final Pattern NON_FINITE_WORD =
            Pattern.compile("[+-]?(nan|inf|infinity)", Pattern.CASE_INSENSITIVE);

    private final Scanner scanner;
    private final PrintStream out;

    /** A parsing/validation step that may reject the text with a recoverable error. */
    @FunctionalInterface
    public interface FieldParser<T> {
        T parse(String rawInput) throws ParcelSystemException;
    }

    public InputValidator(Scanner scanner, PrintStream out) {
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.out = Objects.requireNonNull(out, "out");
    }

    /** Prints the prompt and reads one raw line (not trimmed). */
    public String readLine(String prompt) {
        out.print(prompt);
        out.flush();
        if (!scanner.hasNextLine()) {
            throw new EndOfInputException();
        }
        return scanner.nextLine();
    }

    /**
     * Keeps prompting until {@code parser} accepts the line. Used by the main menu,
     * where there is nowhere to go back to, so BACK and CANCEL are just invalid choices there.
     */
    public <T> T promptUntilValid(String prompt, FieldParser<T> parser) {
        return prompt(prompt, null, parser, false);
    }

    /**
     * Like {@link #promptUntilValid}, but with navigation commands:
     * <ul>
     *   <li>BACK returns {@code null}; the caller goes back one step.</li>
     *   <li>CANCEL throws {@link OperationCancelledException}; the whole operation is abandoned.</li>
     *   <li>If {@code previousAnswer} is not null, pressing Enter keeps it. It is checked
     *       by {@code parser} again, because the rules may have changed since (for example
     *       a different parcel type with a lower weight limit).</li>
     * </ul>
     */
    public <T> T promptUntilValidOrBack(String prompt, String previousAnswer, FieldParser<T> parser) {
        return prompt(prompt, previousAnswer, parser, true);
    }

    /** True when the user typed the BACK command, e.g. "back", "Back", "  BACK  ". */
    public static boolean isBackCommand(String rawInput) {
        return rawInput != null && rawInput.strip().equalsIgnoreCase(BACK_COMMAND);
    }

    /** True when the user typed the CANCEL command, e.g. "cancel", "Cancel", "  CANCEL  ". */
    public static boolean isCancelCommand(String rawInput) {
        return rawInput != null && rawInput.strip().equalsIgnoreCase(CANCEL_COMMAND);
    }

    /**
     * The shared prompt loop. Only expected, recoverable errors
     * ({@link ParcelSystemException}) are caught; programming errors are not hidden.
     */
    private <T> T prompt(String prompt, String previousAnswer, FieldParser<T> parser, boolean allowNavigation) {
        while (true) {
            String rawInput = readLine(prompt);
            if (allowNavigation) {
                // Commands are checked before any validation, so they never cause an error message.
                if (isCancelCommand(rawInput)) {
                    throw new OperationCancelledException();
                }
                if (isBackCommand(rawInput)) {
                    return null;
                }
                if (previousAnswer != null && rawInput.isBlank()) {
                    rawInput = previousAnswer; // Enter keeps the earlier answer (validated again below)
                }
            }
            try {
                if (rawInput.length() > MAX_INPUT_LENGTH) {
                    throw new InvalidInputException("Input is too long (maximum " + MAX_INPUT_LENGTH + " characters).");
                }
                return parser.parse(rawInput);
            } catch (ParcelSystemException e) {
                out.println("Error: " + e.getMessage());
            }
        }
    }

    /** Parses a whole number such as "12" (surrounding spaces allowed). */
    public static int parseWholeNumber(String rawInput, String fieldName) throws InvalidInputException {
        String text = requireNotEmpty(rawInput, fieldName);
        if (LEADING_ZEROS.matcher(text).matches()) {
            throw new InvalidInputException(fieldName + " must not have leading zeros (for example, enter 1 instead of 01).");
        }
        if (!WHOLE_NUMBER.matcher(text).matches()) {
            if (text.contains(".") && DECIMAL_NUMBER.matcher(text).matches()) {
                throw new InvalidInputException(fieldName + " must be a whole number (decimals are not allowed).");
            }
            throw new InvalidInputException(fieldName + " must be a whole number written with digits only, for example 12.");
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException tooLarge) {
            throw new InvalidInputException(fieldName + " is outside the supported range.");
        }
    }

    /** Parses a plain decimal number such as "1.8" into an exact BigDecimal. */
    public static BigDecimal parseDecimal(String rawInput, String fieldName) throws InvalidInputException {
        String text = requireNotEmpty(rawInput, fieldName);
        if (NON_FINITE_WORD.matcher(text).matches()) {
            throw new InvalidInputException(fieldName + " must be a real number (NaN and Infinity are not allowed).");
        }
        if (!DECIMAL_NUMBER.matcher(text).matches()) {
            throw new InvalidInputException(fieldName + " must be a number, for example 1.8");
        }
        if (text.length() > MAX_NUMBER_LENGTH) {
            throw new InvalidInputException(fieldName + " has too many digits.");
        }
        return new BigDecimal(text);
    }

    /** Parses a numbered menu option and checks that it is one of the listed options. */
    public static int parseMenuChoice(String rawInput, int minOption, int maxOption) throws InvalidInputException {
        String rangeMessage = "Invalid choice. Please enter a number from " + minOption + " to " + maxOption + ".";
        int choice;
        try {
            choice = parseWholeNumber(rawInput, "Choice");
        } catch (InvalidInputException notAWholeNumber) {
            throw new InvalidInputException(rangeMessage);
        }
        if (choice < minOption || choice > maxOption) {
            throw new InvalidInputException(rangeMessage);
        }
        return choice;
    }

    private static String requireNotEmpty(String rawInput, String fieldName) throws InvalidInputException {
        if (rawInput == null || rawInput.isBlank()) {
            throw new InvalidInputException(fieldName + " cannot be empty.");
        }
        return rawInput.strip();
    }
}
