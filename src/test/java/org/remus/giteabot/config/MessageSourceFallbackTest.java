package org.remus.giteabot.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.MessageSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * English lives in {@code messages.properties} and has no {@code messages_en} file. With Spring Boot's
 * default {@code fallback-to-system-locale=true}, a lookup for "en" picks the bundle of the server's own
 * locale before the base file, so an English user saw German on a server running with de_DE.
 */
class MessageSourceFallbackTest {

    private Locale systemLocale;

    @BeforeEach
    void runOnAGermanServer() {
        systemLocale = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
    }

    @AfterEach
    void restoreSystemLocale() {
        Locale.setDefault(systemLocale);
    }

    @Test
    void englishUser_onAGermanServer_getsEnglish() throws IOException {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MessageSourceAutoConfiguration.class))
                .withPropertyValues(messagePropertiesOfTheApplication())
                .run(context -> assertThat(context.getBean(MessageSource.class)
                        .getMessage("flash.botNotFound", null, Locale.ENGLISH)).isEqualTo("Bot not found"));
    }

    private static String[] messagePropertiesOfTheApplication() throws IOException {
        Properties application = new Properties();
        try (InputStream in = MessageSourceFallbackTest.class.getResourceAsStream("/application.properties")) {
            application.load(in);
        }
        return application.stringPropertyNames().stream()
                .filter(key -> key.startsWith("spring.messages."))
                .map(key -> key + "=" + application.getProperty(key))
                .toArray(String[]::new);
    }
}
