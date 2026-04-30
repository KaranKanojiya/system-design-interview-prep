package com.systemdesign.payment.repository;

import com.systemdesign.payment.model.Merchant;

import java.util.List;
import java.util.Optional;

/**
 * MerchantRepository — Data access interface for Merchant entities.
 */
public interface MerchantRepository {
    void save(Merchant merchant);
    Optional<Merchant> findById(String merchantId);
    List<Merchant> findAll();
}
