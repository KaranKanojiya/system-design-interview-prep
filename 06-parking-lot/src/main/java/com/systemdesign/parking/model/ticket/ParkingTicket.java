package com.systemdesign.parking.model.ticket;

import com.systemdesign.parking.model.spot.ParkingSpot;
import com.systemdesign.parking.model.vehicle.Vehicle;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable-ish parking ticket issued when a vehicle enters.
 *
 * Demonstrates:
 * - Builder pattern for flexible construction with many optional fields
 * - Value object semantics (equality by ticketId)
 */
public class ParkingTicket {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final String ticketId;
    private final Vehicle vehicle;
    private final ParkingSpot spot;
    private final LocalDateTime entryTime;
    private LocalDateTime exitTime;
    private double amount;
    private boolean isPaid;

    private ParkingTicket(Builder builder) {
        this.ticketId = builder.ticketId;
        this.vehicle = builder.vehicle;
        this.spot = builder.spot;
        this.entryTime = builder.entryTime;
        this.exitTime = builder.exitTime;
        this.amount = builder.amount;
        this.isPaid = builder.isPaid;
    }

    // --- Duration & Fee Calculation ---

    public Duration calculateDuration() {
        LocalDateTime end = (exitTime != null) ? exitTime : LocalDateTime.now();
        return Duration.between(entryTime, end);
    }

    public long calculateHours() {
        Duration duration = calculateDuration();
        long hours = duration.toHours();
        if (duration.toMinutesPart() > 0 || duration.toSecondsPart() > 0) {
            hours++; // ceiling
        }
        return Math.max(1, hours); // minimum 1 hour
    }

    public void markAsPaid(double amount) {
        this.exitTime = LocalDateTime.now();
        this.amount = amount;
        this.isPaid = true;
    }

    public void setExitTime(LocalDateTime exitTime) {
        this.exitTime = exitTime;
    }

    // --- Getters ---

    public String getTicketId() {
        return ticketId;
    }

    public Vehicle getVehicle() {
        return vehicle;
    }

    public ParkingSpot getSpot() {
        return spot;
    }

    public LocalDateTime getEntryTime() {
        return entryTime;
    }

    public LocalDateTime getExitTime() {
        return exitTime;
    }

    public double getAmount() {
        return amount;
    }

    public boolean isPaid() {
        return isPaid;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ParkingTicket that = (ParkingTicket) o;
        return ticketId.equals(that.ticketId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ticketId);
    }

    @Override
    public String toString() {
        String durationStr = String.format("%.1f", calculateDuration().toMinutes() / 60.0);
        String feeStr = isPaid ? String.format("$%.2f", amount) : "UNPAID";
        return String.format("Ticket#%s | %s | %s | Entry: %s | Duration: %sh | Fee: %s",
                ticketId, vehicle, spot.getSpotId(),
                entryTime.format(TIME_FMT), durationStr, feeStr);
    }

    // --- Builder Pattern ---

    public static class Builder {
        private String ticketId = "TKT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        private Vehicle vehicle;
        private ParkingSpot spot;
        private LocalDateTime entryTime = LocalDateTime.now();
        private LocalDateTime exitTime;
        private double amount;
        private boolean isPaid;

        public Builder vehicle(Vehicle vehicle) {
            this.vehicle = Objects.requireNonNull(vehicle);
            return this;
        }

        public Builder spot(ParkingSpot spot) {
            this.spot = Objects.requireNonNull(spot);
            return this;
        }

        public Builder entryTime(LocalDateTime entryTime) {
            this.entryTime = entryTime;
            return this;
        }

        public Builder exitTime(LocalDateTime exitTime) {
            this.exitTime = exitTime;
            return this;
        }

        public Builder ticketId(String ticketId) {
            this.ticketId = ticketId;
            return this;
        }

        public Builder amount(double amount) {
            this.amount = amount;
            return this;
        }

        public Builder isPaid(boolean isPaid) {
            this.isPaid = isPaid;
            return this;
        }

        public ParkingTicket build() {
            Objects.requireNonNull(vehicle, "Vehicle is required");
            Objects.requireNonNull(spot, "Spot is required");
            return new ParkingTicket(this);
        }
    }
}
