package com.systemdesign.parking.repository;

import com.systemdesign.parking.model.ticket.ParkingTicket;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of TicketRepository using ConcurrentHashMap.
 *
 * Thread-safe for concurrent reads/writes from multiple entry/exit gates.
 * In production, this would be replaced with a database-backed implementation.
 */
public class InMemoryTicketRepository implements TicketRepository {

    private final ConcurrentHashMap<String, ParkingTicket> tickets = new ConcurrentHashMap<>();

    @Override
    public void save(ParkingTicket ticket) {
        tickets.put(ticket.getTicketId(), ticket);
    }

    @Override
    public Optional<ParkingTicket> findById(String ticketId) {
        return Optional.ofNullable(tickets.get(ticketId));
    }

    @Override
    public List<ParkingTicket> findActiveTickets() {
        return tickets.values().stream()
                .filter(ticket -> !ticket.isPaid())
                .toList();
    }
}
