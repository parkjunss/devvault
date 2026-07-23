package org.eardream.devvault.file;

import org.eardream.devvault.file.controller.FileController;
import org.eardream.devvault.file.entity.StoredFile;
import org.eardream.devvault.file.service.FileStorageService;
import org.eardream.devvault.file.service.PlaybackTokenService;
import org.eardream.devvault.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FilePlaybackControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void streamsRequestedByteRangeWithoutNginx() throws Exception {
        Path video = tempDir.resolve("stored-video");
        Files.writeString(video, "0123456789");
        StoredFile file = StoredFile.builder().id(42L)
                .owner(User.builder().email("user@example.com").password("pw").username("User").build())
                .originalName("video.mp4").storedName("stored-video")
                .contentType("video/mp4").size(10L).checksum("checksum").build();
        FileStorageService files = mock(FileStorageService.class);
        PlaybackTokenService tokens = mock(PlaybackTokenService.class);
        when(tokens.verify("signed-token"))
                .thenReturn(new PlaybackTokenService.PlaybackGrant("user@example.com", 42L));
        when(files.preview("user@example.com", 42L))
                .thenReturn(new FileStorageService.StoredPreview(file, video, MediaType.parseMediaType("video/mp4")));
        FileController controller = new FileController(files, tokens, false, "/__devvault_files/");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/files/playback/signed-token").header("Range", "bytes=2-5"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 2-5/10"))
                .andExpect(content().string("2345"));
    }
}
