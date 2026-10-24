package com.minidrive.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=${REDIS_PORT:56379}",
        "minidrive.rate-limit.registration.limit=2",
        "minidrive.rate-limit.login.ip-limit=500",
        "minidrive.rate-limit.login.account-limit=1",
        "minidrive.rate-limit.share.limit=2"})
@AutoConfigureMockMvc
class RateLimitControllerIntegrationTest {
    @Autowired MockMvc mvc;
    private final String ip="192.0.2."+(1+(int)(Math.random()*200));

    @Test void registrationIsAllowedToLimitThenReturns429AndRetryAfter() throws Exception {
        for (int i=0;i<2;i++) mvc.perform(register("limit"+i+"@example.test").with(r-> { r.setRemoteAddr(ip); return r; }))
                .andExpect(status().isCreated());
        mvc.perform(register("third@example.test").with(r-> { r.setRemoteAddr(ip); r.addHeader("X-Forwarded-For","203.0.113.77"); return r; }))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value("Too many requests"));
    }

    @Test void failedLoginIsCountedAndAccountBucketNormalizesWithoutVictimWideLockout() throws Exception {
        mvc.perform(login("Victim@example.test").with(r-> { r.setRemoteAddr(ip); return r; }))
                .andExpect(status().isUnauthorized());
        mvc.perform(login("victim@example.test").with(r-> { r.setRemoteAddr(ip); return r; }))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        String otherIp="198.51.100."+(1+(int)(Math.random()*200));
        mvc.perform(login("victim@example.test").with(r-> { r.setRemoteAddr(otherIp); return r; }))
                .andExpect(status().isUnauthorized());
    }

    @Test void invalidPublicShareGuessesAreThrottledAndAuthenticatedRoutesAreUnmetered() throws Exception {
        for (int i=0;i<2;i++) mvc.perform(get("/api/share/invalid-"+i).with(r-> { r.setRemoteAddr(ip); return r; }))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/share/invalid-3").with(r-> { r.setRemoteAddr(ip); return r; }))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        String authIp="192.0.2."+(1+(int)(Math.random()*200));
        String response=mvc.perform(register("authenticated@example.test")
                .with(r-> { r.setRemoteAddr(authIp); return r; }))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token=com.jayway.jsonpath.JsonPath.read(response,"$.token");
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token)
                .with(r-> { r.setRemoteAddr(authIp); return r; })).andExpect(status().isOk());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder register(String email) {
        return post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Limit Test\",\"email\":\""+email+"\",\"password\":\"password123\"}");
    }
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(String email) {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\""+email+"\",\"password\":\"wrong-password\"}");
    }
}
