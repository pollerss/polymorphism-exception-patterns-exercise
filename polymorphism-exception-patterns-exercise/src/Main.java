import java.util.Scanner;

/**
 * Entry point of the Parcel Delivery System.
 */
public class Main {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        new ConsoleMenu(scanner, System.out).run();
    }
}
