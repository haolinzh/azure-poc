package com.example.hello;

import java.time.OffsetDateTime;

public record Message(long id, String content, OffsetDateTime createdAt) {}
