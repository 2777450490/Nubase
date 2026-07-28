package ai.nubase.ai.gateway.controller;

import ai.nubase.ai.gateway.dto.ImageGenerationRequest;
import ai.nubase.ai.gateway.dto.ImageGenerationResponse;
import ai.nubase.ai.gateway.service.ImageGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/ai/v1/images")
@RequiredArgsConstructor
public class ImageGenerationController {

    private final ImageGenerationService imageGenerationService;

    @PostMapping(value = "/generations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createImageGeneration(@RequestBody ImageGenerationRequest request) {
        try {
            ImageGenerationResponse response = imageGenerationService.generate(request);
            log.info("Image generation completed: model={}, task={}, outputs={}",
                    response.getModel(), response.getTask(), response.getOutputs().size());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException exception) {
            log.warn("Invalid image generation request: {}", exception.getMessage());
            return ResponseEntity.badRequest().body(error("invalid_request", exception.getMessage()));
        } catch (IOException exception) {
            log.error("Image generation upstream request failed: {}", exception.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(error("upstream_error", exception.getMessage()));
        }
    }

    private Map<String, Object> error(String code, String message) {
        return Map.of("error", Map.of("type", code, "message", message));
    }
}
