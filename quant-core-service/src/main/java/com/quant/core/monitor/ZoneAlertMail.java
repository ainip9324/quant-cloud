package com.quant.core.monitor;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** SMTP delivery uses only operator-approved recipients, never an arbitrary request-supplied address. */
@Slf4j
@Component
@ConfigurationProperties("zone-alerts.mail")
@ConditionalOnProperty(name = "zone-alerts.enabled", havingValue = "true")
class ZoneAlertMail {
    enum TlsMode {
        STARTTLS,
        IMPLICIT_TLS
    }

    private String host = "";
    private int port = 587;
    private TlsMode tlsMode = TlsMode.STARTTLS;
    private String from = "";
    private String username = "";
    private String password = "";
    private Map<Long, String> recipients = Map.of();

    public void setHost(String host) { this.host = host; }
    public void setPort(int port) { this.port = port; }
    public void setTlsMode(TlsMode tlsMode) { this.tlsMode = tlsMode; }
    public void setFrom(String from) { this.from = from; }
    public void setUsername(String username) { this.username = username; }
    public void setPassword(String password) { this.password = password; }
    public void setRecipients(Map<Long, String> recipients) { this.recipients = recipients; }

    boolean enabledFor(long user) {
        return configuredRecipient(user) != null;
    }

    String configuredRecipient(long user) { return smtpConfigured() ? recipients.get(user) : null; }

    private boolean smtpConfigured() { return !host.isBlank() && !from.isBlank(); }

    /** Builds the SMTP session settings with mandatory encryption in either mode. */
    Properties sessionProperties() {
        Properties props = new Properties();
        props.setProperty("mail.smtp.host", host);
        props.setProperty("mail.smtp.port", Integer.toString(port));
        props.setProperty("mail.smtp.auth", Boolean.toString(!username.isBlank()));
        switch (tlsMode) {
            case STARTTLS -> {
                props.setProperty("mail.smtp.starttls.enable", "true");
                props.setProperty("mail.smtp.starttls.required", "true");
            }
            case IMPLICIT_TLS -> props.setProperty("mail.smtp.ssl.enable", "true");
        }
        props.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        for (String timeout : new String[] {"connectiontimeout", "timeout", "writetimeout"}) {
            props.setProperty("mail.smtp." + timeout, "5000");
        }
        return props;
    }

    String send(long user, ZoneAlerts.Notice notice) {
        return sendTo(configuredRecipient(user), notice);
    }

    String sendTo(String recipient, ZoneAlerts.Notice notice) {
        if (!smtpConfigured() || recipient == null) return "SKIPPED";
        String title = "接近预警";
        String direction = "LONG".equals(notice.direction()) ? "做多方向" : "做空方向";
        String stage = "message preparation";
        boolean accepted = false;
        try {
            var session = Session.getInstance(sessionProperties());
            var message = new MimeMessage(session);
            message.setFrom(new InternetAddress(from, true));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient, true));
            message.setSubject("QuantCloud " + title + " · " + notice.symbol(), StandardCharsets.UTF_8.name());
            message.setText("%s %s · 区间 #%s · %s%n行情时间 %s%n请打开实时盘面查看。"
                    .formatted(notice.symbol(), notice.interval(), notice.zoneId(), direction,
                            Instant.ofEpochMilli(notice.marketTime())), StandardCharsets.UTF_8.name());
            message.saveChanges();
            stage = "connection";
            try (Transport transport = session.getTransport("smtp")) {
                transport.connect(username.isBlank() ? null : username, password);
                stage = "submission";
                transport.sendMessage(message, message.getAllRecipients());
                accepted = true;
                // Closing the SMTP session cannot undo an accepted message.
                stage = "session close";
            }
        } catch (MessagingException | RuntimeException failure) {
            if (failure instanceof AuthenticationFailedException) {
                stage = "authentication";
            }
            // Record only the exception type and numeric SMTP reply. Replies can contain addresses.
            log.warn("Zone alert SMTP {} failed for notice {} ({}, reply={})",
                    stage, notice.id(), failure.getClass().getSimpleName(), smtpReply(failure));
        }
        return accepted ? "ACCEPTED" : "FAILED";
    }

    private Integer smtpReply(Exception failure) {
        if (failure instanceof SMTPSendFailedException response) return response.getReturnCode();
        if (failure instanceof SMTPSenderFailedException response) return response.getReturnCode();
        if (failure instanceof SMTPAddressFailedException response) return response.getReturnCode();
        if (failure instanceof MessagingException messaging
                && messaging.getNextException() instanceof SMTPAddressFailedException response) {
            return response.getReturnCode();
        }
        return null;
    }
}
