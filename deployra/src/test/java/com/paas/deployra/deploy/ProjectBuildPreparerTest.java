package com.paas.deployra.deploy;

import com.paas.deployra.paas.AppService;
import com.paas.deployra.paas.AppServiceRepository;
import com.paas.deployra.paas.EnvVariableService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectBuildPreparerTest {

    private EnvVariableService envVars;
    private AppServiceRepository services;
    private ProjectBuildPreparer preparer;

    @BeforeEach
    void setUp() {
        envVars = mock(EnvVariableService.class);
        services = mock(AppServiceRepository.class);
        preparer = new ProjectBuildPreparer(envVars, services);
    }

    @Test
    void detectsFrontendSpa(@TempDir Path dir) throws IOException {
        String packageJson = """
                {
                  "dependencies": {
                    "react": "^18.0.0",
                    "react-dom": "^18.0.0"
                  },
                  "devDependencies": {
                    "vite": "^5.0.0"
                  }
                }
                """;
        Files.writeString(dir.resolve("package.json"), packageJson);

        assertEquals(ProjectBuildPreparer.ProjectType.FRONTEND_SPA, preparer.detectProjectType(dir));
    }

    @Test
    void detectsNodeBackend(@TempDir Path dir) throws IOException {
        String packageJson = """
                {
                  "dependencies": {
                    "express": "^4.18.2",
                    "cors": "^2.8.5"
                  },
                  "scripts": {
                    "start": "node index.js"
                  }
                }
                """;
        Files.writeString(dir.resolve("package.json"), packageJson);

        assertEquals(ProjectBuildPreparer.ProjectType.NODE_BACKEND, preparer.detectProjectType(dir));
    }

    @Test
    void detectsJavaMaven(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project></project>");
        assertEquals(ProjectBuildPreparer.ProjectType.JAVA_MAVEN, preparer.detectProjectType(dir));
    }

    @Test
    void detectsPython(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("requirements.txt"), "fastapi\nuvicorn");
        assertEquals(ProjectBuildPreparer.ProjectType.PYTHON, preparer.detectProjectType(dir));
    }

    @Test
    void detectsGo(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("go.mod"), "module myapp\ngo 1.22");
        assertEquals(ProjectBuildPreparer.ProjectType.GO, preparer.detectProjectType(dir));
    }

    @Test
    void generatesJavaMavenDockerfileWhenMissing(@TempDir Path dir) throws IOException {
        AppService service = new AppService();
        service.setId(1L);
        service.setPort(8000);

        Files.writeString(dir.resolve("pom.xml"), "<project></project>");

        List<String> logs = new ArrayList<>();
        ProjectBuildPreparer.ProjectType type = preparer.prepare(service, dir, logs::add);

        assertEquals(ProjectBuildPreparer.ProjectType.JAVA_MAVEN, type);
        Path dockerfile = dir.resolve("Dockerfile");
        assertTrue(Files.exists(dockerfile));
        String content = Files.readString(dockerfile);
        assertTrue(content.contains("FROM maven:3.9-eclipse-temurin-21-alpine AS builder"));
        assertTrue(content.contains("FROM eclipse-temurin:21-jre-alpine"));
        assertTrue(content.contains("EXPOSE 8080"));
        assertEquals(8080, service.getPort());
        verify(services).save(service);
    }

    @Test
    void generatesPythonDockerfileWhenMissing(@TempDir Path dir) throws IOException {
        AppService service = new AppService();
        service.setId(2L);
        service.setPort(8000);

        Files.writeString(dir.resolve("requirements.txt"), "flask");

        List<String> logs = new ArrayList<>();
        ProjectBuildPreparer.ProjectType type = preparer.prepare(service, dir, logs::add);

        assertEquals(ProjectBuildPreparer.ProjectType.PYTHON, type);
        Path dockerfile = dir.resolve("Dockerfile");
        assertTrue(Files.exists(dockerfile));
        String content = Files.readString(dockerfile);
        assertTrue(content.contains("FROM python:3.11-slim"));
        assertTrue(content.contains("pip install"));
    }

    @Test
    void generatesNodeBackendDockerfileWhenMissing(@TempDir Path dir) throws IOException {
        AppService service = new AppService();
        service.setId(3L);
        service.setPort(8000);

        String packageJson = "{\"dependencies\": {\"express\": \"^4.18.2\"}}";
        Files.writeString(dir.resolve("package.json"), packageJson);

        List<String> logs = new ArrayList<>();
        ProjectBuildPreparer.ProjectType type = preparer.prepare(service, dir, logs::add);

        assertEquals(ProjectBuildPreparer.ProjectType.NODE_BACKEND, type);
        Path dockerfile = dir.resolve("Dockerfile");
        assertTrue(Files.exists(dockerfile));
        String content = Files.readString(dockerfile);
        assertTrue(content.contains("FROM node:20-alpine"));
        assertTrue(content.contains("CMD [\"npm\", \"start\"]"));
    }

    @Test
    void detectsAndGeneratesStaticHtmlDockerfileWhenOnlyIndexHtmlExists(@TempDir Path dir) throws IOException {
        AppService service = new AppService();
        service.setId(4L);
        service.setPort(8000);

        Files.writeString(dir.resolve("index.html"), "<h1>Hello Deployra</h1>");

        List<String> logs = new ArrayList<>();
        ProjectBuildPreparer.ProjectType type = preparer.prepare(service, dir, logs::add);

        assertEquals(ProjectBuildPreparer.ProjectType.STATIC_HTML, type);
        Path dockerfile = dir.resolve("Dockerfile");
        assertTrue(Files.exists(dockerfile));
        String content = Files.readString(dockerfile);
        assertTrue(content.contains("FROM nginx:alpine"));
        assertTrue(content.contains("COPY . /usr/share/nginx/html"));
        assertTrue(content.contains("EXPOSE 80"));
        assertEquals(80, service.getPort());
        verify(services).save(service);
    }
}
