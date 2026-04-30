package com.systemdesign.payment.model;

/**
 * AccountType — The role an account plays in the payment ecosystem.
 *
 * MERCHANT    — receives payment for goods/services
 * PLATFORM    — the payment platform itself (earns fees, holds float)
 * CUSTOMER    — the payer
 * SETTLEMENT  — intermediate account used during settlement cycles
 *
 * WHY separate account types?
 *   Different accounts have different rules: a CUSTOMER account can go
 *   negative (credit line), a MERCHANT account cannot, the PLATFORM
 *   account collects fees, and SETTLEMENT is a temporary holding area.
 */
public enum AccountType {
    MERCHANT,
    PLATFORM,
    CUSTOMER,
    SETTLEMENT
}
