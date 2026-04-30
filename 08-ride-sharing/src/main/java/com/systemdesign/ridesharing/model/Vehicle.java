package com.systemdesign.ridesharing.model;

/**
 * Vehicle — Represents a driver's vehicle with type and capacity.
 *
 * WHY separate from Driver:
 *   A driver can change vehicles (new car, rental). The vehicle type
 *   directly affects pricing (LUXURY costs more) and capacity (SUV = 6 passengers).
 *   Keeping it separate follows Single Responsibility.
 */
public class Vehicle {

    /**
     * VehicleType — Each type has a max passenger capacity.
     *
     * UGLY approach:
     *   if (type.equals("sedan")) capacity = 4;
     *   else if (type.equals("suv")) capacity = 6;
     *   else if (type.equals("pool")) capacity = 4;
     *   // Fragile — typos, missing cases, no compile-time safety
     *
     * CLEAN approach:
     *   Enum with capacity baked in. Can't be wrong.
     */
    public enum VehicleType {
        SEDAN(4),
        SUV(6),
        POOL(4),
        LUXURY(4),
        AUTO_RICKSHAW(3);

        private final int capacity;

        VehicleType(int capacity) {
            this.capacity = capacity;
        }

        public int getCapacity() {
            return capacity;
        }
    }

    private final VehicleType type;
    private final String licensePlate;
    private final int capacity;  // can override enum default for custom configs

    public Vehicle(VehicleType type, String licensePlate) {
        this.type = type;
        this.licensePlate = licensePlate;
        this.capacity = type.getCapacity(); // default from enum
    }

    public Vehicle(VehicleType type, String licensePlate, int capacity) {
        this.type = type;
        this.licensePlate = licensePlate;
        this.capacity = capacity;
    }

    public VehicleType getType() {
        return type;
    }

    public String getLicensePlate() {
        return licensePlate;
    }

    public int getCapacity() {
        return capacity;
    }

    @Override
    public String toString() {
        return String.format("%s [%s] (seats: %d)", type, licensePlate, capacity);
    }
}
