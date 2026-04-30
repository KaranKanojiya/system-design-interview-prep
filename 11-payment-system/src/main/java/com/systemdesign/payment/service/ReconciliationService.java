package com.systemdesign.payment.service;

import com.systemdesign.payment.model.Transaction;

import java.util.*;

/**
 * ReconciliationService — Matches internal records against external processor statements.
 *
 * ═══════════════════════════════════════════════════════════
 *  WHY RECONCILIATION MATTERS
 * ═══════════════════════════════════════════════════════════
 *
 * In payment systems, money flows through multiple parties:
 *   Customer → Card Network → Acquiring Bank → Our Platform → Merchant
 *
 * Each party keeps their own records.  Discrepancies happen:
 *   - We think we charged $100, but the processor recorded $99.99 (rounding)
 *   - We recorded a payment, but the processor lost it (network failure)
 *   - The processor recorded a payment we don't know about (duplicate)
 *
 * Reconciliation finds these discrepancies BEFORE they become problems
 * (regulatory fines, customer complaints, lost revenue).
 *
 * HOW IT WORKS:
 *   1. Get our internal transaction records (from TransactionRepository)
 *   2. Get the processor's settlement file (CSV/API — simulated here)
 *   3. Match by processorTransactionId
 *   4. Find:
 *      - MATCHED: both sides agree on amount and ID
 *      - AMOUNT MISMATCH: same ID but different amounts
 *      - MISSING EXTERNAL: we have it but processor doesn't (we charged, they didn't record)
 *      - MISSING INTERNAL: processor has it but we don't (they charged, we didn't record)
 *
 * FREQUENCY:
 *   Daily reconciliation for most payment platforms.
 *   Real-time reconciliation for high-volume systems.
 *
 * ═══════════════════════════════════════════════════════════
 */
public class ReconciliationService {

    /**
     * Reconciliation report — the output of a reconciliation run.
     */
    public static class ReconciliationReport {
        private final List<String> matched = new ArrayList<>();
        private final List<String> amountMismatches = new ArrayList<>();
        private final List<String> missingExternal = new ArrayList<>();
        private final List<String> missingInternal = new ArrayList<>();

        public List<String> getMatched() { return matched; }
        public List<String> getAmountMismatches() { return amountMismatches; }
        public List<String> getMissingExternal() { return missingExternal; }
        public List<String> getMissingInternal() { return missingInternal; }

        public boolean isClean() {
            return amountMismatches.isEmpty() && missingExternal.isEmpty() && missingInternal.isEmpty();
        }

        @Override
        public String toString() {
            return "ReconciliationReport{" +
                    "matched=" + matched.size() +
                    ", amountMismatches=" + amountMismatches.size() +
                    ", missingExternal=" + missingExternal.size() +
                    ", missingInternal=" + missingInternal.size() +
                    '}';
        }
    }

    /**
     * External statement record — represents a line in the processor's settlement file.
     * In production this would be parsed from a CSV or fetched via API.
     */
    public static class ExternalStatement {
        private final String processorTransactionId;
        private final double amount;
        private final String status;

        public ExternalStatement(String processorTransactionId, double amount, String status) {
            this.processorTransactionId = processorTransactionId;
            this.amount = amount;
            this.status = status;
        }

        public String getProcessorTransactionId() { return processorTransactionId; }
        public double getAmount() { return amount; }
        public String getStatus() { return status; }
    }

    /**
     * Reconcile internal transactions against external processor statements.
     *
     * ALGORITHM:
     *   1. Index both lists by processorTransactionId for O(1) lookup
     *   2. For each internal transaction, check if external has it
     *      - If yes and amounts match → MATCHED
     *      - If yes but amounts differ → AMOUNT MISMATCH
     *      - If no → MISSING EXTERNAL (we recorded it, processor didn't)
     *   3. For each external statement not matched → MISSING INTERNAL
     *      (processor recorded it, we didn't)
     *
     * @param internalTransactions our transaction records
     * @param externalStatements   processor's settlement records
     * @return ReconciliationReport with all findings
     */
    public ReconciliationReport reconcile(List<Transaction> internalTransactions,
                                          List<ExternalStatement> externalStatements) {
        ReconciliationReport report = new ReconciliationReport();

        // ── Step 1: Index external statements by processorTransactionId ──
        Map<String, ExternalStatement> externalMap = new HashMap<>();
        for (ExternalStatement stmt : externalStatements) {
            externalMap.put(stmt.getProcessorTransactionId(), stmt);
        }

        // Track which external statements we've matched
        Set<String> matchedExternal = new HashSet<>();

        // ── Step 2: Check each internal transaction against external ──
        for (Transaction internal : internalTransactions) {
            String txnId = internal.getProcessorTransactionId();
            ExternalStatement external = externalMap.get(txnId);

            if (external == null) {
                // We recorded this transaction, but the processor doesn't have it
                // This could mean: our request reached the processor but their
                // settlement file is stale, OR we recorded a phantom transaction
                report.getMissingExternal().add(
                    "MISSING IN EXTERNAL: " + txnId
                    + " (internal amount: " + internal.getAmount() + ")"
                );
            } else if (Math.abs(internal.getAmount() - external.getAmount()) > 0.01) {
                // Both sides have the transaction, but amounts don't match
                // This could be: rounding errors, partial captures, currency conversion issues
                report.getAmountMismatches().add(
                    "AMOUNT MISMATCH: " + txnId
                    + " (internal: " + internal.getAmount()
                    + ", external: " + external.getAmount()
                    + ", diff: " + Math.abs(internal.getAmount() - external.getAmount()) + ")"
                );
                matchedExternal.add(txnId);
            } else {
                // Perfect match — both sides agree
                report.getMatched().add(
                    "MATCHED: " + txnId + " (amount: " + internal.getAmount() + ")"
                );
                matchedExternal.add(txnId);
            }
        }

        // ── Step 3: Find external statements we don't have internally ──
        for (ExternalStatement external : externalStatements) {
            if (!matchedExternal.contains(external.getProcessorTransactionId())) {
                // Check if it was already reported as missing external
                boolean alreadyReported = report.getMissingExternal().stream()
                    .anyMatch(s -> s.contains(external.getProcessorTransactionId()));
                if (!alreadyReported) {
                    // Processor has this transaction, but we don't
                    // This is dangerous: money may have moved without our knowledge
                    report.getMissingInternal().add(
                        "MISSING IN INTERNAL: " + external.getProcessorTransactionId()
                        + " (external amount: " + external.getAmount() + ")"
                    );
                }
            }
        }

        return report;
    }
}
