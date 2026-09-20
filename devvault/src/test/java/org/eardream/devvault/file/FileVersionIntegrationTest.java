package org.eardream.devvault.file;

import org.eardream.devvault.user.entity.User;
import org.eardream.devvault.user.repository.UserRepository;
import org.eardream.devvault.file.repository.StoredFileRepository;
import org.eardream.devvault.file.repository.FileRevisionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class FileVersionIntegrationTest {
    @TempDir static Path uploads;
    @DynamicPropertySource static void storage(DynamicPropertyRegistry r) { r.add("app.storage.location", () -> uploads.toString()); }
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired StoredFileRepository files;
    @Autowired FileRevisionRepository revisions;
    @Autowired org.eardream.devvault.file.service.FileStorageService storage;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final ObjectMapper json = new ObjectMapper();

    private String owner(String prefix) {
        String email = prefix + "@versions.test";
        users.saveAndFlush(User.builder().email(email).username(prefix).password("unused").build());
        return email;
    }
    private MockMultipartFile data(String text) { return new MockMultipartFile("file", "sample.pdf", "application/pdf", text.getBytes(StandardCharsets.UTF_8)); }
    private long upload(String owner, String text) throws Exception {
        return json.readTree(mvc.perform(multipart("/api/files").file(data(text)).with(jwt().jwt(j -> j.subject(owner))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
    private int save(String owner, long id, long expected, String text) throws Exception {
        return mvc.perform(multipart("/api/files/" + id + "/versions").file(data(text)).param("expectedVersion", "" + expected)
                .with(jwt().jwt(j -> j.subject(owner)))).andReturn().getResponse().getStatus();
    }
    private long physicalCount() throws Exception { try(var paths = Files.list(uploads)) { return paths.count(); } }

    @Test void savesOneFilePreservesOriginalRestoresAsNewVersionAndDeletesAllBytes() throws Exception {
        String owner = owner("workflow"), original = "%PDF-1.7 original", edit = "%PDF-1.7 edited";
        long id = upload(owner, original);
        assertEquals(200, save(owner, id, 1, edit));
        assertEquals(1, files.findAllByOwnerId(users.findByEmail(owner).orElseThrow().getId()).size());
        assertEquals(original.length() + edit.length(), files.sumStoredBytes(owner));
        assertEquals(1, revisions.findAllByFileIdOrderByVersionDesc(id).size());
        mvc.perform(multipart("/api/files").file(data(original)).with(jwt().jwt(j -> j.subject(owner)))).andExpect(status().isConflict());
        mvc.perform(get("/api/files/" + id + "/download").with(jwt().jwt(j -> j.subject(owner))))
                .andExpect(content().string(edit));
        mvc.perform(get("/api/files/" + id + "/versions").with(jwt().jwt(j -> j.subject(owner))))
                .andExpect(jsonPath("$[0].version").value(2)).andExpect(jsonPath("$[0].current").value(true))
                .andExpect(jsonPath("$[1].version").value(1));
        long count = physicalCount();
        assertEquals(409, save(owner, id, 1, "%PDF-1.7 stale"));
        assertEquals(count, physicalCount());
        mvc.perform(post("/api/files/" + id + "/versions/1/restore").with(jwt().jwt(j -> j.subject(owner)))
                .contentType("application/json").content("{\"expectedVersion\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.version").value(3));
        mvc.perform(get("/api/files/" + id + "/download").with(jwt().jwt(j -> j.subject(owner))))
                .andExpect(content().string(original));
        assertEquals(original.length() * 2 + edit.length(), files.sumStoredBytes(owner));
        assertEquals(200, save(owner, id, 3, original)); // identical content is a no-op
        assertEquals(3, files.findById(id).orElseThrow().getVersion());
        mvc.perform(delete("/api/files/" + id).with(jwt().jwt(j -> j.subject(owner)))).andExpect(status().isNoContent());
        assertEquals(404, save(owner, id, 3, "%PDF-deleted"));
        mvc.perform(get("/api/files/" + id + "/versions").with(jwt().jwt(j -> j.subject(owner)))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/files/" + id + "/permanent").with(jwt().jwt(j -> j.subject(owner)))).andExpect(status().isNoContent());
        assertTrue(revisions.findAllByFileIdOrderByVersionDesc(id).isEmpty());
        assertEquals(0, files.sumStoredBytes(owner));
        assertEquals(count - 2, physicalCount());
    }

    @Test void validatesOwnershipFormatQuotaAndMissingVersionWithoutChangingCurrentBytes() throws Exception {
        String owner = owner("limits"), stranger = owner("stranger");
        long id = upload(owner, "%PDF-original");
        long count = physicalCount();
        assertEquals(404, save(stranger, id, 1, "%PDF-intruder"));
        assertEquals(400, save(owner, id, 0, "%PDF-invalidversion"));
        assertEquals(400, save(owner, id, 1, "not a PDF"));
        mvc.perform(get("/api/files/" + id + "/versions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/files/" + id + "/versions").with(jwt().jwt(j -> j.subject(stranger)))).andExpect(status().isNotFound());
        mvc.perform(post("/api/files/" + id + "/versions/999/restore").with(jwt().jwt(j -> j.subject(owner)))
                .contentType("application/json").content("{\"expectedVersion\":1}")).andExpect(status().isNotFound());
        User user = users.findByEmail(owner).orElseThrow(); user.updateStorageQuota(14); users.saveAndFlush(user);
        assertEquals(413, save(owner, id, 1, "%PDF-too much"));
        assertEquals(count, physicalCount());
        assertEquals(1, files.findById(id).orElseThrow().getVersion());
        assertTrue(revisions.findAllByFileIdOrderByVersionDesc(id).isEmpty());
    }

    @Test void transactionRollbackRetainsOldBytesAndCleansNewPhysicalFile() throws Exception {
        String owner = owner("transaction"); long id = upload(owner, "%PDF-before");
        long count = physicalCount();
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            storage.saveVersion(owner, id, 1, data("%PDF-rolled-back"));
            status.setRollbackOnly();
        });
        assertEquals(count, physicalCount());
        assertEquals(1, files.findById(id).orElseThrow().getVersion());
        assertTrue(revisions.findAllByFileIdOrderByVersionDesc(id).isEmpty());
        mvc.perform(get("/api/files/" + id + "/download").with(jwt().jwt(j -> j.subject(owner))))
                .andExpect(content().string("%PDF-before"));
    }

    @Test void contentSaveReplacesLatestWithoutGrowingHistoryAndRollsBackSafely() throws Exception {
        String owner = owner("content"); long id = upload(owner, "%PDF-original");
        storage.saveContent(owner, id, 1, data("%PDF-edit-one"));
        String previous = files.findById(id).orElseThrow().getStoredName();
        long count = physicalCount();
        User user = users.findByEmail(owner).orElseThrow(); user.updateStorageQuota(26); users.saveAndFlush(user);
        mvc.perform(multipart("/api/files/" + id + "/content").file(data("%PDF-edit-two"))
                .param("expectedVersion", "2").with(jwt().jwt(j -> j.subject(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        assertFalse(Files.exists(uploads.resolve(previous)));
        assertEquals(count, physicalCount());
        assertEquals(1, revisions.findAllByFileIdOrderByVersionDesc(id).size());
        assertEquals("%PDF-original".length() + "%PDF-edit-two".length(), files.sumStoredBytes(owner));
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            storage.saveContent(owner, id, 3, data("%PDF-failed"));
            status.setRollbackOnly();
        });
        assertEquals(count, physicalCount());
        mvc.perform(get("/api/files/" + id + "/download").with(jwt().jwt(j -> j.subject(owner))))
                .andExpect(content().string("%PDF-edit-two"));
        mvc.perform(multipart("/api/files/" + id + "/content").file(data("%PDF-stale"))
                .param("expectedVersion", "2").with(jwt().jwt(j -> j.subject(owner)))).andExpect(status().isConflict());
    }

    @Test void concurrentSavesAllowOnlyOneWriter() throws Exception {
        String owner = owner("concurrent"); long id = upload(owner, "%PDF-base");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> a = pool.submit(() -> { start.await(); return save(owner, id, 1, "%PDF-edit-a"); });
            Future<Integer> b = pool.submit(() -> { start.await(); return save(owner, id, 1, "%PDF-edit-b"); });
            start.countDown();
            assertEquals(java.util.Set.of(200, 409), java.util.Set.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)));
            assertEquals(2, files.findById(id).orElseThrow().getVersion());
            assertEquals(1, revisions.findAllByFileIdOrderByVersionDesc(id).size());
        } finally { pool.shutdownNow(); }
    }
}
