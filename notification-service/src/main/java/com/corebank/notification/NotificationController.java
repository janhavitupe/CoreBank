package com.corebank.notification;

import com.corebank.common.web.Caller;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping("/api/notifications")
    public List<Notification> mine(@RequestHeader(Caller.CUSTOMER_HEADER) UUID caller) {
        return service.forCustomer(caller, PageRequest.of(0, 50));
    }
}
