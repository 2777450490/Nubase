package ai.nubase.ai.gateway.controller;

import ai.nubase.ai.gateway.dto.ImageGenerationRequest;
import ai.nubase.ai.gateway.service.ImageGenerationService;
import ai.nubase.ai.gateway.service.image.ZenmuxOpenAiImageClient.UpstreamHttpException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class ImageGenerationControllerTest {

    @Test
    void upstreamFailureDoesNotExposeExceptionMessageToResponseOrLogs(CapturedOutput output) throws Exception {
        String sentinel = "image-controller-upstream-sentinel";
        ImageGenerationRequest request = new ImageGenerationRequest();
        ImageGenerationService service = mock(ImageGenerationService.class);
        when(service.generate(request)).thenThrow(new IOException(sentinel));
        ImageGenerationController controller = new ImageGenerationController(service);

        ResponseEntity<?> response = controller.createImageGeneration(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().toString())
                .contains("Image upstream request failed")
                .doesNotContain(sentinel);
        assertThat(output.getAll())
                .contains("type=IOException", "status=unavailable")
                .doesNotContain(sentinel);
    }

    @Test
    void knownUpstreamFailureReturnsSafeStatusAndBodySize() throws Exception {
        ImageGenerationRequest request = new ImageGenerationRequest();
        ImageGenerationService service = mock(ImageGenerationService.class);
        when(service.generate(request)).thenThrow(new UpstreamHttpException(429, 73));
        ImageGenerationController controller = new ImageGenerationController(service);

        ResponseEntity<?> response = controller.createImageGeneration(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().toString())
                .contains("status=429", "bodyBytes=73");
    }
}
