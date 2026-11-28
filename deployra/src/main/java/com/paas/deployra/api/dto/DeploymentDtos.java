package com.paas.deployra.api.dto;

import com.paas.deployra.paas.Deployment;

import java.time.Instant;

public final class DeploymentDtos {

    private DeploymentDtos() {
    }

    public record View(
            Long id,
            Deployment.Status status,
            Deployment.Trigger trigger,
            Deployment.SourceType sourceType,
            String commitSha,
            String image,
            Integer buildSeconds,
            String buildLog,
            Instant createdAt,
            Instant finishedAt
    ) {
    }
}
