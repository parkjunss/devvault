package org.eardream.devvault.file;

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
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class FileManagementIntegrationTest {
    @TempDir static Path uploads;
    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("app.storage.location", () -> uploads.toString());
    }
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void preservesCopiesAndValidatesBulkDeletionThenRestoresFilesAfterFolderDeletion() throws Exception {
        String owner = "file-management@example.com";
        users.saveAndFlush(User.builder().email(owner).password("unused").username("File test").build());
        long folder = json.readTree(mvc.perform(post("/api/folders").with(jwt().jwt(j -> j.subject(owner)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Editable\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long original = upload(owner, folder, "original.txt", "original bytes");
        long copy = upload(owner, folder, "edited.txt", "edited bytes");
        mvc.perform(get("/api/files/" + original + "/download").with(jwt().jwt(j -> j.subject(owner))))
                .andExpect(status().isOk()).andExpect(content().string("original bytes"));
        mvc.perform(delete("/api/folders/" + folder).with(jwt().jwt(j -> j.subject(owner))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/files/bulk-delete").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileIds\":[" + original + "]}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/files/bulk-delete").with(jwt().jwt(j -> j.subject("other@example.com")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fileIds\":[" + original + "]}"))
                .andExpect(status().isNotFound());
        for (String ids : new String[]{"[]", "[null]", "[0]", "[-1]"}) {
            mvc.perform(post("/api/files/bulk-delete").with(jwt().jwt(j -> j.subject(owner)))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"fileIds\":" + ids + "}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/files/bulk-delete").with(jwt().jwt(j -> j.subject(owner)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fileIds\":[" + original + ",999999999]}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/files/" + original).with(jwt().jwt(j -> j.subject(owner))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/files/bulk-delete").with(jwt().jwt(j -> j.subject(owner)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fileIds\":[" + original + "," + copy + "]}"))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/folders/" + folder).with(jwt().jwt(j -> j.subject(owner))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/files/" + original + "/restore").with(jwt().jwt(j -> j.subject(owner))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/files/" + original).with(jwt().jwt(j -> j.subject(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.folderId").isEmpty());
        mvc.perform(get("/api/files/" + original + "/download").with(jwt().jwt(j -> j.subject(owner))))
                .andExpect(content().string("original bytes"));
    }

    private long upload(String owner, long folder, String name, String body) throws Exception {
        return json.readTree(mvc.perform(multipart("/api/files")
                .file(new MockMultipartFile("file", name, "text/plain", body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .param("folderId", Long.toString(folder)).with(jwt().jwt(j -> j.subject(owner))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
