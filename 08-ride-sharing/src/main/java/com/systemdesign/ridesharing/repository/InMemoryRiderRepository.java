package com.systemdesign.ridesharing.repository;

import com.systemdesign.ridesharing.model.Rider;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemoryRiderRepository — ConcurrentHashMap-backed rider storage.
 */
public class InMemoryRiderRepository implements RiderRepository {

    private final ConcurrentHashMap<String, Rider> riders = new ConcurrentHashMap<>();

    @Override
    public void save(Rider rider) {
        riders.put(rider.getId(), rider);
    }

    @Override
    public Optional<Rider> findById(String riderId) {
        return Optional.ofNullable(riders.get(riderId));
    }

    @Override
    public List<Rider> findAll() {
        return List.copyOf(riders.values());
    }

    @Override
    public void delete(String riderId) {
        riders.remove(riderId);
    }
}
