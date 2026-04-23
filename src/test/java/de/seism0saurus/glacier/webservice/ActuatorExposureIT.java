package de.seism0saurus.glacier.webservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import social.bigbone.MastodonClient;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies actuator endpoint lockdown (SR-7, OWASP A05 Security Misconfiguration).
 *
 * <p>Controls verified:
 * <ul>
 *   <li>{@code /actuator} (default path) returns 404 — path moved (OWASP A05)</li>
 *   <li>{@code /internal/actuator/health} returns 200 (probe available)</li>
 *   <li>{@code /internal/actuator/health} body does not contain memory or config details
 *       (show-details=never)</li>
 *   <li>{@code /internal/actuator/env} returns 404 (env not exposed)</li>
 *   <li>{@code /internal/actuator/beans} returns 404 (beans not exposed)</li>
 *   <li>{@code /internal/actuator/heapdump} returns 404 (process memory not exposed —
 *       OWASP A05; heapdump would reveal in-flight wallIds, cookie values, request
 *       bodies held in the JVM heap)</li>
 *   <li>{@code /internal/actuator/threaddump} returns 404 (thread state not exposed —
 *       OWASP A05; threaddump can reveal wallIds held in stack frames)</li>
 *   <li>{@code /internal/actuator/loggers} returns 404 (log-level management not exposed —
 *       OWASP A05; dynamic elevation to TRACE would defeat D-13 log-hygiene controls)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "management.endpoints.web.exposure.include=health,info",
        "management.endpoints.web.base-path=/internal/actuator",
        "management.endpoint.health.probes.enabled=true",
        "management.endpoint.health.show-details=never"
})
class ActuatorExposureIT {

    @Autowired
    private MockMvc mockMvc;

    /** MastodonClient needs to be mocked because it directly tests the connection to a nonexistent webservice. */
    @SuppressWarnings("unused")
    @MockitoBean
    private MastodonClient mastodonClient;

    // -------------------------------------------------------------------------
    // Default /actuator path must be 404 (moved) — OWASP A05
    // -------------------------------------------------------------------------

    @Test
    void defaultActuatorPath_returns404() throws Exception {
        mockMvc.perform(get("/actuator"))
                .andExpect(status().isNotFound());
    }

    @Test
    void defaultActuatorHealthPath_returns404() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // /internal/actuator/health — available, no sensitive details
    // -------------------------------------------------------------------------

    @Test
    void internalActuatorHealth_returns200() throws Exception {
        mockMvc.perform(get("/internal/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void internalActuatorHealth_bodyDoesNotContainMemoryDetails() throws Exception {
        mockMvc.perform(get("/internal/actuator/health"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void internalActuatorHealth_bodyContainsStatusOnly() throws Exception {
        mockMvc.perform(get("/internal/actuator/health"))
                .andExpect(jsonPath("$.status").exists());
    }

    // -------------------------------------------------------------------------
    // Non-exposed endpoints must not be reachable (OWASP A05)
    // -------------------------------------------------------------------------

    @Test
    void internalActuatorEnv_notExposed_returns404() throws Exception {
        mockMvc.perform(get("/internal/actuator/env"))
                .andExpect(status().isNotFound());
    }

    @Test
    void internalActuatorBeans_notExposed_returns404() throws Exception {
        mockMvc.perform(get("/internal/actuator/beans"))
                .andExpect(status().isNotFound());
    }

    @Test
    void internalActuatorConfigProps_notExposed_returns404() throws Exception {
        mockMvc.perform(get("/internal/actuator/configprops"))
                .andExpect(status().isNotFound());
    }

    @Test
    void internalActuatorMappings_notExposed_returns404() throws Exception {
        mockMvc.perform(get("/internal/actuator/mappings"))
                .andExpect(status().isNotFound());
    }

    @Test
    void internalActuatorHeapdump_notExposed_returns404() throws Exception {
        mockMvc.perform(get("/internal/actuator/heapdump"))
                .andExpect(status().isNotFound());
    }

    @Test
    void internalActuatorThreaddump_notExposed_returns404() throws Exception {
        mockMvc.perform(get("/internal/actuator/threaddump"))
                .andExpect(status().isNotFound());
    }

    @Test
    void internalActuatorLoggers_notExposed_returns404() throws Exception {
        mockMvc.perform(get("/internal/actuator/loggers"))
                .andExpect(status().isNotFound());
    }
}
