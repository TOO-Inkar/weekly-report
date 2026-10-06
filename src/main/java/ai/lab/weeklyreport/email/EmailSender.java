package ai.lab.weeklyreport.email;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/** Отправка готового недельного отчёта на почту (в дополнение к Telegram). */
@Component
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    private final JavaMailSenderImpl mailSender;

    public EmailSender(JavaMailSenderImpl mailSender) {
        this.mailSender = mailSender;
    }

    public void sendReport(List<String> recipients, String subject, String bodyText, String fileName, byte[] attachment)
            throws MessagingException, MailException {
        // from = авторизованный логин (spring.mail.username), а не отдельная настройка - так они
        // не могут разойтись и получатель всегда видит письмо от адреса, которым реально был
        // выполнен SMTP-логин.
        String fromAddress = mailSender.getUsername();
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(recipients.toArray(new String[0]));
            helper.setSubject(subject);
            helper.setText(bodyText);
            helper.addAttachment(fileName, new ByteArrayResource(attachment));
            mailSender.send(message);
        } catch (MessagingException | MailException e) {
            log.error(SmtpFailureClassifier.describe(e, mailSender.getHost(), mailSender.getPort(), fromAddress), e);
            throw e;
        }
    }

    /** Короткое текстовое уведомление без вложения (например, о том, что отчёт не дошёл в Telegram). */
    public void sendNotification(List<String> recipients, String subject, String bodyText)
            throws MessagingException, MailException {
        String fromAddress = mailSender.getUsername();
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(recipients.toArray(new String[0]));
            helper.setSubject(subject);
            helper.setText(bodyText);
            mailSender.send(message);
        } catch (MessagingException | MailException e) {
            log.error(SmtpFailureClassifier.describe(e, mailSender.getHost(), mailSender.getPort(), fromAddress), e);
            throw e;
        }
    }
}