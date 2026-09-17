package com.example.hello;

import com.azure.spring.messaging.eventhubs.core.EventHubsTemplate;
import java.time.Instant;
import java.util.Map;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/eventhub")
public class EventHubController {
    private final EventHubsTemplate template;

    public EventHubController(EventHubsTemplate template) {
        this.template = template;
    }

    @PostMapping("/send")
    public Map<String, String> send(@RequestBody(required = false) Msg req) {
        String payload = (req == null || req.content() == null || req.content().isBlank())
                ? "hello-from-msi@" + Instant.now()
                : req.content();
        template.send("hello-hub", MessageBuilder.withPayload(payload).build());
        return Map.of("status", "sent", "payload", payload);
    }

    public record Msg(String content) {}
}
