package com.systemdesign.parking;

import com.systemdesign.parking.config.AppConfig;
import com.systemdesign.parking.exception.ParkingException;
import com.systemdesign.parking.exception.PaymentFailedException;
import com.systemdesign.parking.model.*;
import com.systemdesign.parking.model.spot.*;
import com.systemdesign.parking.model.ticket.ParkingTicket;
import com.systemdesign.parking.model.vehicle.*;
import com.systemdesign.parking.payment.CreditCardPaymentProcessor;
import com.systemdesign.parking.service.ParkingService;
import com.systemdesign.parking.strategy.parking.CompactFloorStrategy;
import com.systemdesign.parking.strategy.parking.NearestFirstStrategy;
import com.systemdesign.parking.strategy.pricing.FlatRatePricingStrategy;
import com.systemdesign.parking.strategy.pricing.HourlyPricingStrategy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Parking Lot System - Low-Level Design Interview Demo
 *
 * Demonstrates:
 * 1. Inheritance hierarchies (Vehicle, ParkingSpot)
 * 2. Strategy pattern (ParkingStrategy, PricingStrategy, PaymentProcessor)
 * 3. Singleton pattern (ParkingLot)
 * 4. Builder pattern (ParkingTicket)
 * 5. Facade pattern (ParkingService)
 * 6. Factory Method (ParkingController.createVehicle)
 * 7. Repository pattern (TicketRepository)
 * 8. SOLID principles throughout
 * 9. Thread-safe operations (synchronized park/vacate, ConcurrentHashMap)
 */
public class ParkingLotApp {

    private static final String SEPARATOR = "=".repeat(70);
    private static final String THIN_SEP = "-".repeat(70);

    public static void main(String[] args) {
        System.out.println(SEPARATOR);
        System.out.println("   PARKING LOT SYSTEM -- System Design Interview Demo");
        System.out.println("   Java 21 | Plain Java | No Frameworks");
        System.out.println(SEPARATOR);
        System.out.println();

        // --- Setup ---
        AppConfig config = new AppConfig();
        ParkingLot lot = config.setupParkingLot();
        ParkingService service = config.createParkingService(lot);
        System.out.println();

        // Demo 1: Park Vehicles
        demo1_parkVehicles(service);

        // Demo 2: Display Board
        demo2_displayBoard(service);

        // Demo 3: Exit & Payment
        demo3_exitAndPayment(service);

        // Demo 4: Different Pricing Strategies
        demo4_pricingComparison(service);

        // Demo 5: Spot Type Compatibility
        demo5_spotCompatibility();

        // Demo 6: Parking Strategy Comparison
        demo6_strategyComparison(service);

        // Demo 7: Parking Full
        demo7_parkingFull(service);

        // Demo 8: Credit Card Failure & Retry
        demo8_creditCardFailure(service);

        // Design Summary
        printDesignSummary();
    }

    // ========================================================================
    // Demo 1: Park Vehicles
    // ========================================================================
    private static void demo1_parkVehicles(ParkingService service) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 1: Park Vehicles (Inheritance + Strategy)");
        System.out.println(SEPARATOR);
        System.out.println("  Parking 3 cars, 2 motorcycles, 1 bus...");
        System.out.println("  Each Vehicle subclass defines its VehicleType.");
        System.out.println("  Each ParkingSpot subclass defines canFitVehicle().");
        System.out.println(THIN_SEP);

        // 3 Cars (uses Car extends Vehicle)
        parkAndPrint(service, new Car("CAR-1001"));
        parkAndPrint(service, new Car("CAR-1002"));
        parkAndPrint(service, new Car("CAR-1003"));

        // 2 Motorcycles (uses Motorcycle extends Vehicle)
        parkAndPrint(service, new Motorcycle("MOTO-2001"));
        parkAndPrint(service, new Motorcycle("MOTO-2002"));

        // 1 Bus (uses Bus extends Vehicle)
        parkAndPrint(service, new Bus("BUS-3001"));

