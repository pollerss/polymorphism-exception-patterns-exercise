# Parcel Delivery System

A menu-driven Java console application for registering parcels, calculating delivery fees, changing delivery services, and marking parcels as delivered.

It was written to demonstrate four concepts with real, working code:

1. **Polymorphism**
2. **Exception handling**
3. **Singleton design pattern**
4. **Strategy design pattern**

Repository name: `polymorphism-exception-patterns-exercise`

---

## Features

The main menu always starts with an up-to-date inventory summary:

```text
========================================
       PARCEL DELIVERY SYSTEM
========================================
INVENTORY SUMMARY
Total Parcels: 0
Pending: 0
Delivered: 0
----------------------------------------
1. Add Parcel
2. View Inventory / All Parcels
3. Change Delivery Service [UNAVAILABLE]
4. Mark as Delivered [UNAVAILABLE]
5. Exit
========================================
Enter your choice:
```

The counts are worked out from the actual parcel list every time the menu is shown (`ParcelManager.getParcelCount()` and `countByStatus(...)`), so they can never be out of date. Delivered parcels stay in the inventory and are counted as Delivered.

| Menu option | What it does |
|---|---|
| 1. Add Parcel | Registers a **Document**, **Box**, or **Fragile** parcel. Every field is validated as it is typed and re-asked if invalid. The parcel is saved only once it is complete and valid. Always available. |
| 2. View Inventory / All Parcels | Prints an aligned table (tracking number, type, receiver, chargeable weight, service, delivery fee, status) followed by a status summary. Shows `No parcels found.` when empty. Always available. |
| 3. Change Delivery Service | Switches a pending parcel to another service and recalculates the fee from its base fee. Delivered parcels cannot be changed. |
| 4. Mark as Delivered | Moves a parcel from `PENDING` to `DELIVERED` (once only). The parcel stays in the inventory. |
| 5. Exit | Prints a goodbye message and ends the program normally. Always available. |

**Options 3 and 4 need at least one parcel.** While the inventory is empty they are marked `[UNAVAILABLE]`, and choosing one anyway prints `No parcels in inventory. Please add a parcel first.` without asking anything. The check is done when the option is chosen, not just shown as a label. An inventory that only holds delivered parcels is *not* empty, so options 3 and 4 still work there and give the correct `Parcel already delivered!` / `Parcel was already delivered.` / `Parcel not found!` messages.

Required messages are printed exactly as specified, for example
`Parcel added successfully! Delivery fee: Php 330.00`,
`Same Day delivery is only for parcels up to 10 kg!`,
`Parcel not found!`, `Parcel already delivered!`, `Parcel was already delivered.`, and `Parcel delivered successfully!`.

### Navigation: BACK, CANCEL and keeping your answers

| You type | What happens |
|---|---|
| `BACK` (or `0` in a numbered submenu) | Goes back **one step**: the previous question or screen is shown again and `Going back to the previous step...` is printed. From the first question of an operation (the parcel-type menu, or the tracking number in options 3 and 4) the previous screen is the main menu, so you go there (`Going back to the main menu...`). |
| `CANCEL` | Abandons the whole operation and goes straight to the main menu (`Operation cancelled. Returning to main menu...`). |
| Enter (empty) on a question you already answered | Keeps your earlier answer, shown in brackets: `Enter receiver name [Ana] (Enter = keep, BACK/CANCEL):` |

Examples:
- `BACK` at the weight question asks for the receiver name again, showing the name you typed.
- `BACK` (or `0`) at the delivery-service menu goes back to the last detail you entered (pages, height or handling note), not to the main menu. Press Enter to keep each earlier answer and you are back at the service menu without retyping anything.
- In Change Delivery Service, `BACK` at the service menu goes back to the tracking-number question (the number you entered is kept).

