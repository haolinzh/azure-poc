package com.example.hello;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MessageRepository {
    private final JdbcTemplate jdbc;

    public MessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Message insert(String content) {
        return jdbc.queryForObject(
            "INSERT INTO messages (content) VALUES (?) RETURNING id, content, created_at",
            (rs, i) -> new Message(
                rs.getLong("id"),
                rs.getString("content"),
                rs.getObject("created_at", OffsetDateTime.class)),
            content);
    }

    public List<Message> findAll() {
        return jdbc.query(
            "SELECT id, content, created_at FROM messages ORDER BY id DESC",
            (rs, i) -> new Message(
                rs.getLong("id"),
                rs.getString("content"),
                rs.getObject("created_at", OffsetDateTime.class)));
    }
}