        System.out.println(THIN_SEP);
        System.out.printf("  Available spots after parking: %d / %d%n",
                service.getLot().getTotalAvailable(), service.getLot().getTotalCapacity());
        System.out.println();
    }

    // ========================================================================
    // Demo 2: Display Board
    // ========================================================================
    private static void demo2_displayBoard(ParkingService service) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 2: Display Board (Observer-style rendering)");
        System.out.println(SEPARATOR);
        System.out.println("  Real-time availability by floor and spot type:");
        service.showAvailability();
    }

    // ========================================================================
    // Demo 3: Exit & Payment
    // ========================================================================
    private static void demo3_exitAndPayment(ParkingService service) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 3: Exit & Payment (Facade + Strategy + Builder)");
        System.out.println(SEPARATOR);

        // Park a car, then simulate 2-hour stay by building ticket with past entry time
        System.out.println("  Parking a car that will stay for 2 hours...");
        Car exitCar = new Car("EXIT-9999");
        ParkingTicket ticket = service.parkVehicle(exitCar);

        // Rebuild ticket with entry time 2 hours ago to simulate duration
        ParkingTicket timedTicket = new ParkingTicket.Builder()
                .ticketId(ticket.getTicketId())
                .vehicle(exitCar)
                .spot(ticket.getSpot())
                .entryTime(LocalDateTime.now().minusHours(2))
                .build();
        // Replace in repository
        service.getTicketRepo().save(timedTicket);

        System.out.println(THIN_SEP);
        System.out.println("  Ticket details: " + timedTicket);
        System.out.println("  Now exiting and paying with CASH...");
        System.out.println(THIN_SEP);

        Payment payment = service.unparkVehicle(timedTicket.getTicketId(), PaymentMethod.CASH);

        System.out.println(THIN_SEP);
        System.out.println("  Receipt: " + payment);
        System.out.println("  Spot freed. Updated availability:");
        System.out.printf("  Available: %d / %d%n",
                service.getLot().getTotalAvailable(), service.getLot().getTotalCapacity());
        System.out.println();
    }

    // ========================================================================
    // Demo 4: Pricing Strategy Comparison
    // ========================================================================
    private static void demo4_pricingComparison(ParkingService service) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 4: Pricing Strategy Comparison (Strategy Pattern)");
        System.out.println(SEPARATOR);
        System.out.println("  Same motorcycle, 3-hour stay. Two pricing strategies:");
        System.out.println(THIN_SEP);

        // Create a mock ticket for comparison
        Motorcycle moto = new Motorcycle("PRICE-TEST");
        ParkingTicket parkTicket = service.parkVehicle(moto);

        // Build a ticket with 3 hours ago entry
        ParkingTicket testTicket = new ParkingTicket.Builder()
                .ticketId("PRICE-COMPARE")
                .vehicle(moto)
                .spot(parkTicket.getSpot())
                .entryTime(LocalDateTime.now().minusHours(3))
                .build();

        System.out.println("  --- Hourly Pricing ---");
        HourlyPricingStrategy hourly = new HourlyPricingStrategy();
        double hourlyFee = hourly.calculateFee(testTicket);
        System.out.printf("  Result: $%.2f (3h x $1.00/hr)%n", hourlyFee);

        System.out.println();
        System.out.println("  --- Flat Rate Pricing ---");
        FlatRatePricingStrategy flatRate = new FlatRatePricingStrategy();
        double flatFee = flatRate.calculateFee(testTicket);
        System.out.printf("  Result: $%.2f (fixed daily rate)%n", flatFee);

        System.out.println(THIN_SEP);
        System.out.printf("  Hourly: $%.2f vs Flat Rate: $%.2f -- Hourly is cheaper for short stays!%n",
                hourlyFee, flatFee);

        // Clean up - vacate the spot
        parkTicket.getSpot().vacate();
        System.out.println();
    }

    // ========================================================================
    // Demo 5: Spot Type Compatibility
    // ========================================================================
    private static void demo5_spotCompatibility() {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 5: Spot Type Compatibility (Polymorphism + LSP)");
        System.out.println(SEPARATOR);
        System.out.println("  Testing canFitVehicle() across the ParkingSpot hierarchy:");
        System.out.println(THIN_SEP);

        // Create one of each spot type
        ParkingSpot[] spots = {
                new MotorcycleSpot("TEST-M", 1, 1),
                new CompactSpot("TEST-C", 1, 2),
                new LargeSpot("TEST-L", 1, 3),
                new HandicappedSpot("TEST-H", 1, 4)
        };

        // Create one of each vehicle type
        Vehicle[] vehicles = {
                new Motorcycle("MOTO"),
                new Car("CAR"),
                new Bus("BUS")
        };

        // Print compatibility matrix
        System.out.printf("  %-20s | %-12s | %-12s | %-12s%n",
                "Spot Type", "Motorcycle", "Car", "Bus");
        System.out.println("  " + "-".repeat(62));

        for (ParkingSpot spot : spots) {
            System.out.printf("  %-20s |", spot.getSpotType().getDisplayName()
                    + " (" + spot.getClass().getSimpleName() + ")");
            for (Vehicle v : vehicles) {
                String result = spot.canFitVehicle(v) ? " YES" : " NO ";
                System.out.printf(" %-12s |", result);
            }
            System.out.println();
        }

        System.out.println(THIN_SEP);
        System.out.println("  Key insight: Each ParkingSpot subclass overrides canFitVehicle().");
        System.out.println("  CompactSpot fits Motorcycle+Car. LargeSpot fits everything.");
        System.out.println("  This is polymorphism in action -- no if/else chains!");
        System.out.println();

        // Actually try parking a bus in compact vs large
        System.out.println("  Attempting to park Bus in CompactSpot...");
        CompactSpot compact = new CompactSpot("C-TEST", 1, 99);
        Bus bus = new Bus("BUS-TEST");
        boolean busInCompact = compact.park(bus);
        System.out.printf("  Result: %s (CompactSpot.canFitVehicle(Bus) = false)%n",
                busInCompact ? "SUCCESS" : "REJECTED");

        System.out.println("  Attempting to park Bus in LargeSpot...");
        LargeSpot large = new LargeSpot("L-TEST", 1, 99);
        boolean busInLarge = large.park(bus);
        System.out.printf("  Result: %s (LargeSpot.canFitVehicle(Bus) = true)%n",
                busInLarge ? "SUCCESS" : "REJECTED");

        System.out.println("  Attempting to park Motorcycle in CompactSpot...");
        Motorcycle moto = new Motorcycle("MOTO-TEST");
        CompactSpot compact2 = new CompactSpot("C-TEST2", 1, 98);
        boolean motoInCompact = compact2.park(moto);
        System.out.printf("  Result: %s (CompactSpot.canFitVehicle(Motorcycle) = true)%n",
                motoInCompact ? "SUCCESS" : "REJECTED");
        System.out.println();
    }

    // ========================================================================
    // Demo 6: Parking Strategy Comparison
    // ========================================================================
    private static void demo6_strategyComparison(ParkingService service) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 6: Parking Strategy Comparison (Strategy Pattern)");
        System.out.println(SEPARATOR);
        System.out.println("  Two strategies, same vehicle type -- different spot assignments.");
        System.out.println(THIN_SEP);

        // Strategy 1: Nearest First
        System.out.println("  --- NearestFirstStrategy ---");
        service.setParkingStrategy(new NearestFirstStrategy());
        Car car1 = new Car("STRAT-NF-001");
        ParkingTicket t1 = service.parkVehicle(car1);
        System.out.printf("  Assigned: %s on %s%n", t1.getSpot().getSpotId(), t1.getSpot().getSpotType());
        // Vacate for next test
        t1.getSpot().vacate();

        System.out.println();

        // Strategy 2: Compact Floor
        System.out.println("  --- CompactFloorStrategy ---");
        service.setParkingStrategy(new CompactFloorStrategy());
        Car car2 = new Car("STRAT-CF-001");
        ParkingTicket t2 = service.parkVehicle(car2);
        System.out.printf("  Assigned: %s on %s%n", t2.getSpot().getSpotId(), t2.getSpot().getSpotType());
        // Vacate
        t2.getSpot().vacate();

        // Reset to NearestFirst
        service.setParkingStrategy(new NearestFirstStrategy());

        System.out.println(THIN_SEP);
        System.out.println("  NearestFirst picks the closest spot to entrance (Floor 1).");
        System.out.println("  CompactFloor picks the most-full floor to consolidate vehicles.");
        System.out.println("  Strategy is swappable at RUNTIME -- no code changes needed!");
        System.out.println();
    }

    // ========================================================================
    // Demo 7: Parking Full
    // ========================================================================
    private static void demo7_parkingFull(ParkingService service) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 7: Parking Full (Exception Handling)");
        System.out.println(SEPARATOR);
        System.out.println("  Filling all motorcycle spots across all floors...");
        System.out.println(THIN_SEP);

        // Count total motorcycle spots: Floor1=50 + Floor2=30 + Floor3=20 = 100
        // Some may already be occupied from earlier demos
        List<ParkingTicket> motoTickets = new ArrayList<>();
        int parked = 0;
        int failed = 0;

        // Park motorcycles until we run out of motorcycle-only spots
        // Note: motorcycles can also go in compact/large, so we need to fill ALL compatible spots
        // For this demo, let's just fill a bunch and show the exception
        for (int i = 1; i <= 500; i++) {
            try {
                Motorcycle m = new Motorcycle("FILL-M" + String.format("%04d", i));
                ParkingTicket t = service.parkVehicle(m);
                motoTickets.add(t);
                parked++;
            } catch (ParkingException e) {
                System.out.println();
                System.out.printf("  [EXCEPTION CAUGHT] After parking %d motorcycles:%n", parked);
                System.out.printf("  Exception type: %s%n", e.getClass().getSimpleName());
                System.out.printf("  Message: %s%n", e.getMessage());
                failed++;
                break;
            }
        }

        System.out.println(THIN_SEP);
        System.out.printf("  Motorcycles parked: %d | Failed: %d%n", parked, failed);
        System.out.printf("  Lot available: %d / %d%n",
                service.getLot().getTotalAvailable(), service.getLot().getTotalCapacity());

        // Clean up: vacate all the motorcycles we parked
        System.out.println("  Cleaning up: vacating all test motorcycles...");
        for (ParkingTicket t : motoTickets) {
            t.getSpot().vacate();
        }
        System.out.printf("  After cleanup: %d / %d available%n",
                service.getLot().getTotalAvailable(), service.getLot().getTotalCapacity());
        System.out.println();
    }

    // ========================================================================
    // Demo 8: Credit Card Failure & Retry
    // ========================================================================
    private static void demo8_creditCardFailure(ParkingService service) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 8: Credit Card Failure & Retry (Payment Strategy)");
        System.out.println(SEPARATOR);
        System.out.println("  CreditCardPaymentProcessor has 95% success rate.");
        System.out.println("  Using seeded Random to force a failure, then retry.");
        System.out.println(THIN_SEP);

        // Park a car with 1-hour duration
        Car car = new Car("CC-TEST-001");
        ParkingTicket ticket = service.parkVehicle(car);

        // Rebuild with 1 hour ago
        ParkingTicket timedTicket = new ParkingTicket.Builder()
                .ticketId(ticket.getTicketId())
                .vehicle(car)
                .spot(ticket.getSpot())
                .entryTime(LocalDateTime.now().minusHours(1))
                .build();
        service.getTicketRepo().save(timedTicket);

        System.out.println("  Ticket: " + timedTicket.getTicketId());
        System.out.println();

        // Attempt payment with credit card (may fail)
        // Use a seeded random that will fail first, succeed second
        CreditCardPaymentProcessor forcedFailProcessor = new CreditCardPaymentProcessor(new Random(42));

        int attempts = 0;
        boolean paid = false;

        while (!paid && attempts < 5) {
            attempts++;
            System.out.printf("  Attempt %d: Paying with Credit Card...%n", attempts);

            try {
                Payment payment = service.unparkVehicle(timedTicket.getTicketId(), PaymentMethod.CREDIT_CARD);
                paid = true;
                System.out.printf("  Payment SUCCEEDED on attempt %d! %s%n", attempts, payment);
            } catch (PaymentFailedException e) {
                System.out.printf("  Payment FAILED on attempt %d: %s%n", attempts, e.getMessage());
                // Re-save the ticket since it might have been modified
                service.getTicketRepo().save(timedTicket);

                if (attempts < 5) {
                    System.out.println("  Retrying...");
                }
            }
        }

        if (!paid) {
            System.out.println("  All attempts failed. Falling back to CASH...");
            Payment cashPayment = service.unparkVehicle(timedTicket.getTicketId(), PaymentMethod.CASH);
            System.out.println("  Cash payment: " + cashPayment);
        }

        System.out.println();
    }

    // ========================================================================
    // Helper: Park and print
    // ========================================================================
    private static ParkingTicket parkAndPrint(ParkingService service, Vehicle vehicle) {
        ParkingTicket ticket = service.parkVehicle(vehicle);
        System.out.printf("    Vehicle: %-25s | Class: %-12s | Spot: %-10s | Spot Class: %s%n",
                vehicle,
                vehicle.getClass().getSimpleName(),
                ticket.getSpot().getSpotId(),
                ticket.getSpot().getClass().getSimpleName());
        return ticket;
    }

    // ========================================================================
    // Design Summary
    // ========================================================================
    private static void printDesignSummary() {
        System.out.println(SEPARATOR);
        System.out.println("   DESIGN PATTERNS & SOLID PRINCIPLES SUMMARY");
        System.out.println(SEPARATOR);
        System.out.println();
        System.out.println("  DESIGN PATTERNS USED:");
        System.out.println("  " + THIN_SEP);
        System.out.println("  1. Singleton        -- ParkingLot (double-checked locking)");
        System.out.println("  2. Strategy          -- ParkingStrategy (NearestFirst, CompactFloor)");
        System.out.println("                       -- PricingStrategy (Hourly, FlatRate)");
        System.out.println("                       -- PaymentProcessor (Cash, CreditCard)");
        System.out.println("  3. Builder           -- ParkingTicket.Builder (flexible construction)");
        System.out.println("  4. Factory Method    -- ParkingController.createVehicle()");
        System.out.println("  5. Facade            -- ParkingService (orchestrates all operations)");
        System.out.println("  6. Repository        -- TicketRepository (data access abstraction)");
        System.out.println("  7. Template Method   -- ParkingSpot.canFitVehicle() (abstract hook)");
        System.out.println();
        System.out.println("  SOLID PRINCIPLES:");
        System.out.println("  " + THIN_SEP);
        System.out.println("  S - Single Responsibility:");
        System.out.println("      Each class has one job: Vehicle knows its type, ParkingSpot knows");
        System.out.println("      what fits, PricingStrategy calculates fees, etc.");
        System.out.println();
        System.out.println("  O - Open/Closed:");
        System.out.println("      New vehicle types: extend Vehicle. New spot types: extend ParkingSpot.");
        System.out.println("      New strategies: implement ParkingStrategy/PricingStrategy.");
        System.out.println("      No existing code needs modification.");
        System.out.println();
        System.out.println("  L - Liskov Substitution:");
        System.out.println("      Any Vehicle subclass works wherever Vehicle is expected.");
        System.out.println("      Any ParkingSpot subclass works in ParkingFloor.getAvailableSpots().");
        System.out.println("      The system never checks concrete types with instanceof.");
        System.out.println();
        System.out.println("  I - Interface Segregation:");
        System.out.println("      ParkingStrategy, PricingStrategy, PaymentProcessor are small,");
        System.out.println("      focused interfaces. No class implements methods it doesn't need.");
        System.out.println();
        System.out.println("  D - Dependency Inversion:");
        System.out.println("      ParkingService depends on interfaces (ParkingStrategy, PricingStrategy,");
        System.out.println("      PaymentProcessor, TicketRepository), not concrete implementations.");
        System.out.println("      AppConfig (composition root) wires concrete types at startup.");
        System.out.println();
        System.out.println("  THREAD SAFETY:");
        System.out.println("  " + THIN_SEP);
        System.out.println("  - ParkingSpot.park() / vacate() are synchronized");
        System.out.println("  - ParkingLot uses volatile + double-checked locking Singleton");
        System.out.println("  - CopyOnWriteArrayList for floor spot lists");
        System.out.println("  - ConcurrentHashMap in InMemoryTicketRepository");
        System.out.println();
        System.out.println("  INHERITANCE HIERARCHIES:");
        System.out.println("  " + THIN_SEP);
        System.out.println("  Vehicle (abstract)");
        System.out.println("    +-- Car");
        System.out.println("    +-- Motorcycle");
        System.out.println("    +-- Bus");
        System.out.println();
        System.out.println("  ParkingSpot (abstract)");
        System.out.println("    +-- CompactSpot      (fits Car, Motorcycle)");
        System.out.println("    +-- LargeSpot        (fits everything)");
        System.out.println("    +-- MotorcycleSpot   (fits Motorcycle only)");
        System.out.println("    +-- HandicappedSpot  (fits Car only)");
        System.out.println();
        System.out.println("  ParkingException (RuntimeException)");
        System.out.println("    +-- ParkingFullException");
        System.out.println("    +-- SpotNotAvailableException");
        System.out.println("    +-- InvalidTicketException");
        System.out.println("    +-- PaymentFailedException");
        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("   END OF PARKING LOT SYSTEM DEMO");
        System.out.println(SEPARATOR);
    }
}