Kept answers are checked again before they are accepted, because the rules may have changed. For example, if you go back to the parcel-type menu and switch from Box to Document, a kept weight of 2 kg is refused because a Document may weigh at most 0.5 kg. If three box dimensions turn out to be too large together, all three are cleared and asked again.

`BACK` and `CANCEL` work in any letter case and with spaces around them. They are commands, so they are checked before the field is validated and never cause an "invalid number" or "invalid tracking number" error. Only the exact words count: `Backer` or `Cancel if damaged` are ordinary data. On the main menu itself, `BACK` and `CANCEL` are simply invalid choices.

Nothing is saved or changed until the very last step: a parcel is saved only after a valid delivery service is accepted, a service change happens only when the new service is accepted, and a parcel is only marked delivered after its tracking number is entered. Going back or cancelling therefore never leaves a half-finished parcel or changes an existing one.

### Business rules

| | Document | Box | Fragile (extends Box) |
|---|---|---|---|
| Extra attributes | Pages (1-100) | Length, width, height (whole cm, > 0) | Box dimensions + handling note (not blank) |
| Max **actual** weight | 0.5 kg | 50 kg | 30 kg |
| Chargeable weight | Exact actual weight | `ceil(max(actual, L x W x H / 3500.0))` | Same as Box |
| Base fee | Php 50.00 flat | Php 40.00 x chargeable weight | Box base fee + Php 100.00 |

| Service | Delivery fee |
|---|---|
| STANDARD | Base fee |
| EXPRESS | Base fee x 1.5 |
| SAME DAY | (Base fee x 2) + Php 100.00, **only if chargeable weight <= 10 kg** |

Common rules: tracking number must be exactly `ABC` + 4 digits (e.g. `ABC0001`) and unique, even against delivered parcels; receiver name must not be blank (it is trimmed); weight must be a positive number with at most 3 decimal places (1 gram); new parcels always start as `PENDING`.

Numbered choices (menus, parcel type, delivery service) and whole-number fields (pages, length, width, height) must be written plainly: `1`, `10` and `100` are accepted, but `01`, `001`, `0001`, `+1`, `1.0`, `1e0` and `1abc` are rejected and asked again. Tracking numbers are identifiers, not quantities, so `ABC0001` keeps its zeros and is accepted.

---

## How the four concepts are used

### 1. Polymorphism
`Parcel` is an abstract class with three abstract methods: `getTypeName()`, `getChargeableWeight()`, and `getBaseFee()`. `Document`, `Box`, and `Fragile` each implement them differently.

`ParcelManager` stores a `List<Parcel>`, and the view table calls `parcel.getTypeName()`, `parcel.getChargeableWeight()`, and `parcel.getDeliveryFee()` on plain `Parcel` references. Java picks the right subclass method at runtime, so there is no `instanceof` chain anywhere in the fee or weight logic.

`Fragile extends Box` and reuses the inherited dimensions and chargeable-weight calculation unchanged. It overrides only `getTypeName()` and `getBaseFee()` (which calls `super.getBaseFee()` and adds the handling fee).

The hierarchy is **sealed** (`Parcel permits Document, Box`; `Box permits Fragile`), so no other class can extend it and skip the rules.

### 2. Exception handling
Expected, recoverable problems are checked exceptions that extend `ParcelSystemException`:

| Exception | Thrown when |
|---|---|
| `InvalidInputException` | Console text cannot be parsed (letters in a number field, decimals or leading zeros where a whole number is needed, menu choice out of range). |
| `InvalidParcelException` | A value breaks a business rule (bad tracking format, blank name, weight over the limit, pages out of range, dimension <= 0 or too large). |
| `DuplicateTrackingNumberException` | The tracking number is already registered. |
| `ParcelNotFoundException` | No parcel has the given tracking number. |
| `InvalidDeliveryServiceException` | No service given, or Same Day chosen for a parcel above 10 kg chargeable weight. |
| `ParcelAlreadyDeliveredException` | Trying to change or re-deliver a delivered parcel. |

