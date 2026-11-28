/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.paas.deployra.account.dto;

import lombok.Builder;

/**
 *
 * @author ADMIN
 */
@Builder
public record CurrentSubscriptionPlanResponse(
        String planCode,
        String planName
) {
}
