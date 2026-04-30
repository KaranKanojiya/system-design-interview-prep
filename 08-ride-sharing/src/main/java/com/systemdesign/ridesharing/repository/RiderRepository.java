package com.systemdesign.ridesharing.repository;

import com.systemdesign.ridesharing.model.Rider;

import java.util.List;
import java.util.Optional;

/**
 * RiderRepository — Data access interface for Rider objects.
 */
public interface RiderRepository {

    void save(Rider rider);

    Optional<Rider> findById(String riderId);

    List<Rider> findAll();

    void delete(String riderId);
}
