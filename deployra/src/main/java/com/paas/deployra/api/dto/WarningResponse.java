/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.paas.deployra.api.dto;

/**
 *
 * @author ADMIN
 */
import java.time.Instant;
import lombok.Builder;

@Builder
public record WarningResponse(
        long allocatedMb,
        long limitMb,
        Instant checkedAt,
        Instant deadline) {

}
