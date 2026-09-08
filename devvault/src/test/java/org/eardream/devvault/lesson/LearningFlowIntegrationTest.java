package org.eardream.devvault.lesson;

import org.eardream.devvault.user.entity.User;
import org.eardream.devvault.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class LearningFlowIntegrationTest {
    @TempDir static Path uploads;
    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("app.storage.location", () -> uploads.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void persistsWrittenAndUploadedLessonsAndEnforcesOwnership() throws Exception {
        String owner = "learning-flow@example.com";
        users.saveAndFlush(User.builder().email(owner).password("unused").username("Learning test").build());
        long course = id(mvc.perform(post("/api/courses").with(jwt().jwt(j -> j.subject(owner)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Integration course\"}"))
                .andExpect(status().isCreated()).andReturn());
        long section = id(mvc.perform(post("/api/courses/" + course + "/sections")
                .with(jwt().jwt(j -> j.subject(owner))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Week 1\",\"orderNo\":1}"))
                .andExpect(status().isCreated()).andReturn());
        long written = id(mvc.perform(post("/api/sections/" + section + "/lessons")
                .with(jwt().jwt(j -> j.subject(owner))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Written\",\"contentMd\":\"# Notes\",\"orderNo\":1}"))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(get("/api/lessons/" + written).with(jwt().jwt(j -> j.subject(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contentMd").value("# Notes"));
        long file = id(mvc.perform(multipart("/api/files")
                .file(new MockMultipartFile("file", "lesson.txt", "text/plain",
                        "학습 자료 테스트".getBytes(StandardCharsets.UTF_8)))
                .with(jwt().jwt(j -> j.subject(owner))))
                .andExpect(status().isCreated()).andReturn());
        long uploaded = id(mvc.perform(post("/api/sections/" + section + "/lessons/from-file")
                .with(jwt().jwt(j -> j.subject(owner))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Uploaded\",\"storedFileId\":" + file + ",\"orderNo\":2}"))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(get("/api/lessons/" + uploaded).with(jwt().jwt(j -> j.subject(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contentMd").value("학습 자료 테스트"))
                .andExpect(jsonPath("$.sourceFileId").value(file));
        mvc.perform(get("/api/sections/" + section + "/lessons").with(jwt().jwt(j -> j.subject(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/lessons/" + uploaded).with(jwt().jwt(j -> j.subject("other@example.com"))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/courses")).andExpect(status().isUnauthorized());
    }

    private long id(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
}
