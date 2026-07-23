package org.eardream.devvault.auth.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import javax.naming.NamingEnumeration;
import java.net.IDN;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

@Component
public class EmailDomainValidator {
    private static final Set<String> RESERVED_DOMAINS = Set.of(
            "example.com", "example.net", "example.org", "invalid", "localhost", "test");

    private final Predicate<String> hasMailExchange;

    public EmailDomainValidator() {
        this(EmailDomainValidator::lookupMailExchange);
    }

    EmailDomainValidator(Predicate<String> hasMailExchange) {
        this.hasMailExchange = hasMailExchange;
    }

    public void validate(String email) {
        int separator = email == null ? -1 : email.lastIndexOf('@');
        if (separator < 1 || separator == email.length() - 1) {
            throw invalidEmailDomain();
        }
        String domain;
        try {
            domain = IDN.toASCII(email.substring(separator + 1).trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalidEmailDomain();
        }
        boolean reserved = RESERVED_DOMAINS.stream()
                .anyMatch(value -> domain.equals(value) || domain.endsWith("." + value));
        if (reserved || !hasMailExchange.test(domain)) {
            throw invalidEmailDomain();
        }
    }

    private static boolean lookupMailExchange(String domain) {
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        environment.put("com.sun.jndi.dns.timeout.initial", "1500");
        environment.put("com.sun.jndi.dns.timeout.retries", "1");
        InitialDirContext context = null;
        try {
            context = new InitialDirContext(environment);
            Attributes attributes = context.getAttributes(domain, new String[]{"MX"});
            Attribute mx = attributes.get("MX");
            if (mx == null) {
                return false;
            }
            NamingEnumeration<?> records = mx.getAll();
            while (records.hasMore()) {
                String record = records.next().toString().trim();
                String target = record.substring(record.lastIndexOf(' ') + 1);
                if (!target.equals(".")) {
                    return true;
                }
            }
            return false;
        } catch (Exception exception) {
            return false;
        } finally {
            if (context != null) {
                try {
                    context.close();
                } catch (Exception ignored) {
                    // DNS 조회 결과와 무관한 close 실패는 무시한다.
                }
            }
        }
    }

    private static ResponseStatusException invalidEmailDomain() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "메일을 받을 수 있는 실제 이메일 주소를 입력해 주세요.");
    }
}
