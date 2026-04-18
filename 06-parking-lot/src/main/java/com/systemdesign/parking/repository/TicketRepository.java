package com.systemdesign.parking.repository;

import com.systemdesign.parking.model.ticket.ParkingTicket;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for parking ticket persistence.
 *
 * Demonstrates:
 * - Repository pattern: abstracts storage mechanism
 * - Can be swapped for database-backed implementation without changing service layer
 */
public interface TicketRepository {

    void save(ParkingTicket ticket);

    Optional<ParkingTicket> findById(String ticketId);

    List<ParkingTicket> findActiveTickets();
}
