package com.systemdesign.trading.model;

/**
 * User represents a trading platform participant.
 *
 * WHY accountType matters:
 * - RETAIL users have lower position limits, higher margin requirements.
 * - INSTITUTIONAL users (mutual funds, hedge funds) get different risk parameters.
 * - In production, accountType drives which risk checks apply and their thresholds.
 *
 * CALL CHAIN:
 * AppConfig seeds users → AccountService.getAccount() for margin checks →
 * RiskService uses accountType for position limits
 */
public class User {

    private final String userId;
    private final String name;
    private final String email;
    private final String accountType;  // "RETAIL" or "INSTITUTIONAL"

    public User(String userId, String name, String email, String accountType) {
        this.userId = userId;
        this.name = name;
        this.email = email;
        this.accountType = accountType;
    }

    public String getUserId() { return userId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getAccountType() { return accountType; }

    @Override
    public String toString() {
        return String.format("User{id='%s', name='%s', type='%s'}", userId, name, accountType);
    }
}
