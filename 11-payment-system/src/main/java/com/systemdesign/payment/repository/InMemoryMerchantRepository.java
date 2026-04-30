package com.systemdesign.payment.repository;

import com.systemdesign.payment.model.Merchant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemoryMerchantRepository — ConcurrentHashMap-backed merchant storage.
 */
public class InMemoryMerchantRepository implements MerchantRepository {

    private final Map<String, Merchant> store = new ConcurrentHashMap<>();

    @Override
    public void save(Merchant merchant) {
        store.put(merchant.getMerchantId(), merchant);
    }

    @Override
    public Optional<Merchant> findById(String merchantId) {
        return Optional.ofNullable(store.get(merchantId));
    }

    @Override
    public List<Merchant> findAll() {
        return new ArrayList<>(store.values());
    }
}
