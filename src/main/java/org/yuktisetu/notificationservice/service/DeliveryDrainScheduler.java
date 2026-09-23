package org.yuktisetu.notificationservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the drain loop. A separate bean from DeliveryDrainer on purpose: a
 * @Scheduled method calling a @Transactional method on the same bean would
 * bypass the transaction proxy entirely, and the failure would only surface
 * later, under enough data to force a flush. The cross-bean call is what makes
 * REQUIRES_NEW actually apply.
 *
 * Off by default, like drive-service's sweep: a developer's laptop must not
 * start mailing real students out of the shared database.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "yuktisetu.notification.drain.enabled", havingValue = "true")
public class DeliveryDrainScheduler {

    private final DeliveryDrainer drainer;

    @Value("${yuktisetu.notification.drain.max-passes-per-tick:5}")
    private int maxPassesPerTick;

    @Scheduled(fixedDelayString = "${yuktisetu.notification.drain.fixed-delay-ms:15000}")
    public void drain() {
        try {
            // Keep draining while a tick is still finding work, so a 2000-student
            // blast clears in one tick's worth of passes rather than trickling out
            // 200 per 15 seconds. Bounded so the loop cannot monopolise the
            // scheduler thread on a permanently backlogged queue.
            for (int pass = 0; pass < maxPassesPerTick; pass++) {
                int sent = drainer.drainOnce();
                if (sent == 0) {
                    return;
                }
            }
            log.info("Drain tick hit its pass ceiling of {} -- backlog remains, next tick continues",
                    maxPassesPerTick);

        } catch (Exception e) {
            // Never let an exception escape a @Scheduled method: Spring cancels
            // the schedule permanently if it propagates, and the queue would stop
            // draining silently until the next restart.
            log.error("Drain tick failed, will retry on the next tick: {}", e.getMessage(), e);
        }
    }
}
