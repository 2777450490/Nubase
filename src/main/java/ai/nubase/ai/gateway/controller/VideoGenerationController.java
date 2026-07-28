package ai.nubase.ai.gateway.controller;

import ai.nubase.ai.gateway.dto.VideoGenerationRequest;
import ai.nubase.ai.gateway.dto.VideoOperationFetchRequest;
import ai.nubase.ai.gateway.service.VideoGenerationService;
import ai.nubase.ai.gateway.util.GatewayKeyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
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
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/ai/v1")
@RequiredArgsConstructor
public class VideoGenerationController {

    private final VideoGenerationService videoGenerationService;

    @PostMapping(
            value = {"/videos:generate", "/videos/generations"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> generate(
            @RequestBody VideoGenerationRequest request,
            HttpServletRequest httpRequest) {
        try {
            JsonNode response = videoGenerationService.submit(
                    request,
                    extractClientApiKey(httpRequest),
                    extractHeaders(httpRequest),
                    httpRequest.getHeader("x-upstream"));
            log.info("Video generation submitted: model={}, operation={}, upstream={}",
                    VideoGenerationService.SEEDANCE_MODEL,
                    response.path("name").asText(),
                    response.path("upstream").asText());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException exception) {
            log.warn("Invalid video generation request: {}", exception.getMessage());
            return ResponseEntity.badRequest().body(error("invalid_request", exception.getMessage()));
        } catch (IOException exception) {
            log.error("Video generation upstream request failed: {}", exception.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(error("upstream_error", exception.getMessage()));
        }
    }

    @PostMapping(value = "/videos/operations:fetch", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> fetch(
            @RequestBody VideoOperationFetchRequest request,
            HttpServletRequest httpRequest) {
        try {
            JsonNode response = videoGenerationService.fetch(
                    request,
                    extractClientApiKey(httpRequest),
                    extractHeaders(httpRequest),
                    httpRequest.getHeader("x-upstream"));
            log.info("Video generation operation fetched: model={}, operation={}, done={}, upstream={}",
                    VideoGenerationService.SEEDANCE_MODEL,
                    response.path("name").asText(),
                    response.path("done").asBoolean(false),
                    response.path("upstream").asText());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException exception) {
            log.warn("Invalid video operation fetch request: {}", exception.getMessage());
            return ResponseEntity.badRequest().body(error("invalid_request", exception.getMessage()));
        } catch (IOException exception) {
            log.error("Video operation fetch failed: {}", exception.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(error("upstream_error", exception.getMessage()));
        }
    }

    private String extractClientApiKey(HttpServletRequest request) {
        String apiKey = request.getHeader("x-api-key");
        if (apiKey != null && GatewayKeyUtil.isGatewayCredential(apiKey.trim())) {
            return apiKey.trim();
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String bearer = authorization.substring(7).trim();
            if (GatewayKeyUtil.isGatewayCredential(bearer)) {
                return bearer;
            }
        }
        return null;
    }

    private Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name.startsWith("x-") || name.equalsIgnoreCase("user-agent")) {
                headers.put(name, request.getHeader(name));
            }
        }
        return headers;
    }

    private Map<String, Object> error(String code, String message) {
        return Map.of("error", Map.of("type", code, "message", message));
    }
}
