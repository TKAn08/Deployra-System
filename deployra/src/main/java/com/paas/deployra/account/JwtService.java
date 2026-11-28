/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.paas.deployra.account;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import java.time.Clock;
import javax.crypto.SecretKey;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.Date;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 *
 * @author ADMIN
 */
@Service
public class JwtService {

    private final long expirationSc;
    private final Clock clock;
    private final SecretKey key;
    private final JwtParser parser;

    public JwtService(@Value("${deployra.jwt.secret}") String secret,
            @Value("${deployra.jwt.expiration-seconds}") long expirationSc,
            Clock clock) {
        this.key = Keys.hmacShaKeyFor(
                Base64.getDecoder().decode(secret)
        );
        this.clock = clock;
        this.parser = Jwts.parser().verifyWith(key).build();
        this.expirationSc = expirationSc;
    }

    public String generateToken(User user) {
        Instant now = clock.instant();

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getId().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSc)))
                .signWith(key)
                .compact();
    }

    public Claims  parse(String token) {
        return parser.parseSignedClaims(token).getPayload();
    }

    public long getExpirationSeconds() {
        return expirationSc;
    }
}
