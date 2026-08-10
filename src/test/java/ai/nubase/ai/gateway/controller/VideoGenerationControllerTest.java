package ai.nubase.ai.gateway.controller;

import ai.nubase.ai.gateway.dto.VideoGenerationRequest;
import ai.nubase.ai.gateway.dto.VideoOperationFetchRequest;
import ai.nubase.ai.gateway.service.VideoGenerationService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class VideoGenerationControllerTest {

    @Test
    void upstreamFailuresDoNotExposeExceptionMessagesToResponsesOrLogs(CapturedOutput output) throws Exception {
        String generateSentinel = "video-generate-controller-sentinel";
        String fetchSentinel = "video-fetch-controller-sentinel";
        VideoGenerationRequest generateRequest = new VideoGenerationRequest();
        VideoOperationFetchRequest fetchRequest = new VideoOperationFetchRequest();
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getHeaderNames()).thenReturn(Collections.emptyEnumeration());

        VideoGenerationService service = mock(VideoGenerationService.class);
        when(service.submit(
                eq(generateRequest), nullable(String.class), anyMap(), nullable(String.class)))
                .thenThrow(new IOException(generateSentinel));
        when(service.fetch(
                eq(fetchRequest), nullable(String.class), anyMap(), nullable(String.class)))
                .thenThrow(new IOException(fetchSentinel));
        VideoGenerationController controller = new VideoGenerationController(service);

        ResponseEntity<?> generateResponse = controller.generate(generateRequest, httpRequest);
        ResponseEntity<?> fetchResponse = controller.fetch(fetchRequest, httpRequest);

        assertThat(generateResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(fetchResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(generateResponse.getBody().toString())
                .contains("Video upstream request failed")
                .doesNotContain(generateSentinel, fetchSentinel);
        assertThat(fetchResponse.getBody().toString())
                .contains("Video upstream request failed")
                .doesNotContain(generateSentinel, fetchSentinel);
        assertThat(output.getAll())
                .contains("type=IOException", "status=unavailable")
                .doesNotContain(generateSentinel, fetchSentinel);
    }
}
