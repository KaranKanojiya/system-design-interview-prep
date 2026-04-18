package com.systemdesign.parking.config;

import com.systemdesign.parking.controller.ParkingController;
import com.systemdesign.parking.display.DisplayBoard;
import com.systemdesign.parking.model.ParkingFloor;
import com.systemdesign.parking.model.ParkingLot;
import com.systemdesign.parking.model.PaymentMethod;
import com.systemdesign.parking.model.spot.*;
import com.systemdesign.parking.payment.CashPaymentProcessor;
import com.systemdesign.parking.payment.CreditCardPaymentProcessor;
import com.systemdesign.parking.payment.PaymentProcessor;
import com.systemdesign.parking.repository.InMemoryTicketRepository;
import com.systemdesign.parking.repository.TicketRepository;
import com.systemdesign.parking.service.ParkingService;
import com.systemdesign.parking.strategy.parking.NearestFirstStrategy;
import com.systemdesign.parking.strategy.parking.ParkingStrategy;
import com.systemdesign.parking.strategy.pricing.HourlyPricingStrategy;
import com.systemdesign.parking.strategy.pricing.PricingStrategy;

import java.util.EnumMap;
import java.util.Map;

/**
 * Application configuration: wires all components together.
 *
 * Demonstrates:
 * - Composition Root: single place where all dependencies are assembled
 * - Manual Dependency Injection (no framework)
 * - Factory methods for creating configured components
 *
 * In a Spring app, this would be @Configuration with @Bean methods.
 * Here we achieve the same DI manually to demonstrate understanding.
 */
public class AppConfig {

    /**
     * Create and configure the Singleton ParkingLot with 3 floors.
     *
     * Floor 1: 50 motorcycle, 80 compact, 10 large, 5 handicapped = 145 spots
     * Floor 2: 30 motorcycle, 100 compact, 15 large, 5 handicapped = 150 spots
     * Floor 3: 20 motorcycle, 60 compact, 20 large, 0 handicapped = 100 spots
     * Total: 395 spots
     */
    public ParkingLot setupParkingLot() {
        ParkingLot.resetInstance(); // Reset for clean demo runs
        ParkingLot lot = ParkingLot.createInstance("City Center Parking", "123 Main Street");

        // Floor 1: 145 spots
        ParkingFloor floor1 = new ParkingFloor(1);
        addMotorcycleSpots(floor1, 1, 50);
        addCompactSpots(floor1, 1, 80);
        addLargeSpots(floor1, 1, 10);
        addHandicappedSpots(floor1, 1, 5);
        lot.addFloor(floor1);

        // Floor 2: 150 spots
        ParkingFloor floor2 = new ParkingFloor(2);
        addMotorcycleSpots(floor2, 2, 30);
        addCompactSpots(floor2, 2, 100);
        addLargeSpots(floor2, 2, 15);
        addHandicappedSpots(floor2, 2, 5);
        lot.addFloor(floor2);

        // Floor 3: 100 spots
        ParkingFloor floor3 = new ParkingFloor(3);
        addMotorcycleSpots(floor3, 3, 20);
        addCompactSpots(floor3, 3, 60);
        addLargeSpots(floor3, 3, 20);
        lot.addFloor(floor3);

        System.out.printf("[CONFIG] Parking lot created: %s | %d floors | %d total spots%n",
                lot.getName(), lot.getFloors().size(), lot.getTotalCapacity());

        return lot;
    }

    /**
     * Create the ParkingService with all dependencies wired.
     */
    public ParkingService createParkingService(ParkingLot lot) {
        ParkingStrategy parkingStrategy = new NearestFirstStrategy();
        PricingStrategy pricingStrategy = new HourlyPricingStrategy();

        Map<PaymentMethod, PaymentProcessor> processors = new EnumMap<>(PaymentMethod.class);
        processors.put(PaymentMethod.CASH, new CashPaymentProcessor());
        processors.put(PaymentMethod.CREDIT_CARD, new CreditCardPaymentProcessor());

        TicketRepository ticketRepo = new InMemoryTicketRepository();
        DisplayBoard displayBoard = new DisplayBoard(lot);

        System.out.printf("[CONFIG] ParkingService created: Strategy=%s, Pricing=%s%n",
                parkingStrategy.name(), pricingStrategy.name());

        return new ParkingService(lot, parkingStrategy, pricingStrategy,
                processors, ticketRepo, displayBoard);
    }

    /**
     * Create the controller layer.
     */
    public ParkingController createController(ParkingService service) {
        System.out.println("[CONFIG] ParkingController created");
        return new ParkingController(service);
    }

    // --- Spot factory methods ---

    private void addMotorcycleSpots(ParkingFloor floor, int floorNum, int count) {
        for (int i = 1; i <= count; i++) {
            String id = String.format("F%d-M%03d", floorNum, i);
            floor.addSpot(new MotorcycleSpot(id, floorNum, i));
        }
    }

    private void addCompactSpots(ParkingFloor floor, int floorNum, int count) {
        for (int i = 1; i <= count; i++) {
            String id = String.format("F%d-C%03d", floorNum, i);
            floor.addSpot(new CompactSpot(id, floorNum, i));
        }
    }

    private void addLargeSpots(ParkingFloor floor, int floorNum, int count) {
        for (int i = 1; i <= count; i++) {
            String id = String.format("F%d-L%03d", floorNum, i);
            floor.addSpot(new LargeSpot(id, floorNum, i));
        }
    }

    private void addHandicappedSpots(ParkingFloor floor, int floorNum, int count) {
        for (int i = 1; i <= count; i++) {
            String id = String.format("F%d-H%03d", floorNum, i);
            floor.addSpot(new HandicappedSpot(id, floorNum, i));
        }
    }
}
