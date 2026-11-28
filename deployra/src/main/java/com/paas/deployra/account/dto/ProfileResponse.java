package com.paas.deployra.account.dto;

import lombok.Builder;

@Builder
public record ProfileResponse(
        Long id,
        String email,
        String firstName,
        String lastName) {

}
