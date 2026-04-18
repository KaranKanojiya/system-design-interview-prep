package com.systemdesign.parking.service;

import com.systemdesign.parking.display.DisplayBoard;
import com.systemdesign.parking.exception.*;
import com.systemdesign.parking.model.Payment;
import com.systemdesign.parking.model.PaymentMethod;
import com.systemdesign.parking.model.ParkingLot;
import com.systemdesign.parking.model.spot.ParkingSpot;
import com.systemdesign.parking.model.ticket.ParkingTicket;
import com.systemdesign.parking.model.vehicle.Vehicle;
import com.systemdesign.parking.payment.PaymentProcessor;
import com.systemdesign.parking.repository.TicketRepository;
import com.systemdesign.parking.strategy.parking.ParkingStrategy;
import com.systemdesign.parking.strategy.pricing.PricingStrategy;

import java.util.Map;
import java.util.Optional;

/**
 * Facade that orchestrates all parking operations.
 *
 * Demonstrates:
 * - Facade pattern: single entry point for park/unpark/display operations
 * - Strategy pattern: delegates to ParkingStrategy, PricingStrategy, PaymentProcessor
 * - Dependency Injection: all collaborators injected via constructor
 * - Single Responsibility: coordinates workflow but delegates actual logic
 *
 * This is the central service that an interview candidate should design.
 * It ties together all the SOLID-compliant components.
 */
public class ParkingService {

    private final ParkingLot lot;
    private ParkingStrategy parkingStrategy;
    private PricingStrategy pricingStrategy;
    private final Map<PaymentMethod, PaymentProcessor> paymentProcessors;
    private final TicketRepository ticketRepo;
    private final DisplayBoard displayBoard;

    public ParkingService(ParkingLot lot,
                          ParkingStrategy parkingStrategy,
                          PricingStrategy pricingStrategy,
                          Map<PaymentMethod, PaymentProcessor> paymentProcessors,
                          TicketRepository ticketRepo,
                          DisplayBoard displayBoard) {
        this.lot = lot;
        this.parkingStrategy = parkingStrategy;
        this.pricingStrategy = pricingStrategy;
        this.paymentProcessors = paymentProcessors;
        this.ticketRepo = ticketRepo;
        this.displayBoard = displayBoard;
    }

    /**
     * Park a vehicle in the lot.
     *
     * 1. Check lot capacity
     * 2. Find spot via ParkingStrategy (polymorphic)
     * 3. Park vehicle in spot (synchronized)
     * 4. Create and persist ticket (Builder pattern)
     */
    public ParkingTicket parkVehicle(Vehicle vehicle) {
        // 1. Check if the lot is full
        if (lot.isFull()) {
            throw new ParkingFullException(
                    "Parking lot is full. No spots available for " + vehicle.getVehicleType());
        }

        // 2. Find a spot using the configured strategy
        Optional<ParkingSpot> spotOpt = parkingStrategy.findSpot(lot, vehicle);
        if (spotOpt.isEmpty()) {
            throw new SpotNotAvailableException(
                    "No compatible spot found for " + vehicle.getVehicleType()
                    + " (" + vehicle.getLicensePlate() + ")");
        }

        ParkingSpot spot = spotOpt.get();

        // 3. Park the vehicle (synchronized in ParkingSpot)
        boolean parked = spot.park(vehicle);
        if (!parked) {
            throw new SpotNotAvailableException(
                    "Failed to park in spot " + spot.getSpotId() + " (race condition or incompatible)");
        }

        // 4. Create ticket using Builder pattern
        ParkingTicket ticket = new ParkingTicket.Builder()
                .vehicle(vehicle)
                .spot(spot)
                .entryTime(vehicle.getEntryTime())
                .build();

        // 5. Persist ticket
        ticketRepo.save(ticket);

        // 6. Log
        System.out.printf("  [PARK] %s -> %s (Spot type: %s)%n",
                vehicle, spot.getSpotId(), spot.getSpotType().getDisplayName());

        return ticket;
    }

    /**
     * Unpark a vehicle: calculate fee, process payment, vacate spot.
     */
    public Payment unparkVehicle(String ticketId, PaymentMethod method) {
        // 1. Find ticket
        ParkingTicket ticket = ticketRepo.findById(ticketId)
                .orElseThrow(() -> new InvalidTicketException(ticketId));

        // 2. Calculate fee
        double fee = pricingStrategy.calculateFee(ticket);

        // 3. Get appropriate payment processor
        PaymentProcessor processor = paymentProcessors.get(method);
        if (processor == null) {
            throw new PaymentFailedException("Unsupported payment method: " + method);
        }

        // 4. Process payment
        Payment payment = processor.processPayment(ticketId, fee, method);

        // 5. If successful, vacate spot and mark ticket paid
        if (payment.isSuccessful()) {
            ticket.getSpot().vacate();
            ticket.markAsPaid(fee);
            System.out.printf("  [UNPARK] %s | Duration: %dh | Fee: $%.2f | Paid: %s%n",
                    ticket.getVehicle(), ticket.calculateHours(), fee, method.getDisplayName());
        } else {
            throw new PaymentFailedException(
                    "Payment declined for ticket " + ticketId + " via " + method.getDisplayName());
        }

        return payment;
    }

    /**
     * Display the current availability board.
     */
    public void showAvailability() {
        displayBoard.show();
    }

    // --- Strategy hot-swap (demonstrates runtime flexibility) ---

    public void setParkingStrategy(ParkingStrategy strategy) {
        this.parkingStrategy = strategy;
    }

    public void setPricingStrategy(PricingStrategy strategy) {
        this.pricingStrategy = strategy;
    }

    public ParkingStrategy getParkingStrategy() {
        return parkingStrategy;
    }

    public PricingStrategy getPricingStrategy() {
        return pricingStrategy;
    }

    public TicketRepository getTicketRepo() {
        return ticketRepo;
    }

    public ParkingLot getLot() {
        return lot;
    }
}
