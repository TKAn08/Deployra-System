/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.paas.deployra.config;

import com.paas.deployra.account.JwtService;
import com.paas.deployra.account.RevokedTokenRepository;
import com.paas.deployra.account.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 *
 * @author ADMIN
 */
public class JwtFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final RevokedTokenRepository revokedTokenRepository;
    private final UserRepository userRepository;

    public JwtFilter(
            JwtService jwtService,
            RevokedTokenRepository revokedTokenRepository,
            UserRepository userRepository
    ) {
        this.jwtService = jwtService;
        this.revokedTokenRepository = revokedTokenRepository;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.parse(header.substring(7));

                if (!revokedTokenRepository.existsById(claims.getId())) {
                    Long userId = Long.valueOf(claims.getSubject());
                    userRepository.findById(userId)
                            .filter(user -> Boolean.TRUE.equals(user.getIsActive()))
                            .ifPresent(user -> {
                                var authority = new SimpleGrantedAuthority(user.getRole().name());
                                var auth = new UsernamePasswordAuthenticationToken(
                                        userId, null, List.of(authority));
                                SecurityContextHolder.getContext().setAuthentication(auth);
                            });
                }
            } catch (JwtException | IllegalArgumentException e) {
            }
        }

        chain.doFilter(request, response);
    }
}