`EndOfInputException` (unchecked) signals that the console input ended (Ctrl+D / Ctrl+Z). It unwinds back to the menu loop, which exits cleanly.

`OperationCancelledException` (unchecked) signals that the user typed `CANCEL`. It is caught in exactly one place, the main menu loop in `ConsoleMenu.run()`, which prints the cancellation message and shows the menu again. (`BACK` is not an exception: the question simply returns `null` and the menu goes back one step.)

Because parcels are only created and saved, and services and statuses only changed, at the very last step of an operation, an operation interrupted by either exception never leaves partial data.

Only these specific exceptions are caught. Programming errors (bugs) are not hidden by broad `catch` blocks.

### 3. Singleton: `ParcelManager`
- One instance, created eagerly: `private static final ParcelManager INSTANCE = new ParcelManager();`
- Private constructor, which also refuses to run a second time (so even reflection cannot create a second instance).
- `ParcelManager.getInstance()` is the only access point. `ConsoleMenu` gets it once in its constructor, so every menu operation uses the same object.
- The internal `ArrayList` is private. `getAllParcels()` returns a read-only copy, so the only way in is `addParcel()`, which enforces uniqueness.

### 4. Strategy: delivery fees
- Interface `DeliveryFeeStrategy` with `calculateFee(BigDecimal baseFee)` and `checkEligibility(BigDecimal chargeableWeight)` (default: no restriction).
- Concrete strategies: `StandardDeliveryStrategy`, `ExpressDeliveryStrategy`, `SameDayDeliveryStrategy`. Only Same Day overrides `checkEligibility`.
- `DeliveryStrategyResolver` maps each `DeliveryService` enum value to its strategy object.
- Each `Parcel` stores its current strategy. `getDeliveryFee()` is simply `strategy.calculateFee(getBaseFee())`, so the fee is always recalculated from the base fee; switching EXPRESS -> STANDARD -> EXPRESS never compounds.
- When the service changes, the new strategy is resolved and checked **before** anything is assigned. If it is rejected, the old service and strategy stay exactly as they were.

### Money and rounding policy
All peso amounts are `BigDecimal`. Every fee has exactly 2 decimal places, and when a calculation produces more (e.g. Express on Php 0.01 = 0.015) it is rounded once at the end with `HALF_UP` (0.02). This lives in one place: `Money`.

Weights are also `BigDecimal`, so limits like 0.5 kg, 30 kg, and 50 kg are compared exactly with no floating-point surprises. Volumetric weight is reported as a `double` (`volume / 3500.0`, floating-point division), and the chargeable weight is computed with exact arithmetic. Because `ceil(max(a, b)) = max(ceil(a), ceil(b))`, the result is identical to `ceil(max(actual, volume / 3500.0))`; a test checks this over 810 combinations.

---

## Class structure

All Java files are in one folder, `src/`, with no packages.

```text
polymorphism-exception-patterns-exercise/
|-- src/
|   |-- Main.java                         entry point
|   |
|   |-- Parcel.java                       abstract sealed superclass + shared validation
|   |-- Document.java                     Parcel -> Document
|   |-- Box.java                          Parcel -> Box
|   |-- Fragile.java                      Box -> Fragile
|   |-- DeliveryService.java              enum STANDARD, EXPRESS, SAME_DAY
|   |-- ParcelStatus.java                 enum PENDING, DELIVERED
|   |-- Money.java                        peso rounding/formatting policy
|   |
|   |-- DeliveryFeeStrategy.java          Strategy interface
|   |-- StandardDeliveryStrategy.java
|   |-- ExpressDeliveryStrategy.java
|   |-- SameDayDeliveryStrategy.java
|   |-- DeliveryStrategyResolver.java     maps each service to its strategy
|   |
|   |-- ParcelManager.java                Singleton
|   |
|   |-- ConsoleMenu.java                  menu and workflows
|   |-- InputValidator.java               reusable reading/parsing helpers
|   |
|   |-- ParcelSystemException.java        base of the checked exceptions
|   |-- InvalidInputException.java
|   |-- InvalidParcelException.java
|   |-- DuplicateTrackingNumberException.java
|   |-- ParcelNotFoundException.java
|   |-- InvalidDeliveryServiceException.java
|   |-- ParcelAlreadyDeliveredException.java
|   |-- OperationCancelledException.java  user typed CANCEL
|   |-- EndOfInputException.java
|   |
|   `-- ParcelDeliverySystemTest.java     173 automated tests (plain Java, no JUnit)
|-- README.md
`-- .gitignore
```

