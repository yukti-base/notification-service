package org.yuktisetu.notificationservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.yuktisetu.notification.model.DeliveryStatus;
import org.yuktisetu.notification.repository.NotificationDeliveryRepository;

/**
 * Says out loud, at startup, whether anything is going to send the queued mail.
 *
 * This exists because of a failure that wasted an afternoon. The drain sweep is
 * gated behind `yuktisetu.notification.drain.enabled`, and when it is off the
 * scheduler bean is simply never created -- no bean, no schedule, no log line,
 * no error. Meanwhile POST /dispatch keeps returning 202 and logging
 * "Dispatch accepted ... deliveries=1/1", which reads exactly like success. The
 * rows queue up at PENDING with attempts=0 and no error, and the only symptom
 * is an email that never arrives.
 *
 * A disabled sweep is a legitimate configuration; a disabled sweep that looks
 * identical to a working one is not. So the WARN below is deliberately loud and
 * names the property, and it reports the queue depth so an operator can see the
 * backlog that is accumulating behind it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DrainStartupReporter {

    private final NotificationDeliveryRepository deliveryRepository;

    @Value("${yuktisetu.notification.drain.enabled:false}")
    private boolean drainEnabled;

    @Value("${yuktisetu.notification.drain.fixed-delay-ms:15000}")
    private long fixedDelayMs;

    @Value("${app.email.enabled:false}")
    private boolean emailEnabled;

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        long pending = safeCount(DeliveryStatus.PENDING);
        long retry = safeCount(DeliveryStatus.RETRY);

        if (drainEnabled) {
            log.info("Delivery drain ENABLED: every {} ms, queue depth pending={} retry={}",
                    fixedDelayMs, pending, retry);
        } else {
            log.warn("""
                    Delivery drain is DISABLED (yuktisetu.notification.drain.enabled=false).
                    Dispatches will be ACCEPTED and queued but NEVER SENT -- POST /dispatch will
                    still return 202 and log "Dispatch accepted". Queue depth right now: pending={} retry={}.
                    Set yuktisetu.notification.drain.enabled=true to start sending.""", pending, retry);
        }

        if (!emailEnabled) {
            log.warn("app.email.enabled=false -- EMAIL deliveries will not leave this service.");
        }
    }

    /** The report must never be the reason startup fails. */
    private long safeCount(DeliveryStatus status) {
        try {
            return deliveryRepository.countByStatusAndIsDeletedFalse(status);
        } catch (Exception e) {
            log.debug("Could not count {} deliveries for the startup report: {}", status, e.getMessage());
            return -1;
        }
    }
}
