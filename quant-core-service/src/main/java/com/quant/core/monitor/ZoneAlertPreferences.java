package com.quant.core.monitor;

import com.quant.common.auth.AuthenticatedPrincipal;
import com.quant.common.auth.UserRole;
import jakarta.mail.internet.InternetAddress;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "zone-alerts.enabled", havingValue = "true")
public class ZoneAlertPreferences {
    private final JdbcTemplate db;
    private final ZoneAlertMail mail;

    public ZoneAlertPreferences(JdbcTemplate db, ZoneAlertMail mail) {
        this.db = db;
        this.mail = mail;
    }

    public record Settings(boolean enabled, boolean emailEnabled, String email, boolean emailEditable) {}
    public record Update(Boolean enabled, Boolean emailEnabled, String email) {}

    public Settings read(AuthenticatedPrincipal account) {
        long user = account.userId();
        boolean editable = account.role() == UserRole.ADMIN;
        return db.query("SELECT enabled, email_enabled, email FROM zone_alert_preference WHERE user_id=?", rs -> {
            if (!rs.next()) return new Settings(true, mail.configuredRecipient(user) != null, mail.configuredRecipient(user), editable);
            String address = rs.getString("email");
            return new Settings(rs.getBoolean("enabled"), rs.getBoolean("email_enabled"),
                    address == null ? mail.configuredRecipient(user) : address, editable);
        }, user);
    }

    public Settings save(AuthenticatedPrincipal account, Update update) {
        if (update.enabled() == null || update.emailEnabled() == null) throw MarketMonitorException.invalid("Missing alert settings");
        boolean editable = account.role() == UserRole.ADMIN;
        String address = editable ? validatedAddress(update.email()) : null;
        String recipient = address == null ? mail.configuredRecipient(account.userId()) : address;
        if (update.emailEnabled() && recipient == null) throw MarketMonitorException.invalid("Missing alert email address");
        db.update("""
                INSERT INTO zone_alert_preference (user_id, enabled, email_enabled, email, enabled_since)
                VALUES (?, ?, ?, ?, 0) AS desired
                ON DUPLICATE KEY UPDATE
                  enabled_since=IF(zone_alert_preference.enabled=FALSE AND desired.enabled=TRUE, ?, zone_alert_preference.enabled_since),
                  enabled=desired.enabled, email_enabled=desired.email_enabled, email=desired.email
                """, account.userId(), update.enabled(), update.emailEnabled(), address, System.currentTimeMillis());
        return read(account);
    }

    private static String validatedAddress(String value) {
        if (value == null || value.isBlank()) return null;
        String address = value.strip();
        try {
            var parsed = new InternetAddress(address, true);
            parsed.validate();
            if (address.length() <= 254 && address.equals(parsed.getAddress()) && parsed.getPersonal() == null) return address;
        } catch (jakarta.mail.internet.AddressException ignored) { /* Reject invalid destinations. */ }
        throw MarketMonitorException.invalid("Invalid alert email address");
    }
}
