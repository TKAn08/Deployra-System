package com.paas.deployra.api;

import com.paas.deployra.common.ApiExceptionHandler;
import com.paas.deployra.common.InvalidRequestException;
import com.paas.deployra.common.NotFoundException;
import com.paas.deployra.paas.Deployment;
import com.paas.deployra.paas.SourceUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MockMvc độc lập: kiểm tra việc chọn nguồn zip/git và mã HTTP của bước "run". */
class DeploymentControllerTest {

    private SourceUploadService uploads;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        uploads = mock(SourceUploadService.class);
        mvc = MockMvcBuilders.standaloneSetup(new DeploymentController(uploads))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static Deployment deployment(Deployment.SourceType type) {
        Deployment d = new Deployment();
        d.setId(21L);
        d.setSourceType(type);
        return d;
    }

    @Test
    void withUploadIdDeploysTheZip() throws Exception {
        when(uploads.deployUpload(isNull(), eq(5L), eq(11L))).thenReturn(deployment(Deployment.SourceType.ZIP));

        mvc.perform(post("/api/services/5/deployments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"uploadId\": 11}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.deploymentId").value(21))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.sourceType").value("ZIP"));

        verify(uploads, never()).deployFromGit(any(), anyLong());
    }

    @Test
    void withoutBodyDeploysFromGit() throws Exception {
        when(uploads.deployFromGit(isNull(), eq(5L))).thenReturn(deployment(Deployment.SourceType.GIT));

        mvc.perform(post("/api/services/5/deployments"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sourceType").value("GIT"));

        verify(uploads, never()).deployUpload(any(), anyLong(), anyLong());
    }

    @Test
    void emptyJsonObjectAlsoDeploysFromGit() throws Exception {
        when(uploads.deployFromGit(isNull(), eq(5L))).thenReturn(deployment(Deployment.SourceType.GIT));

        mvc.perform(post("/api/services/5/deployments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void nonPositiveUploadIdReturns400() throws Exception {
        mvc.perform(post("/api/services/5/deployments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"uploadId\": 0}"))
                .andExpect(status().isBadRequest());

        verify(uploads, never()).deployUpload(any(), anyLong(), anyLong());
    }

    @Test
    void unknownUploadReturns404() throws Exception {
        when(uploads.deployUpload(any(), anyLong(), anyLong()))
                .thenThrow(new NotFoundException("Không tìm thấy bản upload"));

        mvc.perform(post("/api/services/5/deployments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"uploadId\": 11}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void serviceWithoutRepoReturns400() throws Exception {
        when(uploads.deployFromGit(any(), anyLong()))
                .thenThrow(new InvalidRequestException("Service chưa cấu hình repo GitHub"));

        mvc.perform(post("/api/services/5/deployments"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }
}
