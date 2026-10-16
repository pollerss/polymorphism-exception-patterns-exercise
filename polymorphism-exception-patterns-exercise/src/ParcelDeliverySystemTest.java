import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Automated tests for the Parcel Delivery System, written in plain Java so they
 * run without Maven, Gradle or JUnit.
 *
 * <p>Expected values are written out by hand (for example "330.00"), never computed
 * by calling the code under test, so a wrong formula cannot "agree with itself".</p>
 *
 * <p>Every test starts with an empty ParcelManager (see {@link #resetManager()}),
 * so tests do not depend on order or on data left by other tests.</p>
 *
 * Run (after javac -d out src/*.java): java -cp out ParcelDeliverySystemTest
 */
public final class ParcelDeliverySystemTest {

    // Short names for the services (static imports are not possible without packages).
    private static final DeliveryService STANDARD = DeliveryService.STANDARD;
    private static final DeliveryService EXPRESS = DeliveryService.EXPRESS;
    private static final DeliveryService SAME_DAY = DeliveryService.SAME_DAY;

    private static final String SAME_DAY_MESSAGE = "Same Day delivery is only for parcels up to 10 kg!";
    private static final long CONSOLE_TIMEOUT_SECONDS = 10;

    @FunctionalInterface
    private interface TestBody {
        void run() throws Exception;
    }

    private record TestCase(String section, String name, TestBody body) {
    }

    private static final List<TestCase> TESTS = new ArrayList<>();
    private static String currentSection = "";

    public static void main(String[] args) {
        registerTrackingNumberTests();
        registerReceiverNameTests();
        registerWeightAndParsingTests();
        registerDocumentTests();
        registerBoxTests();
        registerFragileTests();
        registerStrategyTests();
        registerSameDayEligibilityTests();
        registerStateTransitionTests();
        registerManagerAndSingletonTests();
        registerEncapsulationAndDesignTests();
        registerConsoleTests();
        registerReturnAndBackTests();
        registerInventoryTests();

        int passed = 0;
        List<String> failures = new ArrayList<>();
        String lastSection = "";
        for (TestCase test : TESTS) {
            if (!test.section().equals(lastSection)) {
                System.out.println();
                System.out.println("== " + test.section() + " ==");
                lastSection = test.section();
            }
            try {
                resetManager();
                test.body().run();
                passed++;
                System.out.println("PASS  " + test.name());
            } catch (Throwable failure) {
                String detail = failure instanceof AssertionError ? failure.getMessage() : failure.toString();
                failures.add(test.section() + " > " + test.name() + ": " + detail);
                System.out.println("FAIL  " + test.name() + "  -> " + detail);
            }
        }

        System.out.println();
        System.out.println("==================================================");
        System.out.println("Tests run: " + TESTS.size() + ", Passed: " + passed + ", Failed: " + failures.size());
        failures.forEach(failure -> System.out.println("  FAILED: " + failure));
        System.out.println("==================================================");
        if (!failures.isEmpty()) {
            System.exit(1); // non-zero exit code so scripts/CI can detect failures
        }
    }

    // =====================================================================
    // A. Tracking numbers
    // =====================================================================

    private static void registerTrackingNumberTests() {
        section("A. Tracking numbers");

        test("ABC0001, ABC9999 and ABC0000 are valid formats", () -> {
            for (String valid : List.of("ABC0001", "ABC9999", "ABC0000")) {
                assertEquals(valid, Parcel.validateTrackingNumber(valid));
            }
        });

        test("ABC0000 can be registered when unused", () -> {
            manager().addParcel(sampleDocument("ABC0000", STANDARD));
            assertTrue(manager().isTrackingNumberRegistered("ABC0000"), "ABC0000 should be registered");
        });

        test("Malformed tracking numbers are rejected", () -> {
            String[] invalid = {"ABC001", "ABC12345", "XYZ0001", "ABC0A01", "abc0001", "AB C0001",
                    " ABC0001 ", " ABC0001", "ABC0001 ", "Abc0001", "ABC-001", "ABC", "0001", "",
                    "ABC0001\t", "ABC\u0661\u0662\u0663\u0664", "ABC\uFF10\uFF10\uFF10\uFF11"};
            for (String bad : invalid) {
                assertThrows(InvalidParcelException.class, () -> Parcel.validateTrackingNumber(bad));
            }
            assertThrows(InvalidParcelException.class, () -> Parcel.validateTrackingNumber(null));
        });

        test("Lowercase tracking number is rejected, not silently upper-cased", () -> {
            assertThrows(InvalidParcelException.class,
                    () -> new Document("abc0001", "Ana", kg("0.2"), 5, STANDARD));
        });

        test("Constructors reject spaces around tracking number (no silent trim)", () -> {
            assertThrows(InvalidParcelException.class,
                    () -> new Box(" ABC0001", "Ana", kg("1"), 10, 10, 10, STANDARD));
        });

        test("Duplicate tracking number is rejected and the first parcel kept", () -> {
            Parcel first = sampleDocument("ABC0001", STANDARD);
            manager().addParcel(first);
            assertThrows(DuplicateTrackingNumberException.class,
                    () -> manager().addParcel(sampleBox("ABC0001", "2", STANDARD)));
            assertEquals(1, manager().getAllParcels().size());
            assertSame(first, manager().getParcel("ABC0001"));
        });

        test("Tracking number of a delivered parcel cannot be reused", () -> {
            manager().addParcel(sampleDocument("ABC0001", STANDARD));
            manager().markAsDelivered("ABC0001");
            assertThrows(DuplicateTrackingNumberException.class,
                    () -> manager().addParcel(sampleDocument("ABC0001", STANDARD)));
            assertEquals(1, manager().getAllParcels().size());
        });

        test("The same parcel object cannot be registered twice", () -> {
            Parcel parcel = sampleDocument("ABC0001", STANDARD);
            manager().addParcel(parcel);
            assertThrows(DuplicateTrackingNumberException.class, () -> manager().addParcel(parcel));
            assertEquals(1, manager().getAllParcels().size());
        });
    }

    // =====================================================================
    // Receiver name and other text
    // =====================================================================

    private static void registerReceiverNameTests() {
        section("Receiver name");

        test("Leading/trailing spaces are trimmed", () -> {
            Parcel parcel = new Document("ABC0001", "   Juan Dela Cruz  ", kg("0.2"), 5, STANDARD);
            assertEquals("Juan Dela Cruz", parcel.getReceiverName());
        });

        test("Names with spaces, apostrophes, hyphens, accents and digits are accepted", () -> {
            for (String name : List.of("Mary-Jane O'Neil", "Jos\u00E9 Rizal", "Ma. Clara de los Santos", "Unit 7B Office", "\u674E\u534E")) {
                assertEquals(name, Parcel.validateReceiverName(name));
            }
        });

        test("Empty, blank, tab-only, non-breaking-space and zero-width names are rejected", () -> {
            String[] blanks = {"", " ", "      ", "\t", " \t ", "\u00A0\u00A0", "\u2007", "\u200B", "\u3000"};
            for (String blank : blanks) {
                assertThrows(InvalidParcelException.class, () -> Parcel.validateReceiverName(blank));
            }
            assertThrows(InvalidParcelException.class, () -> Parcel.validateReceiverName(null));
        });

        test("Control characters inside a name are rejected (protects the table layout)", () -> {
            assertThrows(InvalidParcelException.class, () -> Parcel.validateReceiverName("Ana\tCruz"));
            assertThrows(InvalidParcelException.class, () -> Parcel.validateReceiverName("Ana\u0007"));
        });

        test("Name length boundary: 100 accepted, 101 rejected", () -> {
            assertEquals(100, Parcel.validateReceiverName("A".repeat(100)).length());
            assertThrows(InvalidParcelException.class, () -> Parcel.validateReceiverName("A".repeat(101)));
        });

        test("A blank name in the constructor creates no parcel", () -> {
            assertThrows(InvalidParcelException.class, () -> new Document("ABC0001", "   ", kg("0.2"), 5, STANDARD));
        });
    }

    // =====================================================================
    // Weight values and number parsing
    // =====================================================================

    private static void registerWeightAndParsingTests() {
        section("Weights and number parsing");

        test("Decimal parsing accepts plain numbers with surrounding spaces", () -> {
            assertNumberEquals("1.8", InputValidator.parseDecimal("1.8", "Weight"));
            assertNumberEquals("2.5", InputValidator.parseDecimal("  2.5  ", "Weight"));
            assertNumberEquals("0.5", InputValidator.parseDecimal(".5", "Weight"));
            assertNumberEquals("10", InputValidator.parseDecimal("10", "Weight"));
        });

        test("Decimal parsing rejects letters, mixed text, exponents, suffixes, hex and malformed decimals", () -> {
            String[] invalid = {"", "   ", "abc", "1.2.3", "1,5", "1e3", "1E-1", "1.5f", "1.5d", "0x10",
                    "1.", "--1", "1 5", "12kg", "kg12", "+-1", "\u0661", "\u0661.\u0665", "1_000"};
            for (String bad : invalid) {
                assertThrows(InvalidInputException.class, () -> InputValidator.parseDecimal(bad, "Weight"));
            }
            assertThrows(InvalidInputException.class, () -> InputValidator.parseDecimal(null, "Weight"));
        });

        test("NaN, Infinity and -Infinity are rejected with a clear message", () -> {
            for (String bad : List.of("NaN", "nan", "Infinity", "-Infinity", "+Infinity", "infinity")) {
                InvalidInputException e = assertThrows(InvalidInputException.class,
                        () -> InputValidator.parseDecimal(bad, "Weight"));
                assertContains(e.getMessage(), "NaN and Infinity are not allowed");
            }
        });

        test("REGRESSION: words that merely contain 'nan' get the normal message, not the NaN one", () -> {
            for (String word : List.of("banana", "nano", "infinite")) {
                InvalidInputException e = assertThrows(InvalidInputException.class,
                        () -> InputValidator.parseDecimal(word, "Weight"));
                assertEquals("Weight must be a number, for example 1.8", e.getMessage());
            }
            InvalidInputException inf = assertThrows(InvalidInputException.class,
                    () -> InputValidator.parseDecimal("-inf", "Weight"));
            assertContains(inf.getMessage(), "NaN and Infinity are not allowed");
        });

        test("Extremely long numeric text is rejected", () -> {
            assertThrows(InvalidInputException.class,
                    () -> InputValidator.parseDecimal("1".repeat(200), "Weight"));
        });

        test("Zero, negative zero and negative weights are rejected", () -> {
            for (String bad : List.of("0", "0.0", "-0", "-0.0", "-0.1", "-5")) {
                assertThrows(InvalidParcelException.class,
                        () -> Parcel.validateActualWeight(InputValidator.parseDecimal(bad, "Weight"), Box.MAX_BOX_WEIGHT_KG));
            }
        });

        test("Smallest positive weight (0.001 kg) is accepted; finer precision is rejected", () -> {
            assertNumberEquals("0.001", Parcel.validateActualWeight(kg("0.001"), Document.MAX_DOCUMENT_WEIGHT_KG));
            assertThrows(InvalidParcelException.class,
                    () -> Parcel.validateActualWeight(kg("0.0001"), Document.MAX_DOCUMENT_WEIGHT_KG));
            assertThrows(InvalidParcelException.class,
                    () -> Parcel.validateActualWeight(kg("0.00000000000000000001"), Box.MAX_BOX_WEIGHT_KG));
        });

        test("Trailing zeros do not count as extra precision (0.5000 is 0.5)", () -> {
            assertNumberEquals("0.5", Parcel.validateActualWeight(kg("0.5000"), Document.MAX_DOCUMENT_WEIGHT_KG));
        });

        test("Extremely large weights are rejected without overflow", () -> {
            BigDecimal huge = InputValidator.parseDecimal("999999999999999999999999", "Weight");
            assertThrows(InvalidParcelException.class, () -> Parcel.validateActualWeight(huge, Box.MAX_BOX_WEIGHT_KG));
        });

        test("Null weight is rejected", () -> {
            assertThrows(InvalidParcelException.class, () -> Parcel.validateActualWeight(null, Box.MAX_BOX_WEIGHT_KG));
        });

        test("Whole-number parsing accepts 1, 10, 100, surrounding spaces, and reads a minus sign", () -> {
            assertEquals(1, InputValidator.parseWholeNumber("1", "Pages"));
            assertEquals(10, InputValidator.parseWholeNumber("10", "Pages"));
            assertEquals(100, InputValidator.parseWholeNumber("100", "Pages"));
            assertEquals(50, InputValidator.parseWholeNumber(" 50 ", "Pages"));
            assertEquals(0, InputValidator.parseWholeNumber("0", "Pages"));
            assertEquals(-3, InputValidator.parseWholeNumber("-3", "Pages")); // rejected later as "must be > 0"
        });

        test("Whole-number parsing rejects leading zeros (01, 001, 0001, 010, 00, -01)", () -> {
            for (String bad : List.of("01", "001", "0001", "010", "00", "-01", "+01", " 01 ")) {
                InvalidInputException e = assertThrows(InvalidInputException.class,
                        () -> InputValidator.parseWholeNumber(bad, "Pages"));
                assertContains(e.getMessage(), "leading zeros");
            }
        });

        test("Whole-number parsing rejects a plus sign, 1.0, 1e0 and 1abc", () -> {
            for (String bad : List.of("+1", "+10", "1.0", "1e0", "1abc", "1 0", "--1")) {
                assertThrows(InvalidInputException.class, () -> InputValidator.parseWholeNumber(bad, "Pages"));
            }
        });

        test("Page and dimension fields reject leading zeros end to end", () -> {
            assertThrows(InvalidInputException.class,
                    () -> Document.validateNumberOfPages(InputValidator.parseWholeNumber("05", "Number of pages")));
            assertThrows(InvalidInputException.class,
                    () -> Box.validateDimension(InputValidator.parseWholeNumber("040", "Length"), "Length"));
            assertEquals(5, Document.validateNumberOfPages(InputValidator.parseWholeNumber("5", "Number of pages")));
            assertEquals(40, Box.validateDimension(InputValidator.parseWholeNumber("40", "Length"), "Length"));
        });

        test("Tracking numbers keep their zeros: ABC0001 is still valid", () -> {
            assertEquals("ABC0001", Parcel.validateTrackingNumber("ABC0001"));
            assertEquals("ABC0010", Parcel.validateTrackingNumber("ABC0010"));
        });

        test("Whole-number parsing rejects decimals with a specific message", () -> {
            for (String decimal : List.of("2.5", "1.0", ".5")) {
                InvalidInputException e = assertThrows(InvalidInputException.class,
                        () -> InputValidator.parseWholeNumber(decimal, "Pages"));
                assertContains(e.getMessage(), "decimals are not allowed");
            }
        });

        test("Whole-number parsing rejects empty, letters, mixed text and overflow", () -> {
            for (String bad : List.of("", "  ", "abc", "12abc", "1 2", "1e3", "2147483648", "-2147483649",
                    "99999999999999999999")) {
                assertThrows(InvalidInputException.class, () -> InputValidator.parseWholeNumber(bad, "Pages"));
            }
        });

        test("Integer.MAX_VALUE itself parses (range checks are a separate rule)", () -> {
            assertEquals(Integer.MAX_VALUE, InputValidator.parseWholeNumber("2147483647", "Length"));
        });

        test("Menu choice parsing rejects anything outside the range", () -> {
            assertEquals(1, InputValidator.parseMenuChoice("1", 1, 5));
            assertEquals(2, InputValidator.parseMenuChoice("2", 1, 5));
            assertEquals(5, InputValidator.parseMenuChoice(" 5 ", 1, 5));
            for (String bad : List.of("0", "6", "-1", "", "abc", "1.5", "99999999999", "x".repeat(1000),
                    "01", "001", "0001", "+1", "1.0", "1e0", "1abc")) {
                InvalidInputException e = assertThrows(InvalidInputException.class,
                        () -> InputValidator.parseMenuChoice(bad, 1, 5));
                assertEquals("Invalid choice. Please enter a number from 1 to 5.", e.getMessage());
            }
        });
    }

    // =====================================================================
    // B. Document
    // =====================================================================

    private static void registerDocumentTests() {
        section("B. Document");

        test("Page counts 1 and 100 are accepted", () -> {
            assertEquals(1, new Document("ABC0001", "Ana", kg("0.2"), 1, STANDARD).getNumberOfPages());
            assertEquals(100, new Document("ABC0002", "Ana", kg("0.2"), 100, STANDARD).getNumberOfPages());
        });

        test("Page counts 0, 101, -1 and extremes are rejected", () -> {
            for (int pages : new int[]{0, 101, -1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
                assertThrows(InvalidParcelException.class, () -> new Document("ABC0001", "Ana", kg("0.2"), pages, STANDARD));
            }
        });

        test("Decimal and non-numeric page input is rejected", () -> {
            assertThrows(InvalidInputException.class, () -> InputValidator.parseWholeNumber("2.5", "Number of pages"));
            assertThrows(InvalidInputException.class, () -> InputValidator.parseWholeNumber("ten", "Number of pages"));
        });

        test("Weights 0.1, 0.499 and 0.5 are accepted", () -> {
            for (String weight : List.of("0.1", "0.499", "0.5")) {
                assertNumberEquals(weight, new Document("ABC0001", "Ana", kg(weight), 5, STANDARD).getActualWeight());
            }
        });

        test("Weights 0.501, 0.6, 1, 0 and -0.1 are rejected", () -> {
            for (String weight : List.of("0.501", "0.6", "1", "0", "-0.1")) {
                assertThrows(InvalidParcelException.class, () -> new Document("ABC0001", "Ana", kg(weight), 5, STANDARD));
            }
        });

        test("Chargeable weight is the exact actual weight (not rounded up)", () -> {
            assertNumberEquals("0.3", new Document("ABC0001", "Ana", kg("0.3"), 5, STANDARD).getChargeableWeight());
            assertNumberEquals("0.001", new Document("ABC0002", "Ana", kg("0.001"), 5, STANDARD).getChargeableWeight());
        });

        test("Base fee is exactly Php 50.00 regardless of weight or pages", () -> {
            assertMoney("50.00", new Document("ABC0001", "Ana", kg("0.1"), 1, STANDARD).getBaseFee());
            assertMoney("50.00", new Document("ABC0002", "Ana", kg("0.5"), 100, STANDARD).getBaseFee());
        });

        test("Document fees: Standard 50.00, Express 75.00, Same Day 200.00", () -> {
            assertMoney("50.00", sampleDocument("ABC0001", STANDARD).getDeliveryFee());
            assertMoney("75.00", sampleDocument("ABC0002", EXPRESS).getDeliveryFee());
            assertMoney("200.00", sampleDocument("ABC0003", SAME_DAY).getDeliveryFee());
        });
    }

    // =====================================================================
    // C. Box
    // =====================================================================

    private static void registerBoxTests() {
        section("C. Box");

        test("Valid positive dimensions are stored", () -> {
            Box box = new Box("ABC0001", "Ana", kg("2"), 40, 20, 10, STANDARD);
            assertEquals(40, box.getLengthCm());
            assertEquals(20, box.getWidthCm());
            assertEquals(10, box.getHeightCm());
        });

        test("Zero and negative values in each dimension are rejected", () -> {
            int[][] badDimensions = {{0, 10, 10}, {10, 0, 10}, {10, 10, 0}, {-5, 10, 10}, {10, -5, 10},
                    {10, 10, -5}, {Integer.MIN_VALUE, 10, 10}};
            for (int[] d : badDimensions) {
                assertThrows(InvalidParcelException.class, () -> new Box("ABC0001", "Ana", kg("2"), d[0], d[1], d[2], STANDARD));
            }
        });

        test("Decimal dimension input is rejected by the parser", () -> {
            assertThrows(InvalidInputException.class, () -> InputValidator.parseWholeNumber("12.5", "Length"));
        });

        test("Actual weight 50 accepted, 49.999 accepted, 50.001 and 51 rejected", () -> {
            new Box("ABC0001", "Ana", kg("50"), 10, 10, 10, STANDARD);
            new Box("ABC0002", "Ana", kg("49.999"), 10, 10, 10, STANDARD);
            assertThrows(InvalidParcelException.class, () -> new Box("ABC0003", "Ana", kg("50.001"), 10, 10, 10, STANDARD));
            assertThrows(InvalidParcelException.class, () -> new Box("ABC0004", "Ana", kg("51"), 10, 10, 10, STANDARD));
        });

        test("Volumetric weight uses floating-point division (8000 / 3500.0 = 2.2857...)", () -> {
            Box box = new Box("ABC0001", "Ana", kg("1.8"), 40, 20, 10, STANDARD);
            assertClose(2.2857142857142856, box.getVolumetricWeight());
            // Integer division would have produced 2.0:
            assertTrue(box.getVolumetricWeight() > 2.28, "volumetric weight must not be truncated");
        });

        test("Chargeable weight rounding: 2.0 -> 2, 2.1 -> 3, 2.001 -> 3", () -> {
            assertNumberEquals("2", new Box("ABC0001", "Ana", kg("2.0"), 10, 10, 10, STANDARD).getChargeableWeight());
            assertNumberEquals("3", new Box("ABC0002", "Ana", kg("2.1"), 10, 10, 10, STANDARD).getChargeableWeight());
            assertNumberEquals("3", new Box("ABC0003", "Ana", kg("2.001"), 10, 10, 10, STANDARD).getChargeableWeight());
        });

        test("Actual greater than volumetric: actual 5.0, volumetric 3.0 -> 5", () -> {
            Box box = new Box("ABC0001", "Ana", kg("5.0"), 35, 30, 10, STANDARD); // 10500 / 3500 = 3.0
            assertClose(3.0, box.getVolumetricWeight());
            assertNumberEquals("5", box.getChargeableWeight());
        });

        test("Volumetric greater than actual: actual 1.8, volumetric 2.29 -> 3", () -> {
            assertNumberEquals("3", new Box("ABC0001", "Ana", kg("1.8"), 40, 20, 10, STANDARD).getChargeableWeight());
        });

        test("Volumetric exactly a whole number is not rounded up further (7000 cm3 -> 2)", () -> {
            assertNumberEquals("2", new Box("ABC0001", "Ana", kg("1"), 35, 20, 10, STANDARD).getChargeableWeight());
            assertNumberEquals("3", new Box("ABC0002", "Ana", kg("1"), 7001, 1, 1, STANDARD).getChargeableWeight());
        });

        test("Large volumetric weight: 100x50x50 with 1 kg -> 72 kg", () -> {
            assertNumberEquals("72", new Box("ABC0001", "Ana", kg("1"), 100, 50, 50, STANDARD).getChargeableWeight());
        });

        test("Chargeable weight may exceed 50 kg when the actual weight is within the limit", () -> {
            Box box = new Box("ABC0001", "Ana", kg("49"), 100, 100, 100, STANDARD); // 1,000,000 / 3500 = 285.71
            assertNumberEquals("286", box.getChargeableWeight());
            assertMoney("11440.00", box.getBaseFee());
        });

        test("Chargeable weight matches ceil(max(actual, V/3500.0)) over a grid of inputs", () -> {
            String[] weights = {"0.1", "1.8", "2.0", "2.1", "9.5", "9.999", "10", "10.001", "49.999", "50"};
            int[] sizes = {1, 7, 10, 19, 20, 35, 40, 60, 99};
            int counter = 0;
            for (String weight : weights) {
                for (int length : sizes) {
                    for (int width : sizes) {
                        int height = 10;
                        double expected = Math.ceil(Math.max(Double.parseDouble(weight), (length * width * height) / 3500.0));
                        Box box = new Box(String.format("ABC%04d", counter++ % 10000), "Ana", kg(weight),
                                length, width, height, STANDARD);
                        assertEquals((long) expected, box.getChargeableWeight().longValueExact());
                    }
                }
            }
        });

        test("Dimension combinations that overflow a long are rejected", () -> {
            int max = Integer.MAX_VALUE;
            assertThrows(InvalidParcelException.class, () -> new Box("ABC0001", "Ana", kg("1"), max, max, max, STANDARD));
            assertThrows(InvalidParcelException.class, () -> Box.calculateVolume(2097152, 2097152, 2097152)); // 2^63
        });

        test("The largest representable volume is accepted and computed exactly", () -> {
            int side = 2097151; // side^3 is just below Long.MAX_VALUE
            Box box = new Box("ABC0001", "Ana", kg("1"), side, side, side, STANDARD);
            BigInteger volume = BigInteger.valueOf(side).pow(3);
            BigInteger expectedChargeable = volume.add(BigInteger.valueOf(3499)).divide(BigInteger.valueOf(3500));
            assertEquals(expectedChargeable, box.getChargeableWeight().toBigIntegerExact());
            assertTrue(Double.isFinite(box.getVolumetricWeight()), "volumetric weight must be finite");
            assertMoney(new BigDecimal(expectedChargeable).multiply(new BigDecimal("40")).setScale(2).toPlainString(),
                    box.getBaseFee());
        });

        test("Large but valid single dimension (Integer.MAX_VALUE x 1 x 1) is accepted", () -> {
            Box box = new Box("ABC0001", "Ana", kg("1"), Integer.MAX_VALUE, 1, 1, STANDARD);
            assertNumberEquals("613567", box.getChargeableWeight()); // ceil(2147483647 / 3500)
        });

        test("Base fee = Php 40.00 x chargeable weight (1, 2, 3, 11, 50 kg)", () -> {
            assertMoney("40.00", new Box("ABC0001", "Ana", kg("0.5"), 10, 10, 10, STANDARD).getBaseFee());
            assertMoney("80.00", new Box("ABC0002", "Ana", kg("2"), 10, 10, 10, STANDARD).getBaseFee());
            assertMoney("120.00", new Box("ABC0003", "Ana", kg("1.8"), 40, 20, 10, STANDARD).getBaseFee());
            assertMoney("440.00", new Box("ABC0004", "Ana", kg("10.5"), 10, 10, 10, STANDARD).getBaseFee());
            assertMoney("2000.00", new Box("ABC0005", "Ana", kg("50"), 10, 10, 10, STANDARD).getBaseFee());
        });
    }

    // =====================================================================
    // D. Fragile
    // =====================================================================

    private static void registerFragileTests() {
        section("D. Fragile");

        test("Fragile is a subclass of Box (and of Parcel)", () -> {
            assertEquals(Box.class, Fragile.class.getSuperclass());
            Parcel parcel = sampleFragile("ABC0001", EXPRESS);
            assertTrue(parcel instanceof Box, "a Fragile object must be a Box");
        });

        test("Weight 30 accepted, 29.999 accepted, 30.001 and 31 rejected", () -> {
            new Fragile("ABC0001", "Ana", kg("30"), 10, 10, 10, "Glass", STANDARD);
            new Fragile("ABC0002", "Ana", kg("29.999"), 10, 10, 10, "Glass", STANDARD);
            assertThrows(InvalidParcelException.class, () -> new Fragile("ABC0003", "Ana", kg("30.001"), 10, 10, 10, "Glass", STANDARD));
            assertThrows(InvalidParcelException.class, () -> new Fragile("ABC0004", "Ana", kg("31"), 10, 10, 10, "Glass", STANDARD));
        });

        test("REGRESSION: Box has no constructor that accepts a custom weight limit", () -> {
            // With every class in one folder, a hidden "Box(..., maxWeight)" constructor
            // could be called from anywhere to build a Box above 50 kg.
            assertEquals(1, Box.class.getDeclaredConstructors().length);
            assertTrue(Modifier.isPublic(Box.class.getDeclaredConstructors()[0].getModifiers()), "Box constructor should be public");
        });

        test("Fragile built directly above 30 kg is rejected with the 30 kg message", () -> {
            InvalidParcelException e = assertThrows(InvalidParcelException.class,
                    () -> new Fragile("ABC0001", "Ana", kg("40"), 10, 10, 10, "Glass", STANDARD));
            assertContains(e.getMessage(), "must not exceed 30 kg");
        });

        test("The Fragile limit does not leak into Box (50 kg: Box ok, Fragile rejected)", () -> {
            new Box("ABC0001", "Ana", kg("50"), 10, 10, 10, STANDARD);
            assertThrows(InvalidParcelException.class, () -> new Fragile("ABC0002", "Ana", kg("50"), 10, 10, 10, "Glass", STANDARD));
        });

        test("Invalid dimensions are rejected", () -> {
            assertThrows(InvalidParcelException.class, () -> new Fragile("ABC0001", "Ana", kg("1"), 0, 10, 10, "Glass", STANDARD));
            assertThrows(InvalidParcelException.class, () -> new Fragile("ABC0001", "Ana", kg("1"), 10, -1, 10, "Glass", STANDARD));
            int max = Integer.MAX_VALUE;
            assertThrows(InvalidParcelException.class, () -> new Fragile("ABC0001", "Ana", kg("1"), max, max, max, "Glass", STANDARD));
        });

        test("Blank, whitespace-only and null handling notes are rejected", () -> {
            for (String note : new String[]{"", "   ", "\t", "\u00A0", null}) {
                assertThrows(InvalidParcelException.class,
                        () -> new Fragile("ABC0001", "Ana", kg("1"), 10, 10, 10, note, STANDARD));
            }
        });

        test("Handling note is trimmed; 200 chars accepted, 201 rejected", () -> {
            assertEquals("Handle with care",
                    new Fragile("ABC0001", "Ana", kg("1"), 10, 10, 10, "  Handle with care  ", STANDARD).getHandlingNote());
            assertEquals(200, Fragile.validateHandlingNote("n".repeat(200)).length());
            assertThrows(InvalidParcelException.class, () -> Fragile.validateHandlingNote("n".repeat(201)));
        });

        test("Chargeable weight matches the Box calculation for the same inputs", () -> {
            int[][] cases = {{40, 20, 10}, {35, 20, 10}, {60, 60, 10}, {100, 50, 50}};
            int i = 0;
            for (int[] d : cases) {
                Box box = new Box(String.format("ABC%04d", i++), "Ana", kg("1.8"), d[0], d[1], d[2], STANDARD);
                Fragile fragile = new Fragile(String.format("ABC%04d", i++), "Ana", kg("1.8"), d[0], d[1], d[2], "Glass", STANDARD);
                assertNumberEquals(box.getChargeableWeight().toPlainString(), fragile.getChargeableWeight());
            }
        });

        test("Base fee = Box base fee + Php 100.00 (sample: 120.00 + 100.00 = 220.00)", () -> {
            assertMoney("220.00", sampleFragile("ABC0001", STANDARD).getBaseFee());
            assertMoney("140.00", new Fragile("ABC0002", "Ana", kg("1"), 10, 10, 10, "Glass", STANDARD).getBaseFee());
            assertMoney("1300.00", new Fragile("ABC0003", "Ana", kg("30"), 10, 10, 10, "Glass", STANDARD).getBaseFee());
        });

        test("REQUIRED SAMPLE: Fragile 40x20x10, 1.8 kg, Express -> Php 330.00", () -> {
            Fragile fragile = sampleFragile("ABC0001", EXPRESS);
            assertClose(2.285714, fragile.getVolumetricWeight(), 0.000001);
            assertNumberEquals("3", fragile.getChargeableWeight());
            assertMoney("220.00", fragile.getBaseFee());
            assertMoney("330.00", fragile.getDeliveryFee());
            assertEquals("Php 330.00", Money.format(fragile.getDeliveryFee()));
        });
    }

    // =====================================================================
    // E. Strategies
    // =====================================================================

    private static void registerStrategyTests() {
        section("E. Delivery fee strategies");

        test("Base fee Php 220.00: Standard 220.00, Express 330.00, Same Day 540.00", () -> {
            BigDecimal base = new BigDecimal("220.00");
            assertMoney("220.00", new StandardDeliveryStrategy().calculateFee(base));
            assertMoney("330.00", new ExpressDeliveryStrategy().calculateFee(base));
            assertMoney("540.00", new SameDayDeliveryStrategy().calculateFee(base));
        });

        test("Base fee Php 50.00: Standard 50.00, Express 75.00, Same Day 200.00", () -> {
            BigDecimal base = new BigDecimal("50.00");
            assertMoney("50.00", new StandardDeliveryStrategy().calculateFee(base));
            assertMoney("75.00", new ExpressDeliveryStrategy().calculateFee(base));
            assertMoney("200.00", new SameDayDeliveryStrategy().calculateFee(base));
        });

        test("Results always have exactly 2 decimal places", () -> {
            assertEquals(2, new StandardDeliveryStrategy().calculateFee(new BigDecimal("40")).scale());
            assertEquals(2, new ExpressDeliveryStrategy().calculateFee(new BigDecimal("40")).scale());
            assertEquals(2, new SameDayDeliveryStrategy().calculateFee(new BigDecimal("40")).scale());
        });

        test("Rounding policy is HALF_UP to centavos (0.01 x 1.5 = 0.015 -> 0.02)", () -> {
            assertMoney("0.02", new ExpressDeliveryStrategy().calculateFee(new BigDecimal("0.01")));
            assertMoney("0.05", new ExpressDeliveryStrategy().calculateFee(new BigDecimal("0.03")));
            assertEquals("Php 0.00", Money.format(BigDecimal.ZERO));
            assertEquals("Php 12.35", Money.format(new BigDecimal("12.345")));
        });

        test("Strategies reject a null or negative base fee", () -> {
            for (DeliveryFeeStrategy strategy : List.of(new StandardDeliveryStrategy(),
                    new ExpressDeliveryStrategy(), new SameDayDeliveryStrategy())) {
                assertThrows(IllegalArgumentException.class, () -> strategy.calculateFee(null));
                assertThrows(IllegalArgumentException.class, () -> strategy.calculateFee(new BigDecimal("-1")));
            }
        });

        test("Resolver maps each service to its own strategy class", () -> {
            assertEquals(StandardDeliveryStrategy.class, DeliveryStrategyResolver.resolve(STANDARD).getClass());
            assertEquals(ExpressDeliveryStrategy.class, DeliveryStrategyResolver.resolve(EXPRESS).getClass());
            assertEquals(SameDayDeliveryStrategy.class, DeliveryStrategyResolver.resolve(SAME_DAY).getClass());
        });

        test("Resolver rejects a missing (null) service", () -> {
            assertThrows(InvalidDeliveryServiceException.class, () -> DeliveryStrategyResolver.resolve(null));
        });

        test("Parcel stores the resolved strategy object together with the service", () -> {
            Parcel parcel = sampleFragile("ABC0001", STANDARD);
            assertTrue(readField(parcel, Parcel.class, "deliveryFeeStrategy") instanceof StandardDeliveryStrategy,
                    "STANDARD parcel should hold a StandardDeliveryStrategy");
            parcel.changeDeliveryService(EXPRESS);
            assertTrue(readField(parcel, Parcel.class, "deliveryFeeStrategy") instanceof ExpressDeliveryStrategy,
                    "after switching, the parcel should hold an ExpressDeliveryStrategy");
            assertEquals(EXPRESS, parcel.getDeliveryService());
        });

        test("Fee is recalculated from the base fee on every change (no compounding)", () -> {
            Parcel parcel = sampleFragile("ABC0001", STANDARD);
            String[][] sequence = {{"EXPRESS", "330.00"}, {"STANDARD", "220.00"}, {"EXPRESS", "330.00"},
                    {"EXPRESS", "330.00"}, {"EXPRESS", "330.00"}, {"SAME_DAY", "540.00"}, {"EXPRESS", "330.00"},
                    {"STANDARD", "220.00"}};
            for (String[] step : sequence) {
                parcel.changeDeliveryService(DeliveryService.valueOf(step[0]));
                assertMoney(step[1], parcel.getDeliveryFee());
            }
            assertMoney("220.00", parcel.getBaseFee());
        });
    }

    // =====================================================================
    // Same Day eligibility (uses CHARGEABLE weight)
    // =====================================================================

    private static void registerSameDayEligibilityTests() {
        section("E. Same Day eligibility");

        test("Chargeable weight exactly 10 kg is allowed (Box 10 kg -> fee 900.00)", () -> {
            Box box = new Box("ABC0001", "Ana", kg("10"), 10, 10, 10, SAME_DAY);
            assertNumberEquals("10", box.getChargeableWeight());
            assertMoney("900.00", box.getDeliveryFee());
        });

        test("Actual 9.5 kg rounds up to 10 kg and is allowed", () -> {
            assertEquals(SAME_DAY, new Box("ABC0001", "Ana", kg("9.5"), 10, 10, 10, SAME_DAY).getDeliveryService());
        });

        test("Volumetric exactly 10 kg (35000 cm3) is allowed", () -> {
            Box box = new Box("ABC0001", "Ana", kg("1"), 70, 50, 10, SAME_DAY);
            assertNumberEquals("10", box.getChargeableWeight());
            assertMoney("900.00", box.getDeliveryFee());
        });

        test("Actual 10.001 kg and 10.1 kg round up to 11 kg and are rejected with the exact message", () -> {
            for (String weight : List.of("10.001", "10.1", "11")) {
                InvalidDeliveryServiceException e = assertThrows(InvalidDeliveryServiceException.class,
                        () -> new Box("ABC0001", "Ana", kg(weight), 10, 10, 10, SAME_DAY));
                assertEquals(SAME_DAY_MESSAGE, e.getMessage());
            }
        });

        test("Box with actual 2 kg but volumetric 10.29 kg (chargeable 11) is rejected", () -> {
            InvalidDeliveryServiceException e = assertThrows(InvalidDeliveryServiceException.class,
                    () -> new Box("ABC0001", "Ana", kg("2"), 60, 60, 10, SAME_DAY));
            assertEquals(SAME_DAY_MESSAGE, e.getMessage());
            // The same box is fine with other services:
            assertMoney("660.00", new Box("ABC0001", "Ana", kg("2"), 60, 60, 10, EXPRESS).getDeliveryFee());
        });

        test("Fragile with chargeable 11 kg is rejected", () -> {
            assertThrows(InvalidDeliveryServiceException.class,
                    () -> new Fragile("ABC0001", "Ana", kg("10.5"), 10, 10, 10, "Glass", SAME_DAY));
        });

        test("Fragile sample (chargeable 3 kg) is allowed: fee 540.00", () -> {
            assertMoney("540.00", sampleFragile("ABC0001", SAME_DAY).getDeliveryFee());
        });

        test("Documents (actual weight <= 0.5 kg) are always eligible", () -> {
            assertEquals(SAME_DAY, new Document("ABC0001", "Ana", kg("0.5"), 100, SAME_DAY).getDeliveryService());
            assertEquals(SAME_DAY, new Document("ABC0002", "Ana", kg("0.001"), 1, SAME_DAY).getDeliveryService());
        });

        test("Constructor with a null service creates no parcel", () -> {
            assertThrows(InvalidDeliveryServiceException.class, () -> new Document("ABC0001", "Ana", kg("0.2"), 5, null));
            assertThrows(InvalidDeliveryServiceException.class, () -> new Box("ABC0001", "Ana", kg("2"), 1, 1, 1, null));
        });

        test("Eligibility check rejects nonsense chargeable weights (programming error)", () -> {
            SameDayDeliveryStrategy strategy = new SameDayDeliveryStrategy();
            assertThrows(IllegalArgumentException.class, () -> strategy.checkEligibility(null));
            assertThrows(IllegalArgumentException.class, () -> strategy.checkEligibility(BigDecimal.ZERO));
        });
    }

    // =====================================================================
    // F. Status and state transitions
    // =====================================================================

    private static void registerStateTransitionTests() {
        section("F. Status and state transitions");

        test("New parcels are PENDING", () -> {
            assertEquals(ParcelStatus.PENDING, sampleDocument("ABC0001", STANDARD).getStatus());
            assertEquals(ParcelStatus.PENDING, sampleBox("ABC0002", "2", EXPRESS).getStatus());
            assertEquals(ParcelStatus.PENDING, sampleFragile("ABC0003", SAME_DAY).getStatus());
        });

        test("A pending parcel can be marked DELIVERED and stays in the list", () -> {
            manager().addParcel(sampleDocument("ABC0001", STANDARD));
            Parcel delivered = manager().markAsDelivered("ABC0001");
            assertEquals(ParcelStatus.DELIVERED, delivered.getStatus());
            assertEquals(1, manager().getAllParcels().size());
            assertEquals(ParcelStatus.DELIVERED, manager().getAllParcels().get(0).getStatus());
        });

        test("Marking a delivered parcel again fails every time and status stays DELIVERED", () -> {
            manager().addParcel(sampleDocument("ABC0001", STANDARD));
            manager().markAsDelivered("ABC0001");
            for (int attempt = 0; attempt < 3; attempt++) {
                assertThrows(ParcelAlreadyDeliveredException.class, () -> manager().markAsDelivered("ABC0001"));
            }
            assertEquals(ParcelStatus.DELIVERED, manager().getParcel("ABC0001").getStatus());
        });

        test("Delivered parcel: service change refused (manager and direct call), service and fee unchanged", () -> {
            manager().addParcel(sampleFragile("ABC0001", EXPRESS));
            manager().markAsDelivered("ABC0001");
            Parcel parcel = manager().getParcel("ABC0001");
            for (DeliveryService service : DeliveryService.values()) {
                assertThrows(ParcelAlreadyDeliveredException.class, () -> manager().changeDeliveryService("ABC0001", service));
                assertThrows(ParcelAlreadyDeliveredException.class, () -> parcel.changeDeliveryService(service));
            }
            assertEquals(EXPRESS, parcel.getDeliveryService());
            assertMoney("330.00", parcel.getDeliveryFee());
        });

        test("Delivered parcel with an invalid service is still refused as delivered", () -> {
            Parcel parcel = sampleFragile("ABC0001", EXPRESS);
            parcel.markAsDelivered();
            assertThrows(ParcelAlreadyDeliveredException.class, () -> parcel.changeDeliveryService(null));
        });

        test("Failed Same Day change keeps the old service and fee", () -> {
            manager().addParcel(sampleBox("ABC0001", "10.5", EXPRESS)); // chargeable 11 -> 440 x 1.5
            assertMoney("660.00", manager().getParcel("ABC0001").getDeliveryFee());
            for (int attempt = 0; attempt < 3; attempt++) {
                InvalidDeliveryServiceException e = assertThrows(InvalidDeliveryServiceException.class,
                        () -> manager().changeDeliveryService("ABC0001", SAME_DAY));
                assertEquals(SAME_DAY_MESSAGE, e.getMessage());
            }
            Parcel parcel = manager().getParcel("ABC0001");
            assertEquals(EXPRESS, parcel.getDeliveryService());
            assertMoney("660.00", parcel.getDeliveryFee());
        });

        test("Failed change to a null service keeps the old service and fee", () -> {
            Parcel parcel = sampleFragile("ABC0001", EXPRESS);
            assertThrows(InvalidDeliveryServiceException.class, () -> parcel.changeDeliveryService(null));
            assertEquals(EXPRESS, parcel.getDeliveryService());
            assertMoney("330.00", parcel.getDeliveryFee());
        });

        test("Same Day rejected, then Express accepted", () -> {
            manager().addParcel(sampleBox("ABC0001", "10.5", STANDARD));
            assertThrows(InvalidDeliveryServiceException.class, () -> manager().changeDeliveryService("ABC0001", SAME_DAY));
            manager().changeDeliveryService("ABC0001", EXPRESS);
            assertEquals(EXPRESS, manager().getParcel("ABC0001").getDeliveryService());
            assertMoney("660.00", manager().getParcel("ABC0001").getDeliveryFee());
        });

        test("Changing a pending parcel to the service it already has is harmless", () -> {
            Parcel parcel = sampleFragile("ABC0001", EXPRESS);
            parcel.changeDeliveryService(EXPRESS);
            assertMoney("330.00", parcel.getDeliveryFee());
        });

        test("Operations on unknown tracking numbers throw ParcelNotFoundException", () -> {
            manager().addParcel(sampleDocument("ABC0001", STANDARD));
            assertThrows(ParcelNotFoundException.class, () -> manager().getParcel("ABC9999"));
            assertThrows(ParcelNotFoundException.class, () -> manager().markAsDelivered("ABC9999"));
            assertThrows(ParcelNotFoundException.class, () -> manager().changeDeliveryService("ABC9999", EXPRESS));
            assertThrows(ParcelNotFoundException.class, () -> manager().getParcel(null));
            assertThrows(ParcelNotFoundException.class, () -> manager().getParcel("abc0001"));
        });

        test("Failed constructions never leave anything in the manager", () -> {
            assertThrows(InvalidParcelException.class, () -> manager().addParcel(new Document("ABC0001", "Ana", kg("0.6"), 5, STANDARD)));
            assertThrows(InvalidDeliveryServiceException.class, () -> manager().addParcel(new Box("ABC0002", "Ana", kg("11"), 1, 1, 1, SAME_DAY)));
            assertThrows(InvalidParcelException.class, () -> manager().addParcel(null));
            assertEquals(0, manager().getAllParcels().size());
            assertFalse(manager().isTrackingNumberRegistered("ABC0001"), "ABC0001 must not be registered");
        });
    }

    // =====================================================================
    // Singleton and manager
    // =====================================================================

    private static void registerManagerAndSingletonTests() {
        section("F. Singleton and ParcelManager");

        test("getInstance() always returns the same object", () -> {
            ParcelManager first = ParcelManager.getInstance();
            for (int i = 0; i < 100; i++) {
                assertSame(first, ParcelManager.getInstance());
            }
        });

        test("Two console menus share the same manager instance and data", () -> {
            runConsole(lines("1", "1", "ABC0001", "Ana", "0.2", "5", "1", "5"));
            String output = runConsole(lines("2", "5"));
            assertContains(output, "ABC0001");
        });

        test("ParcelManager is final and has only private constructors", () -> {
            assertTrue(Modifier.isFinal(ParcelManager.class.getModifiers()), "ParcelManager should be final");
            for (Constructor<?> constructor : ParcelManager.class.getDeclaredConstructors()) {
                assertTrue(Modifier.isPrivate(constructor.getModifiers()), "constructor should be private");
            }
        });

        test("Even reflection cannot create a second ParcelManager", () -> {
            Constructor<ParcelManager> constructor = ParcelManager.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            InvocationTargetException e = assertThrows(InvocationTargetException.class, constructor::newInstance);
            assertTrue(e.getCause() instanceof IllegalStateException, "should be refused with IllegalStateException");
        });

        test("getAllParcels() is read-only", () -> {
            manager().addParcel(sampleDocument("ABC0001", STANDARD));
            List<Parcel> view = manager().getAllParcels();
            assertThrows(UnsupportedOperationException.class, () -> view.add(sampleDocument("ABC0002", STANDARD)));
            assertThrows(UnsupportedOperationException.class, view::clear);
            assertThrows(UnsupportedOperationException.class, () -> view.remove(0));
            assertEquals(1, manager().getAllParcels().size());
        });

        test("getAllParcels() is a snapshot: later additions do not appear in an old copy", () -> {
            List<Parcel> before = manager().getAllParcels();
            manager().addParcel(sampleDocument("ABC0001", STANDARD));
            assertEquals(0, before.size());
            assertEquals(1, manager().getAllParcels().size());
        });

        test("Parcels keep insertion order", () -> {
            manager().addParcel(sampleDocument("ABC0003", STANDARD));
            manager().addParcel(sampleDocument("ABC0001", STANDARD));
            manager().addParcel(sampleDocument("ABC0002", STANDARD));
            List<Parcel> all = manager().getAllParcels();
            assertEquals("ABC0003", all.get(0).getTrackingNumber());
            assertEquals("ABC0001", all.get(1).getTrackingNumber());
            assertEquals("ABC0002", all.get(2).getTrackingNumber());
        });
    }

    // =====================================================================
    // Polymorphism, encapsulation and design checks
    // =====================================================================

    private static void registerEncapsulationAndDesignTests() {
        section("Polymorphism, encapsulation and design");

        test("A List<Parcel> runs each subclass's own implementation", () -> {
            List<Parcel> parcels = List.of(sampleDocument("ABC0001", STANDARD),
                    sampleBox("ABC0002", "1.8", STANDARD), sampleFragile("ABC0003", STANDARD));
            String[] expectedTypes = {"Document", "Box", "Fragile"};
            String[] expectedWeights = {"0.3", "2", "3"};
            String[] expectedBaseFees = {"50.00", "80.00", "220.00"};
            for (int i = 0; i < parcels.size(); i++) {
                Parcel parcel = parcels.get(i);
                assertEquals(expectedTypes[i], parcel.getTypeName());
                assertNumberEquals(expectedWeights[i], parcel.getChargeableWeight());
                assertMoney(expectedBaseFees[i], parcel.getBaseFee());
            }
        });

        test("Parcel hierarchy is sealed: Parcel -> {Document, Box}, Box -> {Fragile}", () -> {
            assertTrue(Parcel.class.isSealed(), "Parcel should be sealed");
            assertTrue(Modifier.isAbstract(Parcel.class.getModifiers()), "Parcel should be abstract");
            assertEquals(Set.of(Document.class, Box.class), Set.of(Parcel.class.getPermittedSubclasses()));
            assertTrue(Box.class.isSealed(), "Box should be sealed");
            assertEquals(Set.of(Fragile.class), Set.of(Box.class.getPermittedSubclasses()));
            assertTrue(Modifier.isFinal(Document.class.getModifiers()), "Document should be final");
            assertTrue(Modifier.isFinal(Fragile.class.getModifiers()), "Fragile should be final");
        });

        test("Fragile cannot override the Box chargeable-weight calculation", () -> {
            Method method = Box.class.getDeclaredMethod("getChargeableWeight");
            assertTrue(Modifier.isFinal(method.getModifiers()), "Box.getChargeableWeight should be final");
            assertThrows(NoSuchMethodException.class, () -> Fragile.class.getDeclaredMethod("getChargeableWeight"));
        });

        test("Parcel classes have no public setters", () -> {
            for (Class<?> type : List.of(Parcel.class, Document.class, Box.class, Fragile.class)) {
                for (Method method : type.getMethods()) {
                    assertFalse(method.getName().startsWith("set"), type.getSimpleName() + " exposes " + method.getName());
                }
            }
        });

        test("Identity and size fields are private final", () -> {
            assertPrivateFinal(Parcel.class, "trackingNumber");
            assertPrivateFinal(Parcel.class, "receiverName");
            assertPrivateFinal(Parcel.class, "actualWeightKg");
            assertPrivateFinal(Document.class, "numberOfPages");
            assertPrivateFinal(Box.class, "lengthCm");
            assertPrivateFinal(Box.class, "widthCm");
            assertPrivateFinal(Box.class, "heightCm");
            assertPrivateFinal(Fragile.class, "handlingNote");
            for (String mutable : List.of("status", "deliveryService", "deliveryFeeStrategy")) {
                assertTrue(Modifier.isPrivate(Parcel.class.getDeclaredField(mutable).getModifiers()), mutable + " should be private");
            }
        });

        test("Parcel's constructor is not public (only the parcel classes can call it)", () -> {
            for (Constructor<?> constructor : Parcel.class.getDeclaredConstructors()) {
                assertFalse(Modifier.isPublic(constructor.getModifiers()) || Modifier.isProtected(constructor.getModifiers()),
                        "Parcel constructor should not be public or protected");
            }
        });

        test("Only public status change is PENDING -> DELIVERED (no revert method exists)", () -> {
            for (Method method : Parcel.class.getMethods()) {
                String name = method.getName().toLowerCase();
                assertFalse(name.contains("pending") || name.contains("revert") || name.contains("reset"),
                        "unexpected state method: " + method.getName());
            }
        });

        test("Business rules hold without the menu (direct constructor calls)", () -> {
            assertThrows(InvalidParcelException.class, () -> new Document("ABC0001", "Ana", kg("0.51"), 5, STANDARD));
            assertThrows(InvalidParcelException.class, () -> new Fragile("ABC0001", "Ana", kg("30.5"), 1, 1, 1, "x", STANDARD));
            assertThrows(InvalidDeliveryServiceException.class, () -> new Box("ABC0001", "Ana", kg("20"), 1, 1, 1, SAME_DAY));
            assertThrows(InvalidParcelException.class, () -> new Document("ABC00001", "Ana", kg("0.2"), 5, STANDARD));
        });

        test("Table: long names are shortened, weights and fees formatted, types shown", () -> {
            Parcel longName = new Document("ABC0001", "Maria Clara Ysabel de los Santos-Villanueva", kg("0.25"), 5, STANDARD);
            Parcel fragile = sampleFragile("ABC0002", EXPRESS);
            String table = invokeFormatTable(List.of(longName, fragile));
            assertContains(table, "Maria Clara Ysabel de ...");
            assertNotContains(table, "Villanueva");
            assertContains(table, "0.25 kg");
            assertContains(table, "3 kg");
            assertContains(table, "Php 50.00");
            assertContains(table, "Php 330.00");
            assertContains(table, "Document");
            assertContains(table, "Fragile");
            String[] rows = table.split("\\R");
            assertEquals(4, rows.length);
            int pipeColumn = rows[0].indexOf('|');
            for (String row : rows) {
                if (!row.startsWith("-")) {
                    assertEquals(pipeColumn, row.indexOf('|'));
                }
            }
        });

        test("Viewing the table does not change any parcel", () -> {
            Parcel parcel = sampleFragile("ABC0001", EXPRESS);
            invokeFormatTable(List.of(parcel));
            invokeFormatTable(List.of(parcel));
            assertEquals(EXPRESS, parcel.getDeliveryService());
            assertEquals(ParcelStatus.PENDING, parcel.getStatus());
            assertMoney("330.00", parcel.getDeliveryFee());
        });
    }

    // =====================================================================
    // Console integration tests (whole menu workflows)
    // =====================================================================

    private static void registerConsoleTests() {
        section("G. Console workflows");

        test("REQUIRED SAMPLE through the menu prints Php 330.00", () -> {
            String output = runConsole(lines("1", "3", "ABC0001", "Juan Dela Cruz", "1.8", "40", "20", "10",
                    "Handle with care", "2", "2", "5"));
            assertContains(output, "Parcel added successfully! Delivery fee: Php 330.00");
            assertContains(output, "PARCEL DELIVERY SYSTEM");
            assertContains(output, "INVENTORY SUMMARY");
            assertContains(output, "Enter your choice:");
            assertContains(output, ConsoleMenu.MSG_GOODBYE);
            Parcel saved = manager().getParcel("ABC0001");
            assertTrue(saved instanceof Fragile, "saved parcel should be Fragile");
            assertEquals("Handle with care", ((Fragile) saved).getHandlingNote());
        });

        test("Empty list shows 'No parcels found.'", () -> {
            String output = runConsole(lines("2", "5"));
            assertContains(output, "No parcels found.");
        });

        test("Invalid menu choices re-prompt (empty, letters, decimal, 0, 6, -1, overflow, very long, 01, 001, 0001, +1, 1.0, 1e0, 1abc)", () -> {
            String output = runConsole(lines("", "abc", "1.5", "0", "6", "-1", "99999999999", "x".repeat(5000),
                    "01", "001", "0001", "+1", "1.0", "1e0", "1abc", "02", " 2 ", "5"));
            assertEquals(16, countOccurrences(output, "Error:"));
            assertEquals(0, countOccurrences(output, "--- Add Parcel ---")); // "01", "+1" etc. did not open option 1
            assertContains(output, "Invalid choice. Please enter a number from 1 to 5.");
            assertContains(output, "Input is too long");
            assertContains(output, "No parcels found.");
            assertContains(output, ConsoleMenu.MSG_GOODBYE);
        });

        test("Every Add Parcel field re-prompts after invalid input and keeps earlier answers", () -> {
            String output = runConsole(lines(
                    "1",
                    "4", "doc", "01", "1",                                           // parcel type: 3 errors
                    "abc0001", " ABC0001 ", "ABC001", "ABC0A01", "", "ABC0001",      // tracking: 5 errors
                    "   ", "\u00A0", "A".repeat(150), "  Maria O'Brien-Santos ",      // name: 3 errors
                    "abc", "NaN", "Infinity", "0", "-0", "-1", "0.6", "1e-1", "0.0001", "1.8kg", "0.5", // weight: 10 errors
                    "0", "101", "2.5", "x", "", "-7", "010", "+10", "10",             // pages: 8 errors
                    "4", "x", "", "03", "3",                                          // service: 4 errors
                    "2", "5"));
            assertEquals(33, countOccurrences(output, "Error:"));
            assertContains(output, "Number of pages must not have leading zeros");
            assertContains(output, "Parcel added successfully! Delivery fee: Php 200.00");
            Parcel saved = manager().getParcel("ABC0001");
            assertEquals("Maria O'Brien-Santos", saved.getReceiverName());
            assertNumberEquals("0.5", saved.getActualWeight());
            assertEquals(10, ((Document) saved).getNumberOfPages());
            assertEquals(SAME_DAY, saved.getDeliveryService());
            assertEquals(1, manager().getAllParcels().size());
        });

        test("Box dimensions: invalid values and an overflowing combination are re-prompted", () -> {
            String output = runConsole(lines("1", "2", "ABC0001", "Ana", "1",
                    "0", "-5", "12.5", "abc", "010", "2147483647", "2147483647", "2147483647", // 5 errors + overflow
                    "10", "10", "10", "1", "5"));
            assertContains(output, "dimensions are too large");
            assertContains(output, "Please enter the dimensions again.");
            assertContains(output, "Length must not have leading zeros");
            assertEquals(6, countOccurrences(output, "Error:"));
            assertContains(output, "Parcel added successfully! Delivery fee: Php 40.00");
            Box saved = (Box) manager().getParcel("ABC0001");
            assertEquals(10, saved.getLengthCm());
        });

        test("Weight limits per type are applied in the menu (Fragile 30.5 rejected, 30 accepted)", () -> {
            String output = runConsole(lines("1", "3", "ABC0001", "Ana", "30.5", "50", "30", "10", "10", "10",
                    "   ", "Glass", "1", "5"));
            assertEquals(3, countOccurrences(output, "Error:"));
            assertContains(output, "must not exceed 30 kg");
            assertContains(output, "Parcel added successfully! Delivery fee: Php 1300.00");
        });

        test("Duplicate tracking number during Add re-prompts, then a new number works", () -> {
            String output = runConsole(lines(
                    "1", "2", "ABC0001", "Ana", "2", "10", "10", "10", "1",
                    "1", "1", "ABC0001", "ABC0002", "Ben", "0.2", "5", "1",
                    "5"));
            assertContains(output, "Tracking number ABC0001 already exists");
            assertEquals(2, countOccurrences(output, "Parcel added successfully!"));
            assertEquals(2, manager().getAllParcels().size());
            assertEquals("Ben", manager().getParcel("ABC0002").getReceiverName());
        });

        test("Same Day rejected during Add, then Express accepted (Box 2 kg, 60x60x10)", () -> {
            String output = runConsole(lines("1", "2", "ABC0001", "Ana", "2", "60", "60", "10", "3", "2", "5"));
            assertEquals(1, countOccurrences(output, SAME_DAY_MESSAGE));
            assertContains(output, "Parcel added successfully! Delivery fee: Php 660.00");
            assertEquals(EXPRESS, manager().getParcel("ABC0001").getDeliveryService());
        });

        test("Same Day rejected twice during Add, then Standard accepted (Fragile 10.5 kg)", () -> {
            String output = runConsole(lines("1", "3", "ABC0001", "Ana", "10.5", "10", "10", "10", "Glass",
                    "3", "3", "1", "5"));
            assertEquals(2, countOccurrences(output, SAME_DAY_MESSAGE));
            assertContains(output, "Parcel added successfully! Delivery fee: Php 540.00");
        });

        test("Change Delivery Service: rejected Same Day keeps old service; valid change recalculates from base", () -> {
            String output = runConsole(lines(
                    "1", "2", "ABC0001", "Ana", "2", "60", "60", "10", "2",   // Box, EXPRESS, 660.00
                    "3", "ABC0001", "3", "3", "9", "1",                       // Same Day x2, invalid, Standard
                    "3", "ABC0001", "2",                                      // Express
                    "3", "ABC0001", "2",                                      // Express again: no compounding
                    "3", "ABC0404",                                           // unknown
                    "3", "abc", "ABC0001", "1",                               // invalid format then valid
                    "5"));
            assertEquals(2, countOccurrences(output, SAME_DAY_MESSAGE));
            assertContains(output, "Delivery service changed! New fee: Php 440.00");
            assertEquals(2, countOccurrences(output, "Delivery service changed! New fee: Php 660.00"));
            assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_PARCEL_NOT_FOUND));
            assertContains(output, "Current service: EXPRESS (fee: Php 660.00)");
            assertEquals(STANDARD, manager().getParcel("ABC0001").getDeliveryService());
            assertMoney("440.00", manager().getParcel("ABC0001").getDeliveryFee());
        });

        test("Mark as Delivered: success, repeat, unknown, bad format; delivered parcel locked and still listed", () -> {
            String output = runConsole(lines(
                    "1", "1", "ABC0001", "Ana", "0.3", "3", "1",
                    "4", "ABC0001",
                    "4", "ABC0001",
                    "4", "ABC0009",
                    "4", "abc", "ABC0001",
                    "3", "ABC0001",
                    "1", "1", "ABC0001", "ABC0002", "Ben", "0.3", "3", "2",
                    "2",
                    "5"));
            assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_DELIVERED_SUCCESSFULLY));
            assertEquals(2, countOccurrences(output, ConsoleMenu.MSG_MARK_ALREADY_DELIVERED));
            assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_PARCEL_NOT_FOUND));
            assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_CHANGE_ALREADY_DELIVERED));
            assertContains(output, "Tracking number ABC0001 already exists");
            assertContains(output, "DELIVERED");
            assertEquals(ParcelStatus.DELIVERED, manager().getParcel("ABC0001").getStatus());
            assertEquals(STANDARD, manager().getParcel("ABC0001").getDeliveryService());
            assertEquals(2, manager().getAllParcels().size());
        });

        test("View lists every parcel with its runtime type, weight, service, fee and status", () -> {
            String output = runConsole(lines(
                    "1", "1", "ABC0001", "Ana", "0.25", "3", "1",
                    "1", "2", "ABC0002", "Ben", "1.8", "100", "50", "50", "2",
                    "1", "3", "ABC0003", "Cara", "1.8", "40", "20", "10", "Glass", "3",
                    "4", "ABC0003",
                    "2", "5"));
            assertContains(output, "Tracking No.");
            assertContains(output, "Chargeable Wt");
            assertContains(output, "Delivery Fee");
            assertContains(output, "Php 50.00");    // Document standard
            assertContains(output, "Php 4320.00");  // Box 72 kg -> 2880.00 x 1.5
            assertContains(output, "Php 540.00");   // Fragile same day
            assertContains(output, "0.25 kg");
            assertContains(output, "72 kg");
            assertContains(output, "SAME DAY");
            assertContains(output, "DELIVERED");
            assertContains(output, "PENDING");
        });

        test("EOF at the main menu exits cleanly", () -> {
            String output = runConsole("");
            assertContains(output, ConsoleMenu.MSG_INPUT_ENDED);
        });

        test("EOF in the middle of Add Parcel saves nothing", () -> {
            String output = runConsole(lines("1", "2", "ABC0001", "Ana", "2", "10"));
            assertContains(output, ConsoleMenu.MSG_INPUT_ENDED);
            assertEquals(0, manager().getAllParcels().size());
        });

        test("EOF right after a rejected Same Day choice during Add saves nothing", () -> {
            String output = runConsole(lines("1", "2", "ABC0001", "Ana", "2", "60", "60", "10", "3"));
            assertEquals(1, countOccurrences(output, SAME_DAY_MESSAGE));
            assertEquals(0, manager().getAllParcels().size());
        });

        test("EOF during Change Delivery Service leaves the parcel unchanged", () -> {
            String output = runConsole(lines("1", "2", "ABC0001", "Ana", "2", "60", "60", "10", "2", "3", "ABC0001", "3"));
            assertContains(output, ConsoleMenu.MSG_INPUT_ENDED);
            assertEquals(EXPRESS, manager().getParcel("ABC0001").getDeliveryService());
            assertMoney("660.00", manager().getParcel("ABC0001").getDeliveryFee());
        });

        test("Input without a final newline is still read, then EOF exits", () -> {
            String output = runConsole("2");
            assertContains(output, "No parcels found.");
            assertContains(output, ConsoleMenu.MSG_INPUT_ENDED);
        });
    }

    // =====================================================================
    // H. Return to Main Menu / BACK
    // =====================================================================

    private static void registerReturnAndBackTests() {
        section("H. BACK (one step), CANCEL (main menu) and kept answers");

        test("isBackCommand / isCancelCommand: any letter case, spaces around; nothing else", () -> {
            for (String back : List.of("BACK", "back", "Back", "bAcK", "  BACK  ", "\tback\t")) {
                assertTrue(InputValidator.isBackCommand(back), "should be BACK: [" + back + "]");
                assertFalse(InputValidator.isCancelCommand(back), "BACK is not CANCEL: [" + back + "]");
            }
            for (String cancel : List.of("CANCEL", "cancel", "Cancel", "  CANCEL  ", "\tcAnCeL ")) {
                assertTrue(InputValidator.isCancelCommand(cancel), "should be CANCEL: [" + cancel + "]");
                assertFalse(InputValidator.isBackCommand(cancel), "CANCEL is not BACK: [" + cancel + "]");
            }
            for (String neither : List.of("", "BAC", "BACKS", "BACK1", "go back", "0", "CANCELLED", "CANCEL1", "cancel it")) {
                assertFalse(InputValidator.isBackCommand(neither), "should not be BACK: [" + neither + "]");
                assertFalse(InputValidator.isCancelCommand(neither), "should not be CANCEL: [" + neither + "]");
            }
            assertFalse(InputValidator.isBackCommand(null), "null is not BACK");
            assertFalse(InputValidator.isCancelCommand(null), "null is not CANCEL");
        });

        test("Submenus show '0. Back'; questions show the BACK/CANCEL hint", () -> {
            String output = runConsole(lines("1", "2", "ABC0001", "Ana", "2", "10", "10", "10", "CANCEL", "5"));
            assertContains(output, "===== SELECT PARCEL TYPE =====");
            assertContains(output, "===== DELIVERY SERVICE =====");
            assertEquals(2, countOccurrences(output, "0. Back"));
            assertContains(output, "(BACK/CANCEL)");
            assertContains(output, ConsoleMenu.NAVIGATION_HINT);
        });

        test("0 at the parcel-type menu goes back to the main menu (its previous screen)", () -> {
            String output = runConsole(lines("1", "0", "2", "5"));
            assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_BACK_TO_MAIN_MENU));
            assertEquals(0, countOccurrences(output, ConsoleMenu.MSG_CANCELLED));
            assertEquals(0, countOccurrences(output, "Error:"));
            assertContains(output, "No parcels found.");
            assertContains(output, ConsoleMenu.MSG_GOODBYE);
        });

        test("BACK at the parcel-type menu goes back to the main menu", () -> {
            String output = runConsole(lines("1", "back", "5"));
            assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_BACK_TO_MAIN_MENU));
            assertEquals(0, countOccurrences(output, ConsoleMenu.MSG_STEP_BACK));
            assertEquals(0, countOccurrences(output, "Error:"));
        });

        test("Submenu choices keep strict numbers: 00, 01, +0, 0.0 are errors; only 0 goes back", () -> {
            String output = runConsole(lines("1", "00", "01", "+0", "0.0", "4", "0", "5"));
            assertEquals(5, countOccurrences(output, "Error:"));
            assertContains(output, "Invalid choice. Please enter a number from 0 to 3.");
            assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_BACK_TO_MAIN_MENU));
        });

        test("0 at the delivery-service menu goes back ONE step (to height), not to the main menu", () -> {
            String output = runConsole(lines("1", "2", "ABC0001", "Ana", "2", "10", "10", "10",
                    "0",       // back to the height question
                    "",        // Enter keeps height 10
                    "1", "5"));
            assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_STEP_BACK));
            assertEquals(0, countOccurrences(output, ConsoleMenu.MSG_BACK_TO_MAIN_MENU));
            assertContains(output, "Enter height in cm, whole number [10] (Enter = keep, BACK/CANCEL)");
            assertContains(output, "Parcel added successfully! Delivery fee: Php 80.00");
        });

        test("BACK at every Add Parcel question shows exactly the previous question again", () -> {
            // {answers given before typing BACK..., the previous question that must be asked again}
            List<List<String>> cases = List.of(
                    List.of("1", "2", "===== SELECT PARCEL TYPE ====="),                        // at tracking number
                    List.of("1", "2", "ABC0001", "Enter tracking number, e.g."),                // at receiver name
                    List.of("1", "2", "ABC0001", "Ana", "Enter receiver name"),                 // at weight
                    List.of("1", "2", "ABC0001", "Ana", "2", "Enter actual weight"),            // at length
                    List.of("1", "2", "ABC0001", "Ana", "2", "10", "Enter length"),             // at width
                    List.of("1", "2", "ABC0001", "Ana", "2", "10", "10", "Enter width"),        // at height
                    List.of("1", "2", "ABC0001", "Ana", "2", "10", "10", "10", "Enter height"), // at service (Box)
                    List.of("1", "1", "ABC0001", "Ana", "0.3", "Enter actual weight"),          // at pages
                    List.of("1", "1", "ABC0001", "Ana", "0.3", "5", "Enter number of pages"),   // at service (Document)
                    List.of("1", "3", "ABC0001", "Ana", "1", "10", "10", "10", "Enter height"),           // at note
                    List.of("1", "3", "ABC0001", "Ana", "1", "10", "10", "10", "Glass", "Enter handling note")); // at service
            for (List<String> testCase : cases) {
                resetManager();
                List<String> script = new ArrayList<>(testCase.subList(0, testCase.size() - 1));
                String previousQuestion = testCase.get(testCase.size() - 1);
                script.add("back"); // then the input ends
                String output = runConsole(lines(script.toArray(new String[0])));
                assertEquals(2, countOccurrences(output, previousQuestion));
                assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_STEP_BACK));
                assertEquals(0, countOccurrences(output, ConsoleMenu.MSG_CANCELLED));
                assertEquals(0, countOccurrences(output, ConsoleMenu.MSG_BACK_TO_MAIN_MENU));
                assertEquals(0, countOccurrences(output, "Error:"));
                assertEquals(0, manager().getAllParcels().size());
            }
        });

        test("Going back from the service menu keeps every earlier answer (Enter accepts each one)", () -> {
            String output = runConsole(lines("1", "3", "ABC0001", "Juan", "1.8", "40", "20", "10", "Glass",
                    "BACK", "BACK", "BACK", "BACK", "BACK", "BACK",   // service -> note -> height -> width -> length -> weight -> name
                    "", "", "", "", "", "", "",                        // keep name, weight, length, width, height, note... and type below
                    "2", "5"));
            assertEquals(6, countOccurrences(output, ConsoleMenu.MSG_STEP_BACK));
            assertContains(output, "Enter receiver name [Juan]");
            assertContains(output, "more than 0 and max 30 [1.8]");
            assertContains(output, "Enter handling note [Glass]");
            assertContains(output, "Parcel added successfully! Delivery fee: Php 330.00");
            Fragile saved = (Fragile) manager().getParcel("ABC0001");
            assertEquals("Juan", saved.getReceiverName());
            assertEquals("Glass", saved.getHandlingNote());
            assertEquals(40, saved.getLengthCm());
            assertEquals(1, manager().getAllParcels().size()); // no duplicate parcel
        });

        test("Going back lets you correct the tracking number", () -> {
            String output = runConsole(lines("1", "2", "ABC0001", "BACK", "ABC0002", "Ana", "2", "10", "10", "10", "1", "5"));
            assertContains(output, "Enter tracking number, e.g. ABC0001 [ABC0001]");
            assertTrue(manager().isTrackingNumberRegistered("ABC0002"), "corrected number should be saved");
            assertFalse(manager().isTrackingNumberRegistered("ABC0001"), "first number must not be saved");
        });

        test("Going back lets you correct the receiver name", () -> {
            runConsole(lines("1", "1", "ABC0001", "Wrong Name", "back", "Right Name", "0.3", "5", "1", "5"));
            assertEquals("Right Name", manager().getParcel("ABC0001").getReceiverName());
        });

        test("BACK at pages re-asks the weight; the new weight is used", () -> {
            runConsole(lines("1", "1", "ABC0001", "Ana", "0.3", "BACK", "0.4", "7", "1", "5"));
            Document saved = (Document) manager().getParcel("ABC0001");
            assertNumberEquals("0.4", saved.getActualWeight());
            assertEquals(7, saved.getNumberOfPages());
        });

        test("A kept weight is re-checked when the parcel type changes (2 kg Box -> Document)", () -> {
            String output = runConsole(lines("1", "2", "ABC0001", "Ana", "2",
                    "BACK", "BACK", "BACK", "BACK",   // length -> weight -> name -> tracking -> type
                    "1",                              // switch to Document
                    "", "",                           // keep tracking number and name
                    "",                               // try to keep 2 kg: too heavy for a Document
                    "0.3", "5", "1", "5"));
            assertContains(output, "Enter your choice [2]");
            assertEquals(1, countOccurrences(output, "Error:"));
            assertContains(output, "must not exceed 0.5 kg");
            Document saved = (Document) manager().getParcel("ABC0001");
            assertNumberEquals("0.3", saved.getActualWeight());
            assertEquals("Ana", saved.getReceiverName());
        });

        test("Pressing Enter with no earlier answer is still an error (no silent default)", () -> {
            String output = runConsole(lines("1", "1", "", "ABC0001", "", "Ana", "", "0.3", "", "5", "", "1", "5"));
            assertEquals(5, countOccurrences(output, "Error:"));
            assertContains(output, "Parcel added successfully! Delivery fee: Php 50.00");
        });

        test("Same Day refused, BACK to height (kept 10 shown), smaller height makes Same Day allowed", () -> {
            // 60 x 60 x 10 -> 11 kg (Same Day refused); 60 x 60 x 5 -> 6 kg; (6 x 40.00) x 2 + 100.00 = 580.00
            String output = runConsole(lines("1", "2", "ABC0001", "Ana", "2", "60", "60", "10", "3", "BACK", "5", "3", "5"));
            assertEquals(1, countOccurrences(output, SAME_DAY_MESSAGE));
            assertContains(output, "Enter height in cm, whole number [10]");
            assertContains(output, "Parcel added successfully! Delivery fee: Php 580.00");
            assertEquals(5, ((Box) manager().getParcel("ABC0001")).getHeightCm());
        });

        test("Unusable dimension combination clears the three values and asks them again", () -> {
            String output = runConsole(lines("1", "2", "ABC0001", "Ana", "1",
                    "2147483647", "2147483647", "2147483647", "10", "10", "10", "1", "5"));
            assertContains(output, "dimensions are too large");
            assertNotContains(output, "[2147483647]"); // not offered as a value to keep
            assertContains(output, "Parcel added successfully! Delivery fee: Php 40.00");
        });

        test("BACK one step at a time walks back to the main menu without saving", () -> {
            String output = runConsole(lines("1", "2", "ABC0001", "Ana", "2",
                    "BACK", "BACK", "BACK", "BACK", "BACK",   // length -> weight -> name -> tracking -> type -> menu
                    "2", "5"));
            assertEquals(4, countOccurrences(output, ConsoleMenu.MSG_STEP_BACK));
            assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_BACK_TO_MAIN_MENU));
            assertContains(output, "No parcels found.");
            assertEquals(0, manager().getAllParcels().size());
        });

        test("Going back to the type menu and choosing another type asks that type's questions", () -> {
            String output = runConsole(lines("1", "2", "ABC0001", "BACK", "BACK", "1",
                    "", "Ana", "0.3", "5", "1", "5"));
            assertEquals(2, countOccurrences(output, ConsoleMenu.MSG_STEP_BACK));
            assertContains(output, "Enter number of pages");
            assertNotContains(output, "Enter length");
            assertTrue(manager().getParcel("ABC0001") instanceof Document, "should be saved as a Document");
        });

        test("back, Back, BACK and '  BACK  ' go back one step (never an invalid tracking number)", () -> {
            for (String word : List.of("back", "Back", "BACK", "  BACK  ", "\tbAcK ")) {
                resetManager();
                String output = runConsole(lines("1", "1", word, "0", "5"));
                assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_STEP_BACK));
                assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_BACK_TO_MAIN_MENU));
                assertContains(output, "Enter your choice [1]"); // type menu again, earlier answer shown
                assertEquals(0, countOccurrences(output, "Error:"));
            }
        });

        test("cancel, Cancel, CANCEL and '  CANCEL  ' abandon the whole registration", () -> {
            for (String word : List.of("cancel", "Cancel", "CANCEL", "  CANCEL  ", "\tcAnCeL ")) {
                resetManager();
                String output = runConsole(lines("1", "2", "ABC0001", "Ana", "2", "10", word, "2", "5"));
                assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_CANCELLED));
                assertEquals(0, countOccurrences(output, ConsoleMenu.MSG_STEP_BACK));
                assertEquals(0, countOccurrences(output, "Error:"));
                assertContains(output, "No parcels found.");
                assertEquals(0, manager().getAllParcels().size());
            }
        });

        test("CANCEL at every Add Parcel question (and in both submenus) saves nothing", () -> {
            List<List<String>> stops = List.of(
                    List.of("1"),                                                  // parcel-type menu
                    List.of("1", "2"),                                             // tracking number
                    List.of("1", "2", "ABC0001"),                                  // receiver name
                    List.of("1", "2", "ABC0001", "Ana"),                           // weight
                    List.of("1", "2", "ABC0001", "Ana", "2"),                      // length
                    List.of("1", "2", "ABC0001", "Ana", "2", "10"),                // width
                    List.of("1", "2", "ABC0001", "Ana", "2", "10", "10"),          // height
                    List.of("1", "2", "ABC0001", "Ana", "2", "10", "10", "10"),    // delivery-service menu
                    List.of("1", "1", "ABC0001", "Ana", "0.3"),                    // pages
                    List.of("1", "3", "ABC0001", "Ana", "1", "10", "10", "10"));   // handling note
            for (List<String> before : stops) {
                resetManager();
                List<String> script = new ArrayList<>(before);
                script.add("CANCEL");
                script.add("5");
                String output = runConsole(lines(script.toArray(new String[0])));
                assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_CANCELLED));
                assertEquals(0, countOccurrences(output, "Error:"));
                assertEquals(0, manager().getAllParcels().size());
                assertContains(output, ConsoleMenu.MSG_GOODBYE);
            }
        });

        test("Several invalid entries, then BACK, re-asks the previous question", () -> {
            String output = runConsole(lines("1", "2", "ABC0001", "Ana",
                    "abc", "01x", "-1", "60", " back ",
                    "Ana Two", "2", "10", "10", "10", "1", "5"));
            assertEquals(4, countOccurrences(output, "Error:"));
            assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_STEP_BACK));
            assertEquals("Ana Two", manager().getParcel("ABC0001").getReceiverName());
        });

        test("Several invalid entries, then CANCEL, returns to the main menu", () -> {
            String output = runConsole(lines("1", "2", "ABC0001", "Ana", "abc", "01x", "-1", " Cancel ", "2", "5"));
            assertEquals(3, countOccurrences(output, "Error:"));
            assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_CANCELLED));
            assertEquals(0, manager().getAllParcels().size());
        });

        test("Change service: BACK at the service menu returns to the tracking number (kept)", () -> {
            manager().addParcel(sampleBox("ABC0001", "10.5", EXPRESS)); // 660.00
            String output = runConsole(lines("3", "ABC0001", "BACK", "", "1", "5"));
            assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_STEP_BACK));
            assertContains(output, "Enter tracking number [ABC0001]");
            assertContains(output, "Delivery service changed! New fee: Php 440.00");
            assertEquals(STANDARD, manager().getParcel("ABC0001").getDeliveryService());
        });

        test("Change service: BACK then a different tracking number changes only that parcel", () -> {
            manager().addParcel(sampleBox("ABC0001", "10.5", EXPRESS));
            manager().addParcel(sampleBox("ABC0002", "10.5", EXPRESS));
            String output = runConsole(lines("3", "ABC0001", "0", "ABC0002", "1", "5"));
            assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_STEP_BACK));
            assertEquals(EXPRESS, manager().getParcel("ABC0001").getDeliveryService());
            assertMoney("660.00", manager().getParcel("ABC0001").getDeliveryFee());
            assertEquals(STANDARD, manager().getParcel("ABC0002").getDeliveryService());
        });

        test("Change service: leaving with BACK, 0 or CANCEL keeps the old service and fee", () -> {
            manager().addParcel(sampleBox("ABC0001", "10.5", EXPRESS)); // chargeable 11 kg, Php 660.00
            String output = runConsole(lines(
                    "3", "ABC0001", "0", "BACK",             // service -> tracking -> main menu
                    "3", "Back",                             // tracking -> main menu
                    "3", "ABC0001", "3", "CANCEL",           // Same Day refused, then CANCEL
                    "5"));
            assertEquals(2, countOccurrences(output, ConsoleMenu.MSG_BACK_TO_MAIN_MENU));
            assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_STEP_BACK));
            assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_CANCELLED));
            assertEquals(0, countOccurrences(output, ConsoleMenu.MSG_SERVICE_CHANGED));
            Parcel parcel = manager().getParcel("ABC0001");
            assertEquals(EXPRESS, parcel.getDeliveryService());
            assertMoney("660.00", parcel.getDeliveryFee());
            assertEquals(ParcelStatus.PENDING, parcel.getStatus());
        });

        test("Mark as Delivered: BACK and CANCEL return to the main menu without delivering", () -> {
            manager().addParcel(sampleDocument("ABC0001", STANDARD));
            String output = runConsole(lines("4", "  BACK  ", "4", "cancel", "4", "abc", "ABC00001", "back",
                    "2", "4", "ABC0001", "5"));
            assertEquals(2, countOccurrences(output, ConsoleMenu.MSG_BACK_TO_MAIN_MENU));
            assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_CANCELLED));
            assertEquals(2, countOccurrences(output, "Error:"));
            assertContains(output, "PENDING"); // still pending when viewed
            assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_DELIVERED_SUCCESSFULLY));
            assertEquals(ParcelStatus.DELIVERED, manager().getParcel("ABC0001").getStatus());
        });

        test("BACK and CANCEL at the main menu are just invalid choices", () -> {
            String output = runConsole(lines("BACK", "cancel", "2", "5"));
            assertEquals(0, countOccurrences(output, ConsoleMenu.MSG_CANCELLED));
            assertEquals(0, countOccurrences(output, ConsoleMenu.MSG_BACK_TO_MAIN_MENU));
            assertEquals(2, countOccurrences(output, "Invalid choice. Please enter a number from 1 to 5."));
            assertContains(output, "No parcels found.");
        });

        test("Values that only contain 'back' or 'cancel' are ordinary data", () -> {
            String output = runConsole(lines("1", "3", "BACK1", "ABC0001", "Backer", "1", "10", "10", "10",
                    "Cancel if damaged", "1", "5"));
            assertEquals(1, countOccurrences(output, "Error:")); // BACK1 is an invalid tracking number
            assertEquals(0, countOccurrences(output, ConsoleMenu.MSG_STEP_BACK));
            assertEquals(0, countOccurrences(output, ConsoleMenu.MSG_CANCELLED));
            Fragile saved = (Fragile) manager().getParcel("ABC0001");
            assertEquals("Backer", saved.getReceiverName());
            assertEquals("Cancel if damaged", saved.getHandlingNote());
        });

        test("Many back-and-forth steps do not loop or create duplicates", () -> {
            List<String> script = new ArrayList<>(List.of("1", "1"));
            for (int i = 0; i < 30; i++) {
                script.add(i == 0 ? "ABC0001" : ""); // first time type it, then keep it
                script.add("BACK");                  // at the receiver name -> tracking number again
            }
            script.addAll(List.of("", "Ana", "0.3", "5", "1", "5"));
            String output = runConsole(lines(script.toArray(new String[0])));
            assertEquals(30, countOccurrences(output, ConsoleMenu.MSG_STEP_BACK));
            assertEquals(1, manager().getAllParcels().size());
            assertContains(output, ConsoleMenu.MSG_GOODBYE);
        });

        test("Many returns to the main menu in a row do not loop or close the program", () -> {
            List<String> script = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                script.add("1");
                script.add(List.of("0", "BACK", "CANCEL").get(i % 3));
            }
            script.add("2");
            script.add("5");
            String output = runConsole(lines(script.toArray(new String[0])));
            assertEquals(40, countOccurrences(output, ConsoleMenu.MSG_BACK_TO_MAIN_MENU));
            assertEquals(20, countOccurrences(output, ConsoleMenu.MSG_CANCELLED));
            assertContains(output, "No parcels found.");
            assertContains(output, ConsoleMenu.MSG_GOODBYE);
        });

        test("EOF right after going back or cancelling exits cleanly and saves nothing", () -> {
            String output = runConsole(lines("1", "CANCEL"));
            assertContains(output, ConsoleMenu.MSG_CANCELLED);
            assertContains(output, ConsoleMenu.MSG_INPUT_ENDED);
            output = runConsole(lines("1", "2", "ABC0001", "Ana", "BACK"));
            assertContains(output, ConsoleMenu.MSG_INPUT_ENDED);
            assertEquals(0, manager().getAllParcels().size());
        });
    }

    // =====================================================================
    // I. Inventory-aware main menu
    // =====================================================================

    private static void registerInventoryTests() {
        section("I. Inventory summary and availability");

        test("Empty start: summary shows zeros and options 3 and 4 are [UNAVAILABLE]", () -> {
            String output = runConsole(lines("5"));
            assertContains(output, "PARCEL DELIVERY SYSTEM");
            assertContains(output, "INVENTORY SUMMARY\nTotal Parcels: 0\nPending: 0\nDelivered: 0");
            assertContains(output, "3. Change Delivery Service [UNAVAILABLE]");
            assertContains(output, "4. Mark as Delivered [UNAVAILABLE]");
            assertContains(output, "2. View Inventory / All Parcels");
            assertNotContains(output, "1. Add Parcel [UNAVAILABLE]");
        });

        test("Choosing 3 or 4 on an empty inventory is refused before any question", () -> {
            String output = runConsole(lines("3", "4", "3", "5"));
            assertEquals(3, countOccurrences(output, ConsoleMenu.MSG_EMPTY_INVENTORY));
            assertNotContains(output, "Enter tracking number");
            assertNotContains(output, "--- Change Delivery Service ---");
            assertNotContains(output, "--- Mark as Delivered ---");
            assertContains(output, ConsoleMenu.MSG_GOODBYE);
        });

        test("Viewing an empty inventory shows 'No parcels found.'", () -> {
            String output = runConsole(lines("2", "5"));
            assertContains(output, "No parcels found.");
            assertNotContains(output, "Status Summary:");
        });

        test("Adding a parcel updates the summary and removes [UNAVAILABLE]", () -> {
            String output = runConsole(lines("1", "1", "ABC0001", "Ana", "0.3", "5", "1", "5"));
            String lastMenu = output.substring(output.lastIndexOf("PARCEL DELIVERY SYSTEM"));
            assertContains(lastMenu, "Total Parcels: 1\nPending: 1\nDelivered: 0");
            assertNotContains(lastMenu, "[UNAVAILABLE]");
            assertEquals(2, countOccurrences(output, "[UNAVAILABLE]")); // only on the first menu
        });

        test("Several parcels and a delivery: totals, pending and delivered are correct", () -> {
            String output = runConsole(lines(
                    "1", "1", "ABC0001", "Ana", "0.3", "5", "1",
                    "1", "2", "ABC0002", "Ben", "2", "10", "10", "10", "2",
                    "1", "3", "ABC0003", "Cara", "1.8", "40", "20", "10", "Glass", "2",
                    "4", "ABC0002",
                    "5"));
            String lastMenu = output.substring(output.lastIndexOf("PARCEL DELIVERY SYSTEM"));
            assertContains(lastMenu, "Total Parcels: 3\nPending: 2\nDelivered: 1");
            assertEquals(3, manager().getParcelCount());
            assertEquals(2, manager().countByStatus(ParcelStatus.PENDING));
            assertEquals(1, manager().countByStatus(ParcelStatus.DELIVERED));
        });

        test("Inventory view: table with status, peso fees, and a status summary", () -> {
            manager().addParcel(sampleDocument("ABC0001", STANDARD));
            manager().addParcel(sampleFragile("ABC0002", EXPRESS));
            manager().markAsDelivered("ABC0001");
            String output = runConsole(lines("2", "5"));
            assertContains(output, "===================== INVENTORY =====================");
            assertContains(output, "Status");
            assertContains(output, "DELIVERED");
            assertContains(output, "PENDING");
            assertContains(output, "Php 50.00");
            assertContains(output, "Php 330.00");
            assertContains(output, "Status Summary:\nTotal Parcels: 2\nPending: 1\nDelivered: 1");
            assertEquals(2, manager().getAllParcels().size()); // delivered parcel still listed
        });

        test("Only delivered parcels: options 3 and 4 still work and give the right messages", () -> {
            manager().addParcel(sampleDocument("ABC0001", STANDARD));
            manager().markAsDelivered("ABC0001");
            String output = runConsole(lines("3", "ABC0001", "4", "ABC0001", "3", "ABC0404", "4", "ABC0404", "5"));
            assertEquals(0, countOccurrences(output, "[UNAVAILABLE]"));
            assertEquals(0, countOccurrences(output, ConsoleMenu.MSG_EMPTY_INVENTORY));
            assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_CHANGE_ALREADY_DELIVERED));
            assertEquals(1, countOccurrences(output, ConsoleMenu.MSG_MARK_ALREADY_DELIVERED));
            assertEquals(2, countOccurrences(output, ConsoleMenu.MSG_PARCEL_NOT_FOUND));
            assertContains(output, "Total Parcels: 1\nPending: 0\nDelivered: 1");
        });

        test("Summary is current after a cancelled operation (not counted as added)", () -> {
            manager().addParcel(sampleDocument("ABC0001", STANDARD));
            String output = runConsole(lines("1", "1", "ABC0002", "Ana", "CANCEL", "5"));
            String lastMenu = output.substring(output.lastIndexOf("PARCEL DELIVERY SYSTEM"));
            assertContains(lastMenu, "Total Parcels: 1\nPending: 1\nDelivered: 0");
        });

        test("Manager counts come from the list and always add up", () -> {
            assertFalse(manager().hasParcels(), "empty at start");
            assertEquals(0, manager().getParcelCount());
            manager().addParcel(sampleDocument("ABC0001", STANDARD));
            manager().addParcel(sampleBox("ABC0002", "2", STANDARD));
            manager().addParcel(sampleFragile("ABC0003", STANDARD));
            manager().markAsDelivered("ABC0003");
            assertTrue(manager().hasParcels(), "has parcels");
            assertEquals(3, manager().getParcelCount());
            assertEquals(2, manager().countByStatus(ParcelStatus.PENDING));
            assertEquals(1, manager().countByStatus(ParcelStatus.DELIVERED));
            // A failed add and a failed re-delivery do not change any count.
            assertThrows(DuplicateTrackingNumberException.class, () -> manager().addParcel(sampleDocument("ABC0001", STANDARD)));
            assertThrows(ParcelAlreadyDeliveredException.class, () -> manager().markAsDelivered("ABC0003"));
            assertEquals(3, manager().getParcelCount());
            assertEquals(1, manager().countByStatus(ParcelStatus.DELIVERED));
        });

        test("Manager refuses changes on an empty inventory even without the menu", () -> {
            assertThrows(ParcelNotFoundException.class, () -> manager().changeDeliveryService("ABC0001", EXPRESS));
            assertThrows(ParcelNotFoundException.class, () -> manager().markAsDelivered("ABC0001"));
            assertEquals(0, manager().getParcelCount());
        });
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static void section(String name) {
        currentSection = name;
    }

    private static void test(String name, TestBody body) {
        TESTS.add(new TestCase(currentSection, name, body));
    }

    private static ParcelManager manager() {
        return ParcelManager.getInstance();
    }

    /**
     * Test-only: empties the singleton's list so each test starts clean.
     * The production code deliberately offers no way to clear parcels, so the
     * test reaches into the private field with reflection.
     */
    private static void resetManager() throws ReflectiveOperationException {
        Field field = ParcelManager.class.getDeclaredField("parcels");
        field.setAccessible(true);
        ((List<?>) field.get(ParcelManager.getInstance())).clear();
    }

    private static BigDecimal kg(String value) {
        return new BigDecimal(value);
    }

    private static Document sampleDocument(String trackingNumber, DeliveryService service) throws Exception {
        return new Document(trackingNumber, "Ana Reyes", kg("0.3"), 10, service);
    }

    /** A 10 x 10 x 10 box, so the chargeable weight is driven by the actual weight. */
    private static Box sampleBox(String trackingNumber, String actualWeight, DeliveryService service) throws Exception {
        return new Box(trackingNumber, "Ben Cruz", kg(actualWeight), 10, 10, 10, service);
    }

    /** The specification's sample: 40 x 20 x 10 cm, 1.8 kg. */
    private static Fragile sampleFragile(String trackingNumber, DeliveryService service) throws Exception {
        return new Fragile(trackingNumber, "Juan Dela Cruz", kg("1.8"), 40, 20, 10, "Handle with care", service);
    }

    private static String lines(String... inputLines) {
        return String.join("\n", inputLines) + "\n";
    }

    /** Runs the real console menu on scripted input; fails if it does not finish (e.g. EOF loop). */
    private static String runConsole(String scriptedInput) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        Scanner scanner = new Scanner(new ByteArrayInputStream(scriptedInput.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "console-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<?> run = executor.submit(() -> new ConsoleMenu(scanner, out).run());
            run.get(CONSOLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException crashed) {
            throw new AssertionError("Console crashed: " + crashed.getCause(), crashed.getCause());
        } catch (java.util.concurrent.TimeoutException hung) {
            throw new AssertionError("Console did not finish within " + CONSOLE_TIMEOUT_SECONDS + " s (infinite loop?)");
        } finally {
            executor.shutdownNow();
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static String invokeFormatTable(List<Parcel> parcels) {
        return ConsoleMenu.formatParcelTable(parcels);
    }

    private static Object readField(Object target, Class<?> owner, String fieldName) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static int countOccurrences(String text, String fragment) {
        int count = 0;
        int index = text.indexOf(fragment);
        while (index >= 0) {
            count++;
            index = text.indexOf(fragment, index + fragment.length());
        }
        return count;
    }

    // ---------------- assertions ----------------

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertSame(Object expected, Object actual) {
        if (expected != actual) {
            throw new AssertionError("expected the same object instance");
        }
    }

    /** Money must match value AND have exactly 2 decimal places. */
    private static void assertMoney(String expected, BigDecimal actual) {
        if (actual == null || !new BigDecimal(expected).equals(actual)) {
            throw new AssertionError("expected Php " + expected + " but was " + (actual == null ? "null" : actual.toPlainString()));
        }
    }

    /** Numeric comparison that ignores scale (2 == 2.0). */
    private static void assertNumberEquals(String expected, BigDecimal actual) {
        if (actual == null || new BigDecimal(expected).compareTo(actual) != 0) {
            throw new AssertionError("expected " + expected + " but was " + (actual == null ? "null" : actual.toPlainString()));
        }
    }

    private static void assertClose(double expected, double actual) {
        assertClose(expected, actual, 1e-12);
    }

    private static void assertClose(double expected, double actual, double tolerance) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError("expected " + expected + " +/- " + tolerance + " but was " + actual);
        }
    }

    private static void assertContains(String text, String fragment) {
        if (!text.contains(fragment)) {
            throw new AssertionError("expected text to contain \"" + fragment + "\"");
        }
    }

    private static void assertNotContains(String text, String fragment) {
        if (text.contains(fragment)) {
            throw new AssertionError("expected text NOT to contain \"" + fragment + "\"");
        }
    }

    private static void assertPrivateFinal(Class<?> type, String fieldName) throws NoSuchFieldException {
        int modifiers = type.getDeclaredField(fieldName).getModifiers();
        assertTrue(Modifier.isPrivate(modifiers) && Modifier.isFinal(modifiers),
                type.getSimpleName() + "." + fieldName + " should be private final");
    }

    private static <T extends Throwable> T assertThrows(Class<T> expectedType, TestBody body) {
        try {
            body.run();
        } catch (Throwable thrown) {
            if (expectedType.isInstance(thrown)) {
                return expectedType.cast(thrown);
            }
            throw new AssertionError("expected " + expectedType.getSimpleName() + " but got " + thrown);
        }
        throw new AssertionError("expected " + expectedType.getSimpleName() + " but nothing was thrown");
    }
}
