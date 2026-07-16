package org.eardream.devvault;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DevvaultApplicationTest {

    @Test
    void dotenvDoesNotOverrideSystemProperty() {
        String key = "DEVVAULT_DOTENV_TEST";
        System.setProperty(key, "system");

        try {
            DevvaultApplication.setDefaultProperty(key, "dotenv");
            assertEquals("system", System.getProperty(key));
        } finally {
            System.clearProperty(key);
        }
    }
}
