package com.paas.deployra.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AdminProperties {

    private final Set<String> emails;

    public AdminProperties(
            @Value("${deployra.admin.emails:${DEPLOYRA_ADMIN_EMAILS:demo@deployra.local}}") String emails) {
        this.emails = Arrays.stream(emails.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAdmin(String email) {
        return email != null && emails.contains(email.toLowerCase(Locale.ROOT));
    }
}