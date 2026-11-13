package com.paas.deployra.api;

import com.paas.deployra.api.dto.QuotaDtos;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hạn mức của người dùng đang đăng nhập.
 * GET  /api/me/quota                       -> báo cáo đã dùng / giới hạn
 * POST /api/me/addons/{packageId}/purchase -> mua add-on (thanh toán giả lập)
 */
@RestController
@RequestMapping("/api/me")
public class QuotaController {

    private final QuotaApiService quota;

    public QuotaController(QuotaApiService quota) {
        this.quota = quota;
    }

    @GetMapping("/quota")
    public QuotaDtos.Report report() {
        return quota.report();
    }

    @PostMapping("/addons/{packageId}/purchase")
    @ResponseStatus(HttpStatus.CREATED)
    public QuotaDtos.PurchaseResponse purchase(@PathVariable Long packageId) {
        return quota.purchase(packageId);
    }
}