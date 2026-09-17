package com.example.hello;

import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/keyvault")
public class KeyVaultController {
    private final SecretClient secretClient;

    public KeyVaultController(SecretClient secretClient) {
        this.secretClient = secretClient;
    }

    @GetMapping("/secret/{name}")
    public Map<String, String> get(@PathVariable String name) {
        KeyVaultSecret secret = secretClient.getSecret(name);
        return Map.of("name", name, "value", secret.getValue());
    }
}
