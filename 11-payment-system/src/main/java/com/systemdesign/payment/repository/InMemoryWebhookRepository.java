package com.systemdesign.payment.repository;

import com.systemdesign.payment.model.WebhookEvent;
import com.systemdesign.payment.model.WebhookStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemoryWebhookRepository — ConcurrentHashMap-backed webhook event storage.
 */
public class InMemoryWebhookRepository implements WebhookRepository {

    private final Map<String, WebhookEvent> store = new ConcurrentHashMap<>();

    @Override
    public void save(WebhookEvent event) {
        store.put(event.getEventId(), event);
    }

    @Override
    public Optional<WebhookEvent> findById(String eventId) {
        return Optional.ofNullable(store.get(eventId));
    }

    @Override
    public List<WebhookEvent> findByStatus(WebhookStatus status) {
        List<WebhookEvent> result = new ArrayList<>();
        for (WebhookEvent event : store.values()) {
            if (event.getStatus() == status) {
                result.add(event);
            }
        }
        return result;
    }

    @Override
    public List<WebhookEvent> findByMerchantId(String merchantId) {
        List<WebhookEvent> result = new ArrayList<>();
        for (WebhookEvent event : store.values()) {
            if (event.getMerchantId().equals(merchantId)) {
                result.add(event);
            }
        }
        return result;
    }

    @Override
    public List<WebhookEvent> findAll() {
        return new ArrayList<>(store.values());
    }
}
