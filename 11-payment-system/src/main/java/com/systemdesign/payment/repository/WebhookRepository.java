package com.systemdesign.payment.repository;

import com.systemdesign.payment.model.WebhookEvent;
import com.systemdesign.payment.model.WebhookStatus;

import java.util.List;
import java.util.Optional;

/**
 * WebhookRepository — Data access interface for WebhookEvent entities.
 */
public interface WebhookRepository {
    void save(WebhookEvent event);
    Optional<WebhookEvent> findById(String eventId);
    List<WebhookEvent> findByStatus(WebhookStatus status);
    List<WebhookEvent> findByMerchantId(String merchantId);
    List<WebhookEvent> findAll();
}
