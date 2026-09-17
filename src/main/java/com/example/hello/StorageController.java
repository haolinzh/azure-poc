package com.example.hello;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/storage")
public class StorageController {
    private static final String CONTAINER = "demo";
    private final BlobServiceClient blobService;

    public StorageController(BlobServiceClient blobService) {
        this.blobService = blobService;
    }

    @PostMapping("/upload")
    public Map<String, String> upload(@RequestBody(required = false) BlobReq req) {
        String name = (req == null || req.name() == null || req.name().isBlank()) ? "hello.txt" : req.name();
        String content = (req == null || req.content() == null || req.content().isBlank()) ? "hello-from-msi" : req.content();
        BlobClient blob = blobService.getBlobContainerClient(CONTAINER).getBlobClient(name);
        blob.upload(BinaryData.fromString(content), true);
        return Map.of("blob", name, "content", content);
    }

    @GetMapping("/list")
    public List<String> list() {
        BlobContainerClient container = blobService.getBlobContainerClient(CONTAINER);
        List<String> names = new ArrayList<>();
        for (BlobItem item : container.listBlobs()) {
            names.add(item.getName());
        }
        return names;
    }

    public record BlobReq(String name, String content) {}
}
