package com.systemdesign.payment.config;

import com.systemdesign.payment.controller.PaymentController;
import com.systemdesign.payment.display.PaymentStatsDisplay;
import com.systemdesign.payment.model.Account;
import com.systemdesign.payment.model.AccountType;
import com.systemdesign.payment.model.Currency;
import com.systemdesign.payment.model.Merchant;
import com.systemdesign.payment.model.PaymentMethod;
import com.systemdesign.payment.repository.*;
import com.systemdesign.payment.service.*;
import com.systemdesign.payment.strategy.fraud.FraudCheckStrategy;
import com.systemdesign.payment.strategy.fraud.MLFraudCheck;
import com.systemdesign.payment.strategy.fraud.RuleBasedFraudCheck;
import com.systemdesign.payment.strategy.processor.CreditCardProcessor;
import com.systemdesign.payment.strategy.processor.PaymentProcessor;
import com.systemdesign.payment.strategy.processor.UPIProcessor;
import com.systemdesign.payment.strategy.processor.WalletProcessor;

import java.util.*;

/**
 * AppConfig — FACTORY: The ONLY place where "new ConcreteClass()" appears.
 *
 * ═══════════════════════════════════════════════════════════
 *  DEPENDENCY GRAPH
 * ═══════════════════════════════════════════════════════════
 *
 *  Repositories (data layer):
 *    InMemoryPaymentRepository
 *    InMemoryLedgerRepository
 *    InMemoryMerchantRepository
 *    InMemoryAccountRepository
 *    InMemoryIdempotencyRepository
 *    InMemoryWebhookRepository
 *
 *  Strategies (pluggable algorithms):
 *    CreditCardProcessor ──┐
 *    UPIProcessor ─────────┤  → Map<PaymentMethod, PaymentProcessor>
 *    WalletProcessor ──────┘
 *
 *    RuleBasedFraudCheck ──┐
 *    MLFraudCheck ─────────┘  → List<FraudCheckStrategy>
 *
 *  Services (business logic):
 *    IdempotencyService  ← IdempotencyRepository
 *    LedgerService       ← LedgerRepository, AccountRepository
 *    FraudService        ← List<FraudCheckStrategy>
 *    WebhookService      ← WebhookRepository, MerchantRepository
 *    CurrencyService     ← (standalone)
 *    ReconciliationService ← (standalone)
 *    PaymentService      ← PaymentRepo, MerchantRepo, IdempotencyService,
 *                           FraudService, LedgerService, WebhookService, Processors
 *    RefundService       ← PaymentRepo, Processors, LedgerService, WebhookService
 *
 *  Controller (simulated REST):
 *    PaymentController   ← PaymentService, RefundService, LedgerService,
 *                           ReconciliationService, WebhookService
 *
 *  Display:
 *    PaymentStatsDisplay ← PaymentRepo, LedgerService, WebhookService
 *
 * ═══════════════════════════════════════════════════════════
 *
 * In a real Spring app, this would be @Configuration with @Bean methods.
 * In a real Guice app, this would be an AbstractModule with bind() calls.
 * Here it's a plain factory — same concept, zero framework magic.
 */
public class AppConfig {

    // ── Repositories ──
    private final PaymentRepository paymentRepository;
    private final LedgerRepository ledgerRepository;
    private final MerchantRepository merchantRepository;
    private final AccountRepository accountRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final WebhookRepository webhookRepository;

    // ── Processors ──
    private final Map<PaymentMethod, PaymentProcessor> processors;

    // ── Services ──
    private final IdempotencyService idempotencyService;
    private final LedgerService ledgerService;
    private final FraudService fraudService;
    private final WebhookService webhookService;
    private final CurrencyService currencyService;
    private final ReconciliationService reconciliationService;
    private final PaymentService paymentService;
    private final RefundService refundService;

    // ── Controller ──
    private final PaymentController paymentController;

    // ── Display ──
    private final PaymentStatsDisplay statsDisplay;

