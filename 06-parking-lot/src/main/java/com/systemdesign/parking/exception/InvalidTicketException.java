package com.systemdesign.parking.exception;

/**
 * Thrown when a ticket ID cannot be found in the system.
 */
public class InvalidTicketException extends ParkingException {

    public InvalidTicketException(String ticketId) {
        super("Ticket not found: " + ticketId);
    }
}
