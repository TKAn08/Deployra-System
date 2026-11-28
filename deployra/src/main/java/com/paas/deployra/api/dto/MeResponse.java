package com.paas.deployra.api.dto;

import lombok.Builder;

@Builder
public record MeResponse(
        Long id,
        String email,
        String firstName,
        String lastName,
        String planCode,
        String planName,
        boolean admin
) {
}