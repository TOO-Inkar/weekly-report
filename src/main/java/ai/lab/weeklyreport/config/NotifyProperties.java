package ai.lab.weeklyreport.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Получатели короткого статус-письма о доставке недельного отчёта (WEEKLY_REPORT_NOTIFY_RECIPIENTS -
 * список email через запятую): после каждого цикла доставки - "успешно отправлен" или уведомление о
 * финальном сбое. Письмо без вложения, сам отчёт по почте получают только
 * {@code weekly-report.email.recipients}. Пустой список - статус-письма не отправляются.
 */
@ConfigurationProperties(prefix = "weekly-report.notify")
public record NotifyProperties(List<String> recipients) {

    public NotifyProperties {
        recipients = recipients == null ? List.of() : recipients.stream().map(String::trim).filter(r -> !r.isEmpty()).toList();
    }
}
