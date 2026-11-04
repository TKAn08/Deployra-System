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

	// TODO: bỏ các mock này khi đã có bean thật. Hiện chưa có class nào implement
	// ContainerRuntime, còn GitSourceFetcher và ZipSourceExtractor chưa được khai
	// báo thành bean, nên DeploymentService không có đủ dependency để Spring khởi
	// động.
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