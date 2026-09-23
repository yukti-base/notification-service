package org.yuktisetu.notificationservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yuktisetu.core.exception.ForbiddenException;
import org.yuktisetu.core.exception.NotFoundException;
import org.yuktisetu.notification.db.Notification;
import org.yuktisetu.notificationservice.dto.NotificationResponse;
import org.yuktisetu.notification.repository.NotificationRepository;

import java.time.Instant;

/**
 * Read side of the bell. Paged, because a student three years into the platform
 * has hundreds of rows and the bell shows twenty.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationInboxService {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationRepository notificationRepository;

    @Transactional(readOnly = true)
    public Page<NotificationResponse> list(Long userId, boolean unreadOnly, int page, int size) {
        try {
            Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));

            Page<Notification> rows = unreadOnly
                    ? notificationRepository
                        .findByRecipientUserIdAndIsReadFalseAndIsDeletedFalseOrderByCreatedAtDesc(userId, pageable)
                    : notificationRepository
                        .findByRecipientUserIdAndIsDeletedFalseOrderByCreatedAtDesc(userId, pageable);

            return rows.map(NotificationInboxService::toResponse);

        } catch (Exception e) {
            log.error("Failed to list notifications for userId={}: {}", userId, e.getMessage(), e);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        try {
            return notificationRepository.countByRecipientUserIdAndIsReadFalseAndIsDeletedFalse(userId);
        } catch (Exception e) {
            log.error("Failed to count unread notifications for userId={}: {}", userId, e.getMessage(), e);
            throw e;
        }
    }

    @Transactional
    public void markRead(Long userId, Long notificationId) {
        try {
            Notification notification = notificationRepository.findByIdAndIsDeletedFalse(notificationId)
                    .orElseThrow(() -> new NotFoundException("Notification not found: " + notificationId));

            // Ownership is checked here rather than trusted from the path -- the
            // id space is sequential and guessable.
            if (!notification.getRecipientUserId().equals(userId)) {
                log.warn("Access denied — userId={} attempted to read notificationId={} belonging to userId={}",
                        userId, notificationId, notification.getRecipientUserId());
                throw new ForbiddenException("You do not have permission to modify this notification.");
            }

            if (!notification.isRead()) {
                notification.setRead(true);
                notification.setReadAt(Instant.now());
            }

        } catch (NotFoundException | ForbiddenException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to mark notificationId={} read for userId={}: {}",
                    notificationId, userId, e.getMessage(), e);
            throw e;
        }
    }

    @Transactional
    public int markAllRead(Long userId) {
        try {
            // One UPDATE, not a fetch-then-loop.
            return notificationRepository.markAllRead(userId, Instant.now());
        } catch (Exception e) {
            log.error("Failed to mark all notifications read for userId={}: {}", userId, e.getMessage(), e);
            throw e;
        }
    }

    private static NotificationResponse toResponse(Notification n) {
        return new NotificationResponse(
                n.getId(),
                n.getType() == null ? null : n.getType().name(),
                n.getSeverity() == null ? null : n.getSeverity().name(),
                n.getTitle(),
                n.getBody(),
                n.getActionUrl(),
                n.isRead(),
                n.getReadAt(),
                n.getCreatedAt());
    }
}
