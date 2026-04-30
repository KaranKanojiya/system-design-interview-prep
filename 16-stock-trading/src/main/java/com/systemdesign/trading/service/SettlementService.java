package com.systemdesign.trading.service;

import com.systemdesign.trading.model.Trade;
import com.systemdesign.trading.repository.TradeRepository;

import java.util.List;

/**
 * SettlementService handles T+1 trade settlement simulation.
 *
 * WHAT IS SETTLEMENT:
 * - In India, equity trades settle T+1 (trade today, settle next business day).
 * - Settlement means: actual transfer of funds (buyer→seller) and shares (seller→buyer).
 * - Before settlement, funds are "blocked" (margin) and shares are "locked".
 * - After settlement, the buyer truly owns the shares and the seller truly has the money.
 *
 * WHY T+1 (not instant):
 * - Historical reason: physical share certificates needed to be delivered.
 * - Modern reason: netting. If user buys 100 RELIANCE and sells 50 RELIANCE same day,
 *   only the net 50 shares need to settle. This reduces the number of transfers.
 * - India moved from T+2 to T+1 in 2023 (one of the fastest in the world).
 *
 * SETTLEMENT FLOW:
 * 1. Find all unsettled trades.
 * 2. For each trade: transfer funds (buyer pays, seller receives) via AccountService.
 * 3. Mark trade as settled in TradeRepository.
 * 4. Notify both parties.
 *
 * CALL CHAIN:
 * End of day (or manual trigger) → SettlementService.settleTradesForDay() →
 * TradeRepository.findUnsettled() → for each trade: AccountService.settleTradePayment() →
 * TradeRepository.markSettled() → NotificationService.notifyTradeSettled()
 */
public class SettlementService {

    private final TradeRepository tradeRepository;
    private final AccountService accountService;
    private final NotificationService notificationService;

    public SettlementService(TradeRepository tradeRepository,
                             AccountService accountService,
                             NotificationService notificationService) {
        this.tradeRepository = tradeRepository;
        this.accountService = accountService;
        this.notificationService = notificationService;
    }

    /**
     * Settle all unsettled trades (T+1 simulation).
     *
     * In production, this runs as a batch job at end of day + 1.
     * Here we simulate it as an on-demand operation.
     *
     * @return number of trades settled
     */
    public int settleTradesForDay() {
        List<Trade> unsettledTrades = tradeRepository.findUnsettled();

        if (unsettledTrades.isEmpty()) {
            System.out.println("  No trades to settle.");
            return 0;
        }

        System.out.printf("  Settling %d trade(s)...%n", unsettledTrades.size());
        int settledCount = 0;

        for (Trade trade : unsettledTrades) {
            try {
                // Transfer funds: buyer pays, seller receives
                accountService.settleTradePayment(trade);

                // Mark as settled
                tradeRepository.markSettled(trade.getTradeId());

                // Notify both parties
                notificationService.notifyTradeSettled(trade);

                settledCount++;

                System.out.printf("  Settled: %s → %s pays %.2f to %s for %d %s%n",
                        trade.getTradeId(),
                        trade.getBuyerUserId(),
                        trade.getValue(),
                        trade.getSellerUserId(),
                        trade.getQuantity(),
                        trade.getSymbol());

            } catch (Exception e) {
                // In production: retry logic, dead letter queue, manual intervention.
                System.out.printf("  SETTLEMENT FAILED for trade %s: %s%n",
                        trade.getTradeId(), e.getMessage());
            }
        }

        System.out.printf("  Settlement complete: %d/%d trades settled.%n",
                settledCount, unsettledTrades.size());
        return settledCount;
    }
}