Every public class is in its own file named after the class, as Java requires. The blank lines above only group related files; they are all in the same folder.

---

## Requirements

- **JDK 17 or newer** (the code uses records, switch expressions, and sealed classes). Tested with OpenJDK 21.
- No external libraries, no Maven/Gradle needed.

Check your version:

```bash
java -version
javac -version
```

---

## Compile and run

Run these from the project folder (`polymorphism-exception-patterns-exercise`). The same commands work on Windows (PowerShell or Command Prompt), macOS, and Linux.

```bash
javac -d out src/*.java
java -cp out Main
```

The first command compiles every `.java` file in `src` and puts the `.class` files in a new `out` folder. The second runs the program.

### IntelliJ IDEA
Open the project folder, right-click `src` > **Mark Directory as > Sources Root**, then run `Main`. To run the tests, run `ParcelDeliverySystemTest`.

To end input at any prompt: **Ctrl+D** (Linux/macOS) or **Ctrl+Z then Enter** (Windows). The program exits cleanly without saving any unfinished parcel.

---

## Run the tests

The tests are plain Java (a small built-in runner), so nothing needs to be downloaded. They are compiled by the same `javac -d out src/*.java` command, so just run:

```bash
java -cp out ParcelDeliverySystemTest
```

Each test prints `PASS` or `FAIL`, followed by a summary. The process exits with code `1` if any test fails. Expected ending:

```text
==================================================
Tests run: 173, Passed: 173, Failed: 0
==================================================
```

What is covered: tracking numbers, names, weight/number parsing (including leading zeros), the inventory summary and [UNAVAILABLE] rules, BACK (one step), CANCEL and kept answers at every question, Document, Box, Fragile, strategies and rounding, Same Day eligibility, state transitions, the Singleton, encapsulation and design checks, and 17 full console workflows (scripted input, including EOF in the middle of operations, each with a timeout so an infinite loop would fail the test). Every test starts with an empty manager, so the order does not matter.

---

## Sample console output

A real session, recorded from a terminal. It starts with an empty inventory, where option 3 is refused. Next comes the required sample (Fragile, 40 x 20 x 10 cm, 1.8 kg, Express), using `BACK` at the service menu and Enter to keep the handling note. Then a Box refused for Same Day, where `0` goes back to the height question to make it smaller. Then a Document abandoned with `CANCEL`, then a delivery, the inventory view, and the already-delivered message. Your typed input appears after each prompt.

