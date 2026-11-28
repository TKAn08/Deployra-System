/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.paas.deployra.account;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 *
 * @author ADMIN
 */
@Component
@RequiredArgsConstructor
public class SubscriptionExpiryJob {

    private final UserRepository userRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final Clock clock;

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireSubscriptions() {
        Instant now = clock.instant();

        List<Long> userIds = userSubscriptionRepository.findUserIdWithExpiredSubscriptions(UserSubscription.Status.ACTIVE, now);

        for (Long userId : userIds) {
            var user = userRepository.findByIdForUpdate(userId);
            if (user.isEmpty()) {
                continue;
            }

            Optional<UserSubscription> currentSubscription = userSubscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(userId, UserSubscription.Status.ACTIVE);

            if (currentSubscription.isEmpty()) {
                continue;
            }

            UserSubscription subscription = currentSubscription.get();
            if (subscription.getCurrentPeriodEnd().isAfter(now)) {
                continue;
            }

            subscription.setStatus(UserSubscription.Status.EXPIRED);
            user.get().setSubscriptionPlan(null);
        }
    }
}
