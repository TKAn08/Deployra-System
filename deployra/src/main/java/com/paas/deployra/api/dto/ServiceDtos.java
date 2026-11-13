package com.paas.deployra.api.dto;

import com.paas.deployra.paas.AppService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class ServiceDtos {

    private ServiceDtos() {
    }

    public record CreateRequest(
            @NotBlank @Size(max = 80) String name,
            AppService.Type type,
            @Size(max = 500) String repoUrl,
            @Size(max = 255) String branch,
            @Size(max = 255) String rootDir,
            @Size(max = 255) String dockerfilePath,
            @Min(1) @Max(65535) Integer port,
            @Size(max = 255) String healthcheckPath,
            @Min(64) @Max(8192) Integer memoryMb
    ) {
    }

    public record Summary(
            Long id,
            Long projectId,
            String name,
            String slug,
            AppService.Type type,
            AppService.Status status,
            String repoUrl,
            String branch,
            Integer port,
            int memoryMb,
            Instant createdAt
    ) {
    }

    public record Detail(
            Long id,
            Long projectId,
            String projectName,
            String name,
            String slug,
            AppService.Type type,
            AppService.Status status,
            String repoUrl,
            String branch,
            String rootDir,
            String dockerfilePath,
            Integer port,
            String healthcheckPath,
            int memoryMb,
            int cpuMillicores,
            AppService.SuspendedReason suspendedReason,
            Instant lastActiveAt,
            Instant createdAt
    ) {
    }
}
