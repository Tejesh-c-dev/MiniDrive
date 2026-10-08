
package com.minidrive.controller;

import com.minidrive.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import org.springframework.http.MediaType;

import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void cleanup() {
        userRepository.deleteAll();
    }

    @Test
    void registerShouldCreateUser() throws Exception {

        mockMvc.perform(
                post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Tejesh\",\"email\":\"tejesh@test.com\",\"password\":\"password123\"}")
        )
                .andExpect(status().isCreated());
    }

    @Test
    void duplicateEmailShouldFail() throws Exception {

        // First registration should succeed
        mockMvc.perform(
                post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Tejesh\",\"email\":\"tejesh@test.com\",\"password\":\"password123\"}")
        )
                .andExpect(status().isCreated());

        // Second registration with the same email should fail
        mockMvc.perform(
                post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Tejesh\",\"email\":\"tejesh@test.com\",\"password\":\"password123\"}")
        )
                .andExpect(status().isBadRequest());
    }

    @Test
    void protectedEndpointShouldRejectUnauthenticatedRequest()
            throws Exception {

        mockMvc.perform(
                get("/api/auth/me")
        )
                .andExpect(status().isUnauthorized());
    }
}
