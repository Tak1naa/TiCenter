package com.exambank.chat.service;

import com.exambank.common.entity.SystemConfig;
import com.exambank.common.repository.SystemConfigRepository;
import com.exambank.common.security.SecretValueCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Properties;

/**
 * 邮件告警：从 SystemConfig KV 读 SMTP 配置（改配置免重启）。
 * 未配 SMTP 或 alarm_enabled=false 时降级为日志告警。
 */
@Service
public class MailAlarmService {

    private static final Logger log = LoggerFactory.getLogger(MailAlarmService.class);

    private final SystemConfigRepository configRepository;
    private final SecretValueCrypto secretValueCrypto;

    @Value("${app.mail.delivery-enabled:true}")
    private boolean deliveryEnabled;

    public MailAlarmService(SystemConfigRepository configRepository, SecretValueCrypto secretValueCrypto) {
        this.configRepository = configRepository;
        this.secretValueCrypto = secretValueCrypto;
    }

    public boolean sendAlarm(String subject, String body) {
        if (!deliveryEnabled) {
            log.warn("[MAIL] 当前环境禁止外发邮件，降级日志: {} | {}", subject, body);
            return false;
        }
        String enabled = getConfig("alarm_enabled", "false");
        if (!"true".equalsIgnoreCase(enabled)) {
            log.warn("[ALARM] 告警开关关闭，降级日志: {} | {}", subject, body);
            return false;
        }
        String to = getConfig("alarm_emails", getConfig("notification_email", ""));
        if (to.isEmpty()) {
            log.warn("[ALARM] 无收件人，降级日志告警: {}", subject);
            return false;
        }
        return sendVia("smtp", to, subject, body);
    }

    /** 学生邮件（找回密码/注册验证）：独立 SMTP 配置 student_smtp_*，未配则降级告警 SMTP，再降级日志。 */
    public boolean sendStudentEmail(String to, String subject, String body) {
        if (!deliveryEnabled) {
            log.warn("[MAIL] 当前环境禁止外发学生邮件: {}", subject);
            return false;
        }
        if (sendVia("student_smtp", to, subject, body)) return true;
        // 降级：学生 SMTP 未配置时用告警 SMTP（若已配）
        return sendVia("smtp", to, subject, body);
    }

    /** 按配置前缀发送（如 smtp_* 或 student_smtp_*）。未配置返回 false。 */
    private boolean sendVia(String prefix, String to, String subject, String body) {
        String smtpHost = getConfig(prefix + "_host", "");
        String smtpUser = getConfig(prefix + "_user", "");
        String smtpPass = getConfig(prefix + "_pass", "");
        if (smtpHost.isEmpty() || smtpUser.isEmpty()) {
            log.warn("[MAIL] {} 未配置，无法发送: {}", prefix, subject);
            return false;
        }
        try {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(smtpHost);
            sender.setPort(Integer.parseInt(getConfig(prefix + "_port", "465")));
            sender.setUsername(smtpUser);
            sender.setPassword(smtpPass);
            Properties props = sender.getJavaMailProperties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.timeout", "10000");
            props.put("mail.smtp.connectiontimeout", "10000");

            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(smtpUser);
            msg.setTo(to.split(","));
            msg.setSubject(subject);
            msg.setText(body);
            sender.send(msg);
            log.info("[MAIL] 邮件已发送 ({}): {} -> {}", prefix, subject, to);
            return true;
        } catch (Exception e) {
            log.error("[MAIL] 邮件发送失败 ({}): {}", prefix, e.getMessage());
            return false;
        }
    }

    public String getConfig(String key, String def) {
        String value = configRepository.findByConfigKey(key)
                .map(SystemConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse(def);
        if (key.endsWith("_pass") && !value.isEmpty()) {
            try {
                return secretValueCrypto.decrypt(value);
            } catch (Exception e) {
                log.error("[MAIL] Refusing unusable or plaintext secret config {}", key);
                return def;
            }
        }
        return value;
    }
}
