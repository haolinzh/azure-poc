package com.example.hello;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MessageController.class)
class MessageControllerTest {
    @Autowired MockMvc mvc;
    @MockBean MessageRepository repo;

    @Test
    void createInsertsAndReturns() throws Exception {
        when(repo.insert(anyString()))
            .thenReturn(new Message(1L, "hi", OffsetDateTime.parse("2026-09-17T00:00:00Z")));
        mvc.perform(post("/messages").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"hi\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.content").value("hi"));
    }

    @Test
    void listReturnsAll() throws Exception {
        when(repo.findAll()).thenReturn(List.of(new Message(1L, "hi", OffsetDateTime.parse("2026-09-17T00:00:00Z"))));
        mvc.perform(get("/messages"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].content").value("hi"));
    }
}
