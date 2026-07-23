package org.eardream.devvault.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmailDomainValidatorTest {

    @Test
    void acceptsDomainWithMailExchange() {
        assertDoesNotThrow(() -> new EmailDomainValidator(domain -> true).validate("user@example.kr"));
    }

    @Test
    void rejectsReservedAndDomainsWithoutMailExchange() {
        EmailDomainValidator validator = new EmailDomainValidator(domain -> false);

        assertThrows(ResponseStatusException.class, () -> validator.validate("user@example.com"));
        assertThrows(ResponseStatusException.class, () -> validator.validate("kimchi@yahoo.jp"));
    }
}
