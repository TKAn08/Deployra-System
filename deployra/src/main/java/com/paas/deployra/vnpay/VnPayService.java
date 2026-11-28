/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.paas.deployra.vnpay;

import com.paas.deployra.account.Payment;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 *
 * @author ADMIN
 */
@Service
public class VnPayService {

    private static final DateTimeFormatter DATE_FORMAT
            = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT);

    private final String paymentUrl;
    private final String tmnCode;
    private final String hashSecret;
    private final String returnUrl;
    private final Clock clock;

    public VnPayService(
            @Value("${deployra.vnpay.payment-url}") String paymentUrl,
            @Value("${deployra.vnpay.tmn-code}") String tmnCode,
            @Value("${deployra.vnpay.hash-secret}") String hashSecret,
            @Value("${deployra.vnpay.return-url}") String returnUrl,
            Clock clock) {
        this.paymentUrl = paymentUrl;
        this.tmnCode = tmnCode;
        this.hashSecret = hashSecret;
        this.returnUrl = returnUrl;
        this.clock = clock;
    }

    public String createPaymentUrl(Payment payment, String clientIp) {
        long amount = payment.getAmount().longValueExact();
        long vnpAmount = Math.multiplyExact(amount, 100L);
        ZonedDateTime now = clock.instant().atZone(ZoneOffset.ofHours(7));

        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", tmnCode);
        params.put("vnp_Amount", Long.toString(vnpAmount));
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_TxnRef", payment.getId().toString());
        params.put("vnp_OrderInfo", "Thanh toan goi Deployra " + payment.getId());
        params.put("vnp_OrderType", "other");
        params.put("vnp_Locale", "vn");
        params.put("vnp_ReturnUrl", returnUrl);
        params.put("vnp_IpAddr", clientIp);
        params.put("vnp_CreateDate", now.format(DATE_FORMAT));
        params.put("vnp_ExpireDate", now.plusMinutes(15).format(DATE_FORMAT));
//        params.put("vnp_BankCode", "VNPAYEWALLET");
        String query = buildQuery(params);

        return paymentUrl + "?" + query + "&vnp_SecureHash=" + sign(query);
    }

    private String buildQuery(Map<String, String> params) {
        return new TreeMap<>(params).entrySet().stream()
                .map(entry -> entry.getKey() + "="
                + URLEncoder.encode(
                        entry.getValue(), StandardCharsets.US_ASCII))
                .collect(Collectors.joining("&"));
    }

    private String sign(String query) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(hashSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(query.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Không thể ký URL thanh toán VNPay", ex);
        }
    }

    public boolean verifyIpn(Map<String, String> params) {
        String receivedHash = params.get("vnp_SecureHash");

        if (receivedHash == null || !receivedHash.matches("[0-9a-fA-F]{128}") || !tmnCode.equals(params.get("vnp_TmnCode"))) {
            return false;
        }

        Map<String, String> signedParams = new TreeMap<>();

        for (Map.Entry<String, String> entry : params.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();

            if (name.startsWith("vnp_") && !"vnp_SecureHash".equals(name) && !value.isEmpty()) {
                signedParams.put(name, value);
            }
        }

        byte[] expected = sign(buildQuery(signedParams))
                .getBytes(StandardCharsets.US_ASCII);
        byte[] received = receivedHash.toLowerCase()
                .getBytes(StandardCharsets.US_ASCII);

        return MessageDigest.isEqual(expected, received);
    }
}
