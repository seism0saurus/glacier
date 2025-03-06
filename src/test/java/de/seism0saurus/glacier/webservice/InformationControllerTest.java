package de.seism0saurus.glacier.webservice;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import social.bigbone.MastodonClient;

@SpringBootTest
@AutoConfigureMockMvc
class InformationControllerTest {

    @Autowired
    private MockMvc mockMvc;
    /**
     * MastodonClient needs to be mocked because it directly tests the connection to a nonexistent webservice.
     */
    @SuppressWarnings("unused")
    @MockitoBean
    private MastodonClient mastodonClient;

    @Test
    void testReadCookieWithExistingWallId() throws Exception {
        String existingWallId = "test-existing-wall-id";

        mockMvc.perform(MockMvcRequestBuilders.get("/rest/wall-id")
                        .cookie(new Cookie("wallId", existingWallId)))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.jsonPath("$.id").value(existingWallId));
    }

    @Test
    void testReadCookieWhenWallIdIsNotPresent() throws Exception {

        mockMvc.perform(MockMvcRequestBuilders.get("/rest/wall-id"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.jsonPath("$.id").isNotEmpty())
                .andExpect(MockMvcResultMatchers.cookie().exists("wallId"));
    }

    @Test
    void testGetMastodonHandle() throws Exception {

        mockMvc.perform(MockMvcRequestBuilders.get("/rest/mastodon-handle"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.jsonPath("$.name").value("the_handle_of_the_account_from_my_acces_key@my_instance"));
    }

    @Test
    void testGetInstanceOperator() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/rest/operator"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.jsonPath("$.domain").value("example.com"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.operatorName").value("Jon Doe"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.operatorStreetAndNumber").value("somewhere 1"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.operatorZipcode").value("12345"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.operatorCity").value("somecity"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.operatorCountry").value("Germany"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.operatorPhone").value("+123456789"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.operatorMail").value("mail@example.com"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.operatorWebsite").value("example.com"));
    }
}