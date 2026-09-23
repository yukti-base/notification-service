package org.yuktisetu.notificationservice.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yuktisetu.core.response.YuktiSetuResponse;
import org.yuktisetu.messaging.events.NotificationDispatchEvent;
import org.yuktisetu.notificationservice.dto.DispatchAcceptedResponse;
import org.yuktisetu.notificationservice.service.DispatchIngestionService;

/**
 * The synchronous door, for single low-stakes sends -- auth-service's one-off
 * role invite is the motivating case.
 *
 * It takes the same NotificationDispatchEvent the Kafka topic carries, so there
 * is exactly one payload shape on this service and callers do not have to learn
 * two. That is also why the contract is a LIST of recipients even here: a caller
 * with fifty invites to send posts one request with fifty recipients, never
 * fifty requests.
 *
 * Fan-out blasts should not use this endpoint. They go through the caller's
 * transactional outbox onto the dispatch topic, so the notification survives
 * this service being down -- a synchronous POST that fails at 9 PM on drive
 * night is a notification nobody ever sends.
 */
@Slf4j
@RestController
@RequestMapping("/dispatch")
@RequiredArgsConstructor
public class DispatchController {

    private final DispatchIngestionService ingestionService;

    /**
     * @param idempotencyKey the caller's stable id for this dispatch. It becomes
     *                       source_event_id, so a retried call after a timeout
     *                       re-inserts nothing rather than sending everyone a
     *                       second copy. Callers must generate it before the
     *                       first attempt, not per attempt.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_SERVICE')")
    public ResponseEntity<YuktiSetuResponse<DispatchAcceptedResponse>> dispatch(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody NotificationDispatchEvent request) {

        log.info("POST /dispatch idempotencyKey={} type={} recipients={} groups={}",
                idempotencyKey,
                request.notificationType(),
                request.recipients() == null ? 0 : request.recipients().size(),
                request.broadcastGroupAliases() == null ? 0 : request.broadcastGroupAliases().size());

        DispatchAcceptedResponse accepted = ingestionService.ingest(idempotencyKey, request);

        // 202, not 200: the rows are durable but nothing has been sent yet.
        // Claiming "sent" here would be a lie the delivery log contradicts.
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(YuktiSetuResponse.success(accepted, "Dispatch accepted"));
    }
}
