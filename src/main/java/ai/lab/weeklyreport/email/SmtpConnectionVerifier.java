package ai.lab.weeklyreport.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import ai.lab.weeklyreport.config.EmailProperties;
import ai.lab.weeklyreport.config.NotifyProperties;

/**
 * Проверяет SMTP-подключение (включая аутентификацию) один раз при старте приложения - но только
 * если задан хотя бы один получатель отчёта ({@code weekly-report.email.recipients}) или статус-писем
 * ({@code weekly-report.notify.recipients}); если почта не используется, проверка бессмысленна и пропускается. Как и сама отправка письма (см.
 * {@code WeeklyReportService}), это лучшая попытка поверх Telegram: неудачная проверка только
 * логируется и не мешает старту бота.
 */
@Component
public class SmtpConnectionVerifier implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SmtpConnectionVerifier.class);

    private final JavaMailSenderImpl mailSender;
    private final EmailProperties emailProperties;
    private final NotifyProperties notifyProperties;

    public SmtpConnectionVerifier(JavaMailSenderImpl mailSender, EmailProperties emailProperties,
                                  NotifyProperties notifyProperties) {
        this.mailSender = mailSender;
        this.emailProperties = emailProperties;
        this.notifyProperties = notifyProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (emailProperties.recipients().isEmpty() && notifyProperties.recipients().isEmpty()) {
            log.info("weekly-report.email.recipients и weekly-report.notify.recipients пусты - почта не используется, проверка SMTP-соединения пропущена");
            return;
        }
        verify();
    }

    /** @return true, если удалось установить и закрыть SMTP-соединение (включая аутентификацию). */
    public boolean verify() {
        try {
            mailSender.testConnection();
            log.info("SMTP-соединение проверено успешно: host={}, port={}, user={}",
                    mailSender.getHost(), mailSender.getPort(), mailSender.getUsername());
            return true;
        } catch (Exception e) {
            log.error(SmtpFailureClassifier.describe(e, mailSender.getHost(), mailSender.getPort(), mailSender.getUsername()), e);
            return false;
        }
    }
}