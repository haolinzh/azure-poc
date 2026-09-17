package com.example.hello;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat")
public class ChatController {
    private final OpenAIClient client;
    private final String deployment;

    public ChatController(OpenAIClient client,
            @Value("${spring.cloud.azure.openai.deployment-name}") String deployment) {
        this.client = client;
        this.deployment = deployment;
    }

    @PostMapping
    public Map<String, String> chat(@RequestBody(required = false) ChatReq req) {
        String prompt = (req == null || req.prompt() == null || req.prompt().isBlank())
                ? "Say hello in one short sentence." : req.prompt();
        ChatCompletions completions = client.getChatCompletions(
                deployment,
                new ChatCompletionsOptions(List.of(new ChatRequestUserMessage(prompt))));
        String reply = completions.getChoices().get(0).getMessage().getContent();
        return Map.of("prompt", prompt, "reply", reply);
    }

    public record ChatReq(String prompt) {}
}
