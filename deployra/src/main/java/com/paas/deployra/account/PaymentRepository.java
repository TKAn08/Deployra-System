package com.paas.deployra.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * Chống xử lý trùng khi cổng thanh toán gửi webhook nhiều lần.
     */
    Optional<Payment> findByProviderTxnId(String providerTxnId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    List<Payment> findByUserIdOrderByCreatedAtDesc(Long userId);
}
