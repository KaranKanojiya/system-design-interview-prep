package com.systemdesign.parking.strategy.pricing;

import com.systemdesign.parking.model.VehicleType;
import com.systemdesign.parking.model.ticket.ParkingTicket;

import java.util.EnumMap;
import java.util.Map;

/**
 * Hourly pricing: fee = ceil(hours) x rate per vehicle type.
 * Minimum charge is 1 hour.
 *
 * Rates:
 *   Motorcycle: $1/hr
 *   Car:        $2/hr
 *   Bus:        $5/hr
 */
public class HourlyPricingStrategy implements PricingStrategy {

    private final Map<VehicleType, Double> rates;

    public HourlyPricingStrategy() {
        rates = new EnumMap<>(VehicleType.class);
        rates.put(VehicleType.MOTORCYCLE, 1.0);
        rates.put(VehicleType.CAR, 2.0);
        rates.put(VehicleType.BUS, 5.0);
    }

    @Override
    public double calculateFee(ParkingTicket ticket) {
        VehicleType type = ticket.getVehicle().getVehicleType();
        double rate = rates.getOrDefault(type, 2.0);
        long hours = ticket.calculateHours();
        double total = hours * rate;
        System.out.printf("  [PRICING] Hourly: %dh x $%.2f = $%.2f (%s)%n",
                hours, rate, total, type.getDisplayName());
        return total;
    }

    @Override
    public String name() {
        return "Hourly";
    }
}
