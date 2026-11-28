/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.paas.deployra.api.dto;

import com.paas.deployra.account.User.Role;
import lombok.Builder;

/**
 *
 * @author ADMIN
 */
@Builder
public record LoginResponse(
        String accessToken,
        long expiresIn,
        Role role
) {}
