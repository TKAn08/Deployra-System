package com.paas.deployra.api;

import com.paas.deployra.account.User;
import com.paas.deployra.account.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.paas.deployra.account.AuthService;
import com.paas.deployra.account.JwtService;
import com.paas.deployra.account.RevokedTokenRepository;

class AuthControllerTest {

    private UserRepository users;
    private BCryptPasswordEncoder encoder;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        encoder = new BCryptPasswordEncoder();

        AuthService authService = new AuthService(
                users,
                encoder,
                mock(JwtService.class),
                mock(RevokedTokenRepository.class));

        mvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .build();
    }

    private static String body(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"firstName\":\"An\",\"lastName\":\"Nguyen\"}";
    }

    @Test
    void registerCreatesUserWithHashedPasswordAndLowercaseEmail() throws Exception {
        when(users.existsByEmail("new@example.com")).thenReturn(false);
        when(users.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(7L);
            return u;
        });

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(body("New@Example.com", "matkhau123")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.email").value("new@example.com"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertEquals("new@example.com", saved.getValue().getEmail());
        assertNotEquals("matkhau123", saved.getValue().getPassword());
        assertTrue(encoder.matches("matkhau123", saved.getValue().getPassword()));
    }

    @Test
    void duplicateEmailReturns409() throws Exception {
        when(users.existsByEmail("pro.user@example.com")).thenReturn(true);

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(body("pro.user@example.com", "matkhau123")))
                .andExpect(status().isConflict());

        verify(users, never()).saveAndFlush(any(User.class));
    }

    @Test
    void shortPasswordReturns400() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(body("new@example.com", "short")))
                .andExpect(status().isBadRequest());

        verify(users, never()).saveAndFlush(any(User.class));
    }
}