```text
========================================
       PARCEL DELIVERY SYSTEM
========================================
INVENTORY SUMMARY
Total Parcels: 0
Pending: 0
Delivered: 0
----------------------------------------
1. Add Parcel
2. View Inventory / All Parcels
3. Change Delivery Service [UNAVAILABLE]
4. Mark as Delivered [UNAVAILABLE]
5. Exit
========================================
Enter your choice: 3

No parcels in inventory. Please add a parcel first.

========================================
       PARCEL DELIVERY SYSTEM
========================================
INVENTORY SUMMARY
Total Parcels: 0
Pending: 0
Delivered: 0
...
Enter your choice: 1

--- Add Parcel ---
Type BACK to go to the previous step, or CANCEL to return to the main menu.
===== SELECT PARCEL TYPE =====
1. Document
2. Box
3. Fragile
0. Back
Enter your choice (BACK/CANCEL): 3
Enter tracking number, e.g. ABC0001 (BACK/CANCEL): ABC0001
Enter receiver name (BACK/CANCEL): Juan Dela Cruz
Enter actual weight in kg, more than 0 and max 30 (BACK/CANCEL): 1.8
Enter length in cm, whole number (BACK/CANCEL): 40
Enter width in cm, whole number (BACK/CANCEL): 20
Enter height in cm, whole number (BACK/CANCEL): 10
Enter handling note (BACK/CANCEL): Handle with care
===== DELIVERY SERVICE =====
1. STANDARD
2. EXPRESS
3. SAME DAY
0. Back
Enter your choice (BACK/CANCEL): BACK
Going back to the previous step...
Enter handling note [Handle with care] (Enter = keep, BACK/CANCEL): 
===== DELIVERY SERVICE =====
1. STANDARD
2. EXPRESS
3. SAME DAY
0. Back
Enter your choice (BACK/CANCEL): 2
Parcel added successfully! Delivery fee: Php 330.00

========================================
       PARCEL DELIVERY SYSTEM
========================================
INVENTORY SUMMARY
Total Parcels: 1
Pending: 1
Delivered: 0
...
Enter your choice: 1

--- Add Parcel ---
Type BACK to go to the previous step, or CANCEL to return to the main menu.
===== SELECT PARCEL TYPE =====
1. Document
2. Box
3. Fragile
0. Back
Enter your choice (BACK/CANCEL): 2
Enter tracking number, e.g. ABC0001 (BACK/CANCEL): ABC0002
Enter receiver name (BACK/CANCEL): Maria Santos
Enter actual weight in kg, more than 0 and max 50 (BACK/CANCEL): 2
Enter length in cm, whole number (BACK/CANCEL): 60
Enter width in cm, whole number (BACK/CANCEL): 60
Enter height in cm, whole number (BACK/CANCEL): 10
===== DELIVERY SERVICE =====
1. STANDARD
2. EXPRESS
3. SAME DAY
0. Back
Enter your choice (BACK/CANCEL): 3
Same Day delivery is only for parcels up to 10 kg!
===== DELIVERY SERVICE =====
1. STANDARD
2. EXPRESS
3. SAME DAY
0. Back
Enter your choice (BACK/CANCEL): 0
Going back to the previous step...
Enter height in cm, whole number [10] (Enter = keep, BACK/CANCEL): 5
===== DELIVERY SERVICE =====
1. STANDARD
2. EXPRESS
3. SAME DAY
0. Back
Enter your choice (BACK/CANCEL): 3
Parcel added successfully! Delivery fee: Php 580.00

========================================
       PARCEL DELIVERY SYSTEM
========================================
INVENTORY SUMMARY
Total Parcels: 2
Pending: 2
Delivered: 0
...
Enter your choice: 1

--- Add Parcel ---
Type BACK to go to the previous step, or CANCEL to return to the main menu.
===== SELECT PARCEL TYPE =====
1. Document
2. Box
3. Fragile
0. Back
Enter your choice (BACK/CANCEL): 1
Enter tracking number, e.g. ABC0001 (BACK/CANCEL): ABC0003
Enter receiver name (BACK/CANCEL): Ana Reyes
Enter actual weight in kg, more than 0 and max 0.5 (BACK/CANCEL): CANCEL
Operation cancelled. Returning to main menu...

========================================
       PARCEL DELIVERY SYSTEM
========================================
INVENTORY SUMMARY
Total Parcels: 2
Pending: 2
Delivered: 0
...
Enter your choice: 4

--- Mark as Delivered ---
Type BACK to go to the previous step, or CANCEL to return to the main menu.
Enter tracking number (BACK/CANCEL): ABC0001
Parcel delivered successfully!

========================================
       PARCEL DELIVERY SYSTEM
========================================
INVENTORY SUMMARY
Total Parcels: 2
Pending: 1
Delivered: 1
...
Enter your choice: 2

===================== INVENTORY =====================
Tracking No. | Type    | Receiver       | Chargeable Wt | Service  | Delivery Fee | Status
---------------------------------------------------------------------------------------------
ABC0001      | Fragile | Juan Dela Cruz |          3 kg | EXPRESS  |   Php 330.00 | DELIVERED
ABC0002      | Box     | Maria Santos   |          6 kg | SAME DAY |   Php 580.00 | PENDING
-----------------------------------------------------
Status Summary:
Total Parcels: 2
Pending: 1
Delivered: 1
=====================================================

========================================
       PARCEL DELIVERY SYSTEM
========================================
INVENTORY SUMMARY
Total Parcels: 2
Pending: 1
Delivered: 1
...
Enter your choice: 3

--- Change Delivery Service ---
Type BACK to go to the previous step, or CANCEL to return to the main menu.
Enter tracking number (BACK/CANCEL): ABC0001
Parcel already delivered!

========================================
       PARCEL DELIVERY SYSTEM
========================================
INVENTORY SUMMARY
Total Parcels: 2
Pending: 1
Delivered: 1
...
Enter your choice: 5
Thank you for using the Parcel Delivery System. Goodbye!
```

