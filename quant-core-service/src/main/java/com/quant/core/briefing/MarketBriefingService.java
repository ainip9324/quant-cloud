package com.quant.core.briefing;

import com.quant.common.auth.AuthenticatedPrincipal;
import com.quant.core.mapper.MarketBriefingMapper;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
public class MarketBriefingService {
    private static final int MAX_TEXT_LENGTH = 1_000;
    private final MarketBriefingMapper mapper;

    public MarketBriefingService(MarketBriefingMapper mapper) {
        this.mapper = mapper;
    }

    public MarketBriefing get() {
        MarketBriefing current = mapper.selectCurrent();
        if (current == null) {
            throw new DataRetrievalFailureException("Missing market briefing row");
        }
        return current;
    }

    public MarketBriefing publish(Update command, AuthenticatedPrincipal actor) {
        long expectedRevision = command.expectedRevision();
        if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid expected briefing revision");
        }
        MarketBriefing next = new MarketBriefing(
                normalize(command.outlook()), normalize(command.events()), normalize(command.risks()),
                expectedRevision + 1, System.currentTimeMillis());
        if (mapper.updateVersion(next, actor.userId(), expectedRevision) == 0) {
            throw new OptimisticLockingFailureException("Market briefing version changed");
        }
        return next;
    }

    private static String normalize(String value) {
        if (value == null || value.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Invalid market briefing text");
        }
        return value.strip();
    }

    public record Update(long expectedRevision, String outlook, String events, String risks) {
    }
}
