package com.systemdesign.parking.model;

import com.systemdesign.parking.model.vehicle.Vehicle;

/**
 * Represents a physical entry gate in the parking facility.
 * Logs vehicle entry events.
 */
public class EntryGate {

    private final int gateNumber;

    public EntryGate(int gateNumber) {
        this.gateNumber = gateNumber;
    }

    public void processEntry(Vehicle vehicle) {
        System.out.printf("[ENTRY Gate %d] Vehicle %s entering%n",
                gateNumber, vehicle.getLicensePlate());
    }

    public int getGateNumber() {
        return gateNumber;
    }

    @Override
    public String toString() {
        return "EntryGate #" + gateNumber;
    }
}
