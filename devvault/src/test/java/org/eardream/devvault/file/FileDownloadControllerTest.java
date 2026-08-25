package org.eardream.devvault.file;

import org.eardream.devvault.file.controller.FileController;
import org.eardream.devvault.file.entity.StoredFile;
import org.eardream.devvault.file.service.FileAccessTokenService;
import org.eardream.devvault.file.service.FileStorageService;
import org.eardream.devvault.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FileDownloadControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void downloadsOneFileDirectlyFromSignedUrl() throws Exception {
        Path path = Files.writeString(tempDir.resolve("stored-one"), "tablet");
        FileStorageService files = mock(FileStorageService.class);
        FileAccessTokenService tokens = mock(FileAccessTokenService.class);
        when(tokens.verifyDownload("signed-token"))
                .thenReturn(new FileAccessTokenService.DownloadGrant("user@example.com", List.of(7L)));
        when(files.downloads("user@example.com", List.of(7L)))
                .thenReturn(List.of(download(7L, "report.pdf", path)));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new FileController(files, tokens, false, "/__devvault_files/")).build();

        var pending = mvc.perform(get("/api/files/download/signed-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("report.pdf")))
                .andReturn();
        mvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().string("tablet"));
    }

    @Test
    void streamsSeveralFilesAsZipFromSignedUrl() throws Exception {
        Path first = Files.writeString(tempDir.resolve("stored-one"), "first");
        Path second = Files.writeString(tempDir.resolve("stored-two"), "second");
        FileStorageService files = mock(FileStorageService.class);
        FileAccessTokenService tokens = mock(FileAccessTokenService.class);
        List<Long> ids = List.of(7L, 9L);
        when(tokens.verifyDownload("signed-token"))
                .thenReturn(new FileAccessTokenService.DownloadGrant("user@example.com", ids));
        when(files.downloads("user@example.com", ids)).thenReturn(List.of(
                download(7L, "same.txt", first), download(9L, "same.txt", second)));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new FileController(files, tokens, false, "/__devvault_files/")).build();

        var pending = mvc.perform(get("/api/files/download/signed-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("devvault-files.zip")))
                .andExpect(content().contentType("application/zip"))
                .andReturn();
        byte[] body = mvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(body))) {
            assertEquals("same.txt", zip.getNextEntry().getName());
            assertEquals("first", new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("same (2).txt", zip.getNextEntry().getName());
            assertEquals("second", new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private FileStorageService.StoredDownload download(long id, String name, Path path) throws Exception {
        StoredFile file = StoredFile.builder().id(id)
                .owner(User.builder().email("user@example.com").password("pw").username("User").build())
                .originalName(name).storedName(path.getFileName().toString())
                .contentType("text/plain").size(Files.size(path)).checksum("checksum").build();
        return new FileStorageService.StoredDownload(file, path);
    }
}
