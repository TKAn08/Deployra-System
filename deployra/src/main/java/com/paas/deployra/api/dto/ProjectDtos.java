package com.paas.deployra.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class ProjectDtos {

    private ProjectDtos() {
    }

    public record CreateRequest(@NotBlank @Size(max = 120) String name) {
    }

    public record Summary(Long id, String name, Instant createdAt, long serviceCount) {
    }

    public record Detail(Long id, String name, Instant createdAt, java.util.List<ServiceDtos.Summary> services) {
    }
}
