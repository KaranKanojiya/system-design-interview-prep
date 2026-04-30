package com.systemdesign.trading.repository;

import com.systemdesign.trading.model.Position;

import java.util.List;

/**
 * PositionRepository stores and retrieves user positions (holdings).
 *
 * CALL CHAIN:
 * Trade executed → PortfolioService.updatePositionOnTrade() →
 * PositionRepository.findByUserIdAndSymbol() → update position →
 * PositionRepository.save() → Portfolio display reads findByUserId()
 */
public interface PositionRepository {

    void save(Position position);

    Position findByUserIdAndSymbol(String userId, String symbol);

    List<Position> findByUserId(String userId);

    List<Position> findAll();
}
