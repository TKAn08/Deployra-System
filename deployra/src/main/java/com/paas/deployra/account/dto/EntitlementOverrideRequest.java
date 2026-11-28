/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.paas.deployra.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;

/**
 *
 * @author ADMIN
 */
public record EntitlementOverrideRequest(
        @JsonProperty(value = "limitValue", required = true)
        @PositiveOrZero Long limitValue,
        String reason,
        Instant expiresAt
) {
}
