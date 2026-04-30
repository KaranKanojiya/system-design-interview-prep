package com.systemdesign.payment.model;

/**
 * Merchant — A business that accepts payments through our platform.
 *
 * webhookUrl — where we POST payment events (payment.succeeded, refund.completed, etc.)
 * apiKey     — used to authenticate API requests from the merchant
 * isActive   — merchants can be deactivated (fraud, ToS violation, etc.)
 */
public class Merchant {

    private final String merchantId;
    private final String name;
    private final String email;
    private final String webhookUrl;
    private final String apiKey;
    private boolean isActive;

    public Merchant(String merchantId, String name, String email,
                    String webhookUrl, String apiKey, boolean isActive) {
        this.merchantId = merchantId;
        this.name = name;
        this.email = email;
        this.webhookUrl = webhookUrl;
        this.apiKey = apiKey;
        this.isActive = isActive;
    }

    // ── Getters ──
    public String getMerchantId() { return merchantId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getWebhookUrl() { return webhookUrl; }
    public String getApiKey() { return apiKey; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { this.isActive = active; }

    @Override
    public String toString() {
        return "Merchant{" +
                "id='" + merchantId + '\'' +
                ", name='" + name + '\'' +
                ", email='" + email + '\'' +
                ", webhookUrl='" + webhookUrl + '\'' +
                ", active=" + isActive +
                '}';
    }
}
