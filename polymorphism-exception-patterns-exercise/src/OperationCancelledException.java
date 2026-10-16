/**
 * Signals that the user typed {@code CANCEL} to abandon the current operation.
 *
 * <p>Like {@link EndOfInputException}, it is unchecked: it can happen at any
 * question, and its only job is to unwind the current operation back to the main
 * menu loop in {@link ConsoleMenu#run()}, which is the one place that catches it.
 * Every operation saves or changes data only at its very last step, so cancelling
 * earlier never leaves a half-created parcel or a half-changed one.</p>
 *
 * <p>(BACK is different: it only goes back one step, so it is handled where the
 * question is asked, not with this exception.)</p>
 */
public class OperationCancelledException extends RuntimeException {

    public OperationCancelledException() {
        super("Operation cancelled by the user.");
    }
}