(`...` marks where the repeated menu options were shortened; the inventory summary is kept so you can see it update.)

Box ABC0002: at 60 x 60 x 10 cm the volumetric weight is 10.29 kg, so the chargeable weight is 11 kg even though it only weighs 2 kg, and Same Day is refused. After going back and changing the height to 5 cm, the volumetric weight is 5.14 kg, the chargeable weight 6 kg, and Same Day costs (6 x 40.00) x 2 + 100.00 = Php 580.00.

---

## Known limitations

- Data is kept in memory only; it is lost when the program exits (no database or file storage was requested).
- Box dimensions have no realistic size cap. Any positive whole number is accepted as long as L x W x H fits in a `long`; larger combinations are rejected.
- Weights are limited to 3 decimal places (1 gram). Finer values such as `0.0001` are rejected with a clear message.
- Receiver names are only checked for being non-blank, at most 100 characters, and free of control characters such as tabs. Names are deliberately not restricted to letters.
- The table aligns columns by character count. Full-width characters (for example Chinese names) take two screen columns, so such rows may look slightly shifted.
- Parcels cannot be edited or deleted; only the delivery service (while pending) and the status can change, as required.
- Weight input uses plain decimal notation with a dot (`1.8`). Commas (`1,8`), exponents (`1e3`), and units (`1.8kg`) are rejected. The leading-zero rule applies to whole numbers only, so a weight typed as `01.5` is still read as 1.5 kg.
- The words `BACK` and `CANCEL` (on their own) are navigation commands, so a receiver name or handling note cannot be exactly one of those words. Longer text containing them, such as `Backer`, is fine.

---

## Upload to GitHub

1. Create an **empty** repository named `polymorphism-exception-patterns-exercise` on GitHub (no README, no .gitignore, so it does not conflict).
2. In the project folder, run (replace `YOUR-USERNAME`):

```bash
git init
git add .
git commit -m "Parcel Delivery System: polymorphism, exceptions, Singleton and Strategy"
git branch -M main
git remote add origin https://github.com/YOUR-USERNAME/polymorphism-exception-patterns-exercise.git
git push -u origin main
```

If you use the GitHub CLI, steps 1 and 2 can be combined:

```bash
git init && git add . && git commit -m "Parcel Delivery System: polymorphism, exceptions, Singleton and Strategy"
gh repo create polymorphism-exception-patterns-exercise --public --source=. --push
```

The `.gitignore` keeps the compiled `out/` folder and IDE files out of the repository.
