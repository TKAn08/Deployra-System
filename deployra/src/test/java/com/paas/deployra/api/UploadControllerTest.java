package com.paas.deployra.api;

import com.paas.deployra.common.ApiExceptionHandler;
import com.paas.deployra.common.InvalidRequestException;
import com.paas.deployra.common.NotFoundException;
import com.paas.deployra.common.QuotaExceededException;
import com.paas.deployra.entitlement.EntitlementCodes;
import com.paas.deployra.paas.SourceUpload;
import com.paas.deployra.paas.SourceUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dựng MockMvc độc lập (không khởi động Spring), kiểm tra đường dẫn, tham số và
 * mã lỗi HTTP.
 */
class UploadControllerTest {

    private SourceUploadService uploads;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        uploads = mock(SourceUploadService.class);
        mvc = MockMvcBuilders.standaloneSetup(new UploadController(uploads))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockMultipartFile zipFile() {
        return new MockMultipartFile("file", "app.zip", "application/zip",
                "PK\u0003\u0004demo".getBytes(StandardCharsets.ISO_8859_1));
    }

    private static SourceUpload result() {
        SourceUpload upload = new SourceUpload();
        upload.setId(11L);
        upload.setSizeBytes(8);
        upload.setSha256("abc");
        return upload;
    }

    @Test
    void validUploadReturns201WithUploadInfoAndNoDeployment() throws Exception {
        // userId đang là null vì chưa ghép phần đăng nhập (xem UploadController)
        when(uploads.uploadZip(isNull(), eq(5L), eq("app.zip"), anyLong(), any(InputStream.class)))
                .thenReturn(result());

        mvc.perform(multipart("/api/services/5/uploads").file(zipFile()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploadId").value(11))
                .andExpect(jsonPath("$.sha256").value("abc"))
                .andExpect(jsonPath("$.deploymentId").doesNotExist())
                .andExpect(jsonPath("$.deploymentStatus").doesNotExist());

        verify(uploads).uploadZip(isNull(), eq(5L), eq("app.zip"), eq(8L), any(InputStream.class));
    }

    @Test
    void missingFilePartReturns400() throws Exception {
        mvc.perform(multipart("/api/services/5/uploads"))
                .andExpect(status().isBadRequest());
        verify(uploads, never()).uploadZip(any(), anyLong(), any(), anyLong(), any());
    }

    @Test
    void invalidFileReturns400() throws Exception {
        when(uploads.uploadZip(any(), anyLong(), any(), anyLong(), any()))
                .thenThrow(new InvalidRequestException("File không phải zip hợp lệ"));

        mvc.perform(multipart("/api/services/5/uploads").file(zipFile()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message").value("File không phải zip hợp lệ"));
    }

    @Test
    void unknownServiceReturns404() throws Exception {
        when(uploads.uploadZip(any(), anyLong(), any(), anyLong(), any()))
                .thenThrow(new NotFoundException("Không tìm thấy service"));

        mvc.perform(multipart("/api/services/5/uploads").file(zipFile()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void quotaExceededReturns403() throws Exception {
        when(uploads.uploadZip(any(), anyLong(), any(), anyLong(), any()))
                .thenThrow(new QuotaExceededException(EntitlementCodes.MAX_UPLOAD_MB, "File zip tối đa 50 MB"));

        mvc.perform(multipart("/api/services/5/uploads").file(zipFile()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("quota_exceeded"))
                .andExpect(jsonPath("$.key").value(EntitlementCodes.MAX_UPLOAD_MB));
    }

    @Test
    void getIsNotAllowed() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/services/5/uploads"))
                .andExpect(status().isMethodNotAllowed());
    }
}