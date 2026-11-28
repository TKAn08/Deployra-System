/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.paas.deployra.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 *
 * @author ADMIN
 */
public record SubscibePlanRequest(
        @NotBlank
        String planCode) {

}
