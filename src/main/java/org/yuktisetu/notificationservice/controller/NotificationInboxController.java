package org.yuktisetu.notificationservice.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yuktisetu.core.response.YuktiSetuResponse;
import org.yuktisetu.core.security.UserPrincipal;
import org.yuktisetu.notificationservice.dto.NotificationResponse;
import org.yuktisetu.notificationservice.service.NotificationInboxService;

/**
 * The bell, for whoever is holding the token.
 *
 * Every method reads the recipient from the principal and never from a path or
 * query parameter -- same discipline as drive-service's student endpoints. An
 * endpoint that accepted a userId would let any authenticated user page through
 * another person's notifications, which on this platform means reading their
 * shortlist and offer history.
 */
@Slf4j
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationInboxController {

    private final NotificationInboxService inboxService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<YuktiSetuResponse<Page<NotificationResponse>>> list(
            @AuthenticationPrincipal UserPrincipal actor,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<NotificationResponse> result = inboxService.list(actor.userId(), unreadOnly, page, size);
        return ResponseEntity.ok(YuktiSetuResponse.success(result, "Notifications fetched"));
    }

    /** The unread badge. Its own endpoint because the bell polls this, not the list. */
    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<YuktiSetuResponse<Long>> unreadCount(@AuthenticationPrincipal UserPrincipal actor) {
        return ResponseEntity.ok(YuktiSetuResponse.success(
                inboxService.unreadCount(actor.userId()), "Unread count fetched"));
    }

    @PostMapping("/{id}/read")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<YuktiSetuResponse<Void>> markRead(
            @AuthenticationPrincipal UserPrincipal actor,
            @PathVariable Long id) {

        inboxService.markRead(actor.userId(), id);
        return ResponseEntity.ok(YuktiSetuResponse.success(null, "Notification marked read"));
    }

    @PostMapping("/read-all")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<YuktiSetuResponse<Integer>> markAllRead(@AuthenticationPrincipal UserPrincipal actor) {
        int updated = inboxService.markAllRead(actor.userId());
        return ResponseEntity.ok(YuktiSetuResponse.success(updated, "Notifications marked read"));
    }
}
