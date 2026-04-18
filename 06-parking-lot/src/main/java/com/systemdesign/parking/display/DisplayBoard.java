package com.systemdesign.parking.display;

import com.systemdesign.parking.model.ParkingFloor;
import com.systemdesign.parking.model.ParkingLot;
import com.systemdesign.parking.model.SpotType;

/**
 * Observer-style display board that renders real-time parking availability.
 * Queries the ParkingLot model and formats a table for console output.
 */
public class DisplayBoard {

    private final ParkingLot lot;

    public DisplayBoard(ParkingLot lot) {
        this.lot = lot;
    }

    public void show() {
        System.out.println();
        System.out.println("  +=========================================================+");
        System.out.printf("  |   %-53s |%n", lot.getName() + " - AVAILABILITY");
        System.out.println("  +=========+============+==========+=========+=============+");
        System.out.println("  | Floor   | Motorcycle | Compact  | Large   | Total Avail |");
        System.out.println("  +=========+============+==========+=========+=============+");

        long grandMotorcycle = 0, grandCompact = 0, grandLarge = 0, grandTotal = 0;
        long totalMotorcycle = 0, totalCompact = 0, totalLarge = 0;

        for (ParkingFloor floor : lot.getFloors()) {
            long mAvail = floor.getAvailableCountByType(SpotType.MOTORCYCLE_SPOT);
            long mTotal = floor.getTotalCountByType(SpotType.MOTORCYCLE_SPOT);
            long cAvail = floor.getAvailableCountByType(SpotType.COMPACT);
            long cTotal = floor.getTotalCountByType(SpotType.COMPACT);
            long lAvail = floor.getAvailableCountByType(SpotType.LARGE);
            long lTotal = floor.getTotalCountByType(SpotType.LARGE);
            long floorAvail = floor.getAvailableCount();

            grandMotorcycle += mAvail;
            grandCompact += cAvail;
            grandLarge += lAvail;
            grandTotal += floorAvail;
            totalMotorcycle += mTotal;
            totalCompact += cTotal;
            totalLarge += lTotal;

            System.out.printf("  |   %2d    |  %3d/%-4d  | %3d/%-4d | %2d/%-4d |    %4d     |%n",
                    floor.getFloorNumber(),
                    mAvail, mTotal,
                    cAvail, cTotal,
                    lAvail, lTotal,
                    floorAvail);
        }

        System.out.println("  +=========+============+==========+=========+=============+");
        System.out.printf("  | TOTAL   |  %3d/%-4d  | %3d/%-4d | %2d/%-4d |    %4d     |%n",
                grandMotorcycle, totalMotorcycle,
                grandCompact, totalCompact,
                grandLarge, totalLarge,
                grandTotal);
        System.out.println("  +=========+============+==========+=========+=============+");
        System.out.printf("  Total Capacity: %d | Total Available: %d | Occupancy: %.1f%%%n",
                lot.getTotalCapacity(), lot.getTotalAvailable(),
                (1.0 - (double) lot.getTotalAvailable() / lot.getTotalCapacity()) * 100);
        System.out.println();
    }
}
