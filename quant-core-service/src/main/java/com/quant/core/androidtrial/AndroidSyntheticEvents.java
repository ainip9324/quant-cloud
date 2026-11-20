package com.quant.core.androidtrial;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Reads the current, operator-owned test event from a single replaceable file. */
@Component
@ConditionalOnProperty(name = "android-synthetic.enabled", havingValue = "true")
class AndroidSyntheticEvents {
    private static final int MAX_FILE_BYTES = 1024;
    private static final long TTL_MS = 120_000;
    private static final Path EVENT_FILE = Path.of("/run/quant-android-synthetic/event.json");
    private final Set<Long> accounts;
    private final Path eventFile;
    private final Clock clock;
    private final ObjectReader reader;

    @Autowired
    AndroidSyntheticEvents(@Value("${android-synthetic.allowed-user-ids:}") String accountIds, ObjectMapper mapper) {
        this(accountIds, mapper, EVENT_FILE, Clock.systemUTC());
    }

    AndroidSyntheticEvents(String accountIds, ObjectMapper mapper, Path eventFile, Clock clock) {
        this.accounts = accounts(accountIds);
        this.eventFile = eventFile;
        this.clock = clock;
        this.reader = mapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    }

    boolean allows(long account) { return accounts.contains(account); }

    Frame next(long account, Collection<Long> sent) {
        if (!allows(account) || !Files.isRegularFile(eventFile, LinkOption.NOFOLLOW_LINKS)) return null;
        try (var input = Files.newInputStream(eventFile, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) return null;
            JsonNode event = reader.readValue(bytes);
            if (event == null || !event.isObject() || !"TEST".equals(event.path("marker").textValue())) return null;
            long id = positiveLong(event.path("id"));
            long confirmedAt = positiveLong(event.path("confirmed_at_ms"));
            long now = clock.millis();
            if (positiveLong(event.path("user_id")) != account || id == 0 || sent.contains(id)
                    || confirmedAt <= now - TTL_MS || confirmedAt > now) return null;
            return new Frame(Long.toString(id), "CONFIRMED", "TEST", "1m", "LONG", confirmedAt, confirmedAt + TTL_MS);
        } catch (IOException unreadable) {
            // Missing, replaced, or malformed files leave the stream at idle heartbeat.
            return null;
        }
    }

    private static long positiveLong(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() ? Math.max(0, value.longValue()) : 0;
    }

    private static Set<Long> accounts(String configured) {
        if (configured.isBlank()) return Set.of();
        var accounts = new HashSet<Long>();
        for (String part : configured.split(",", -1)) {
            String value = part.strip();
            try {
                if (!value.matches("[1-9][0-9]*")) throw new NumberFormatException();
                accounts.add(Long.parseLong(value));
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Android test allowlist requires comma-separated positive account IDs");
            }
        }
        return Set.copyOf(accounts);
    }

    record Frame(String id, String kind, String symbol, String interval, String direction,
                 @JsonProperty("confirmed_at_ms") long confirmedAt, @JsonProperty("expires_at_ms") long expiresAt) { }
}
