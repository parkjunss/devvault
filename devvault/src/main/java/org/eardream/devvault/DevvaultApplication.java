package org.eardream.devvault;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DevvaultApplication {

    public static void main(String[] args) {
        Dotenv.configure()
                .ignoreIfMissing()
                .load()
                .entries()
                .forEach(entry -> setDefaultProperty(entry.getKey(), entry.getValue()));

        SpringApplication.run(DevvaultApplication.class, args);
    }

    static void setDefaultProperty(String key, String value) {
        if (System.getenv(key) == null) {
            System.getProperties().putIfAbsent(key, value);
        }
    }

}
