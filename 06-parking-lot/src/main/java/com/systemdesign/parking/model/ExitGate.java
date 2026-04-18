package com.systemdesign.parking.model;

import com.systemdesign.parking.model.ticket.ParkingTicket;

/**
 * Represents a physical exit gate in the parking facility.
 * Logs vehicle exit events with fee information.
 */
public class ExitGate {

    private final int gateNumber;

    public ExitGate(int gateNumber) {
        this.gateNumber = gateNumber;
    }

    public void processExit(ParkingTicket ticket) {
        System.out.printf("[EXIT Gate %d] Vehicle %s exiting | Fee: $%.2f%n",
                gateNumber, ticket.getVehicle().getLicensePlate(), ticket.getAmount());
    }

    public int getGateNumber() {
        return gateNumber;
    }

    @Override
    public String toString() {
        return "ExitGate #" + gateNumber;
    }
}
