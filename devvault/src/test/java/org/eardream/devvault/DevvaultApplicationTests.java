package org.eardream.devvault;

import org.eardream.devvault.file.StoredFileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class DevvaultApplicationTests {
    @Autowired
    StoredFileRepository storedFileRepository;

    @Test
    void contextLoads() {
    }

    @Test
    void executesCombinedFileSearchQuery() {
        var result = storedFileRepository.search(
                "missing-user@example.com", "report", "pdf", "java", true, PageRequest.of(0, 20));

        assertTrue(result.isEmpty());
    }

    @Test
    void aggregatesEmptyDashboardUsage() {
        var result = storedFileRepository.summarizeActiveUsage("missing-user@example.com");

        assertEquals(0L, result.getFileCount());
        assertEquals(0L, result.getUsedBytes());
    }

}
