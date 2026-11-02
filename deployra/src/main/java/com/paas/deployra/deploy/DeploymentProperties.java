package com.paas.deployra.deploy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
public class DeploymentProperties {

    private final int workerCount;
    private final Duration healthTimeout;
    private final String healthPath;
    private final int appPort;
    private final Duration buildTimeout;

    public DeploymentProperties(
            @Value("${deployra.deploy.worker-count:1}") int workerCount,
            @Value("${deployra.deploy.health-timeout:60s}") Duration healthTimeout,
            @Value("${deployra.deploy.health-path:/health}") String healthPath,
            @Value("${deployra.deploy.app-port:8080}") int appPort,
            @Value("${deployra.deploy.build-timeout:300s}") Duration buildTimeout) {
        this.workerCount = Math.max(1, workerCount);
        this.healthTimeout = healthTimeout;
        this.healthPath = healthPath;
        this.appPort = appPort;
        this.buildTimeout = buildTimeout;
    }

    public int getWorkerCount() {
        return workerCount;
    }

    public Duration getHealthTimeout() {
        return healthTimeout;
    }

    public String getHealthPath() {
        return healthPath;
    }

    public int getAppPort() {
        return appPort;
    }

    public Duration getBuildTimeout() {
        return buildTimeout;
    }
}