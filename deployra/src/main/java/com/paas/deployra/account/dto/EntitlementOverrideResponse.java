/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.paas.deployra.account.dto;

/**
 *
 * @author ADMIN
 */
import java.time.Instant;
import lombok.Builder;

@Builder
public record EntitlementOverrideResponse(
        Long id,
        Long userId,
        String key,
        Long limitValue,
        String reason,
        Instant expiresAt) {

}
