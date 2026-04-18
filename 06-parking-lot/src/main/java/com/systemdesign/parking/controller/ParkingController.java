package com.systemdesign.parking.controller;

import com.systemdesign.parking.exception.ParkingException;
import com.systemdesign.parking.model.Payment;
import com.systemdesign.parking.model.PaymentMethod;
import com.systemdesign.parking.model.VehicleType;
import com.systemdesign.parking.model.ticket.ParkingTicket;
import com.systemdesign.parking.model.vehicle.*;
import com.systemdesign.parking.service.ParkingService;

/**
 * Simulated REST controller. Entry point for client requests.
 *
 * Demonstrates:
 * - Controller pattern: translates external requests to service calls
 * - Factory Method: creates correct Vehicle subclass from VehicleType enum
 * - Error handling: catches domain exceptions and reports user-friendly messages
 */
public class ParkingController {

    private final ParkingService parkingService;

    public ParkingController(ParkingService parkingService) {
        this.parkingService = parkingService;
    }

    /**
     * Handle a vehicle entry request.
     * Uses Factory Method to create the correct Vehicle subclass based on type.
     */
    public ParkingTicket handleEntry(VehicleType type, String licensePlate) {
        System.out.printf("  [CONTROLLER] Entry request: %s %s%n", type.getDisplayName(), licensePlate);

        // Factory Method: create the correct vehicle subclass
        Vehicle vehicle = createVehicle(type, licensePlate);
        System.out.printf("  [CONTROLLER] Created vehicle: %s (class: %s)%n",
                vehicle, vehicle.getClass().getSimpleName());

        try {
            ParkingTicket ticket = parkingService.parkVehicle(vehicle);
            System.out.printf("  [CONTROLLER] Ticket issued: %s%n", ticket.getTicketId());
            return ticket;
        } catch (ParkingException e) {
            System.out.printf("  [CONTROLLER] Entry FAILED: %s%n", e.getMessage());
            throw e;
        }
    }

    /**
     * Handle a vehicle exit request.
     */
    public Payment handleExit(String ticketId, PaymentMethod method) {
        System.out.printf("  [CONTROLLER] Exit request: Ticket %s, Payment: %s%n",
                ticketId, method.getDisplayName());

        try {
            Payment payment = parkingService.unparkVehicle(ticketId, method);
            System.out.printf("  [CONTROLLER] Payment processed: %s%n", payment);
            return payment;
        } catch (ParkingException e) {
            System.out.printf("  [CONTROLLER] Exit FAILED: %s%n", e.getMessage());
            throw e;
        }
    }

    /**
     * Display the availability board.
     */
    public void handleAvailability() {
        parkingService.showAvailability();
    }

    /**
     * Factory Method: creates the correct Vehicle subclass.
     * Encapsulates the mapping from VehicleType enum to concrete class.
     */
    private Vehicle createVehicle(VehicleType type, String licensePlate) {
        return switch (type) {
            case CAR -> new Car(licensePlate);
            case MOTORCYCLE -> new Motorcycle(licensePlate);
            case BUS -> new Bus(licensePlate);
        };
    }
}
