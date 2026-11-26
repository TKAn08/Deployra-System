package com.paas.deployra;

import com.paas.deployra.deploy.GitSourceFetcher;
import com.paas.deployra.deploy.ZipSourceExtractor;
import com.paas.deployra.runtime.ContainerRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class DeployraApplicationTests {

	@MockitoBean
	ContainerRuntime containerRuntime;

	@MockitoBean
	GitSourceFetcher gitSourceFetcher;

	@MockitoBean
	ZipSourceExtractor zipSourceExtractor;

	@Test
	void contextLoads() {
	}

}