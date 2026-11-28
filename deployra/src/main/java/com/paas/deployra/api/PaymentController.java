/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.paas.deployra.api;


import com.paas.deployra.account.PaymentService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 *
 * @author ADMIN
 */
@Slf4j
@RestController
@RequestMapping("/api/payments/vnpay")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @GetMapping("/ipn")
    public Map<String, String> ipn(
            @RequestParam MultiValueMap<String, String> requestParams
    ) {
        if (requestParams.values().stream().anyMatch(values -> values.size() != 1)) {
            return Map.of("RspCode", "97", "Message", "Tham số IPN bị lặp");
        }

        String code;
        try {
            code = paymentService.processVnPayIpn(requestParams.toSingleValueMap());
        } catch (RuntimeException ex) {
            log.error("Không thể xử lý kết quả thanh toán VNPay", ex);
            code = "99";
        }

        String message = switch (code) {
            case "00" -> "Đã ghi nhận kết quả giao dịch";
            case "01" -> "Không tìm thấy giao dịch";
            case "02" -> "Giao dịch đã được xác nhận";
            case "04" -> "Số tiền không khớp";
            case "97" -> "Chữ ký không hợp lệ";
            default -> "Chưa thể xác nhận giao dịch";
        };

        return Map.of("RspCode", code, "Message", message);
    }
}