    public AppConfig() {
        System.out.println("[AppConfig] Initializing payment system...\n");

        // ════════════════════════════════════════════
        //  LAYER 1: Repositories (data access layer)
        //  All backed by ConcurrentHashMap for thread safety.
        // ════════════════════════════════════════════
        this.paymentRepository = new InMemoryPaymentRepository();
        this.ledgerRepository = new InMemoryLedgerRepository();
        this.merchantRepository = new InMemoryMerchantRepository();
        this.accountRepository = new InMemoryAccountRepository();
        this.idempotencyRepository = new InMemoryIdempotencyRepository();
        this.webhookRepository = new InMemoryWebhookRepository();

        // ════════════════════════════════════════════
        //  LAYER 2: Strategies (pluggable algorithms)
        // ════════════════════════════════════════════

        // Payment processors — one per payment method
        // The Map lookup in PaymentService selects the right processor:
        //   PaymentMethod.CREDIT_CARD → CreditCardProcessor
        //   PaymentMethod.UPI → UPIProcessor
        //   PaymentMethod.WALLET → WalletProcessor
        this.processors = new HashMap<>();
        processors.put(PaymentMethod.CREDIT_CARD, new CreditCardProcessor());
        processors.put(PaymentMethod.DEBIT_CARD, new CreditCardProcessor()); // Debit uses same processor
        processors.put(PaymentMethod.UPI, new UPIProcessor());
        processors.put(PaymentMethod.WALLET, new WalletProcessor());
        processors.put(PaymentMethod.BANK_TRANSFER, new CreditCardProcessor()); // Simplified

        // Fraud check strategies — run in order, fail if ANY fails
        Set<String> blacklistedMerchants = Set.of("MERCHANT-BLACKLISTED", "MERCHANT-FRAUD");
        List<FraudCheckStrategy> fraudStrategies = List.of(
            new RuleBasedFraudCheck(blacklistedMerchants),
            new MLFraudCheck()
        );

        // ════════════════════════════════════════════
        //  LAYER 3: Services (business logic)
        //  Order matters — some services depend on others.
        // ════════════════════════════════════════════
        this.idempotencyService = new IdempotencyService(idempotencyRepository);
        this.ledgerService = new LedgerService(ledgerRepository, accountRepository);
        this.fraudService = new FraudService(fraudStrategies);
        this.webhookService = new WebhookService(webhookRepository, merchantRepository);
        this.currencyService = new CurrencyService();
        this.reconciliationService = new ReconciliationService();

        // PaymentService depends on most other services — it's the facade
        this.paymentService = new PaymentService(
            paymentRepository, merchantRepository, idempotencyService,
            fraudService, ledgerService, webhookService, processors
        );

        // RefundService depends on payment repo, processors, ledger, and webhooks
        this.refundService = new RefundService(
            paymentRepository, processors, ledgerService, webhookService
        );

        // ════════════════════════════════════════════
        //  LAYER 4: Controller (simulated REST)
        // ════════════════════════════════════════════
        this.paymentController = new PaymentController(
            paymentService, refundService, ledgerService, reconciliationService, webhookService
        );

        // ════════════════════════════════════════════
        //  LAYER 5: Display (stats and reporting)
        // ════════════════════════════════════════════
        this.statsDisplay = new PaymentStatsDisplay(
            paymentRepository, ledgerService, webhookService
        );

        // ════════════════════════════════════════════
        //  SEED DATA: Create test merchants and accounts
        // ════════════════════════════════════════════
        seedData();

        System.out.println("[AppConfig] Payment system initialized successfully.\n");
    }

    /**
     * Seed test data — merchants and accounts for demos.
     */
    private void seedData() {
        System.out.println("[AppConfig] Seeding test data...");

        // ── Merchants ──
        merchantRepository.save(new Merchant(
            "MERCHANT-001", "Acme Electronics", "acme@example.com",
            "https://acme.example.com/webhooks", "sk_acme_123", true
        ));
        merchantRepository.save(new Merchant(
            "MERCHANT-002", "Global Groceries", "global@example.com",
            "https://global.example.com/webhooks", "sk_global_456", true
        ));
        merchantRepository.save(new Merchant(
            "MERCHANT-003", "Quick Rides", "rides@example.com",
            "https://rides.example.com/webhooks", "sk_rides_789", true
        ));

        // ── Accounts ──
        // Merchant accounts (receive payments)
        accountRepository.save(new Account(
            "MERCHANT-001", "Acme Electronics Account",
            AccountType.MERCHANT, 0.0, Currency.USD
        ));
        accountRepository.save(new Account(
            "MERCHANT-002", "Global Groceries Account",
            AccountType.MERCHANT, 0.0, Currency.USD
        ));
        accountRepository.save(new Account(
            "MERCHANT-003", "Quick Rides Account",
            AccountType.MERCHANT, 0.0, Currency.USD
        ));

        // Platform account (collects fees, holds float)
        accountRepository.save(new Account(
            "ACC-PLATFORM", "Platform Revenue Account",
            AccountType.PLATFORM, 100_000.0, Currency.USD
        ));

        // Customer accounts (for demo purposes)
        accountRepository.save(new Account(
            "CUSTOMER-001", "Alice Customer Account",
            AccountType.CUSTOMER, 50_000.0, Currency.USD
        ));
        accountRepository.save(new Account(
            "CUSTOMER-002", "Bob Customer Account",
            AccountType.CUSTOMER, 25_000.0, Currency.USD
        ));

        // Settlement account (intermediate)
        accountRepository.save(new Account(
            "ACC-SETTLEMENT", "Settlement Account",
            AccountType.SETTLEMENT, 0.0, Currency.USD
        ));

        System.out.println("[AppConfig] Seeded 3 merchants, 7 accounts.");
    }

    // ── Getters for all components ──
    public PaymentRepository getPaymentRepository() { return paymentRepository; }
    public LedgerRepository getLedgerRepository() { return ledgerRepository; }
    public MerchantRepository getMerchantRepository() { return merchantRepository; }
    public AccountRepository getAccountRepository() { return accountRepository; }
    public IdempotencyRepository getIdempotencyRepository() { return idempotencyRepository; }
    public WebhookRepository getWebhookRepository() { return webhookRepository; }
    public Map<PaymentMethod, PaymentProcessor> getProcessors() { return processors; }
    public IdempotencyService getIdempotencyService() { return idempotencyService; }
    public LedgerService getLedgerService() { return ledgerService; }
    public FraudService getFraudService() { return fraudService; }
    public WebhookService getWebhookService() { return webhookService; }
    public CurrencyService getCurrencyService() { return currencyService; }
    public ReconciliationService getReconciliationService() { return reconciliationService; }
    public PaymentService getPaymentService() { return paymentService; }
    public RefundService getRefundService() { return refundService; }
    public PaymentController getPaymentController() { return paymentController; }
    public PaymentStatsDisplay getStatsDisplay() { return statsDisplay; }
}
