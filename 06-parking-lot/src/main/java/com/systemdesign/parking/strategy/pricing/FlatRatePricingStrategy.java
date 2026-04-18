package com.systemdesign.parking.strategy.pricing;

import com.systemdesign.parking.model.VehicleType;
import com.systemdesign.parking.model.ticket.ParkingTicket;

import java.util.EnumMap;
import java.util.Map;

/**
 * Flat rate pricing: fixed daily rate regardless of duration.
 *
 * Rates:
 *   Motorcycle: $10/day
 *   Car:        $20/day
 *   Bus:        $50/day
 */
public class FlatRatePricingStrategy implements PricingStrategy {

    private final Map<VehicleType, Double> rates;

    public FlatRatePricingStrategy() {
        rates = new EnumMap<>(VehicleType.class);
        rates.put(VehicleType.MOTORCYCLE, 10.0);
        rates.put(VehicleType.CAR, 20.0);
        rates.put(VehicleType.BUS, 50.0);
    }

    @Override
    public double calculateFee(ParkingTicket ticket) {
        VehicleType type = ticket.getVehicle().getVehicleType();
        double rate = rates.getOrDefault(type, 20.0);
        System.out.printf("  [PRICING] Flat Rate: $%.2f/day (%s)%n", rate, type.getDisplayName());
        return rate;
    }

    @Override
    public String name() {
        return "Flat Rate";
    }
}
