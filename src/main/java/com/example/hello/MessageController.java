package com.example.hello;

import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/messages")
public class MessageController {
    private final MessageRepository repo;

    public MessageController(MessageRepository repo) {
        this.repo = repo;
    }

    @PostMapping
    public Message create(@RequestBody(required = false) CreateRequest req) {
        String content = (req == null || req.content() == null || req.content().isBlank())
                ? "hello-from-msi@" + Instant.now()
                : req.content();
        return repo.insert(content);
    }

    @GetMapping
    public List<Message> list() {
        return repo.findAll();
    }

    public record CreateRequest(String content) {}
}
