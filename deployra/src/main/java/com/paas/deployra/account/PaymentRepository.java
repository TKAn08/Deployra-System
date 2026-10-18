package com.paas.deployra.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** Chống xử lý trùng khi cổng thanh toán gửi webhook nhiều lần. */
    Optional<Payment> findByProviderTxnId(String providerTxnId);

    List<Payment> findByUserIdOrderByCreatedAtDesc(Long userId);
}
