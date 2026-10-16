/**
 * Signals that the console has no more input (Ctrl+D on Linux/macOS, Ctrl+Z then
 * Enter on Windows, or the end of a redirected input file).
 *
 * <p>It is unchecked on purpose: it can happen at any prompt, and its only job is
 * to unwind the current operation back to the main menu loop, which then exits
 * cleanly. Because it is thrown <em>before</em> anything is saved, an operation
 * interrupted by end of input never leaves a half-created or half-updated parcel.</p>
 */
public class EndOfInputException extends RuntimeException {

    public EndOfInputException() {
        super("End of console input reached.");
    }
}
