package ai.nubase.ai.gateway.service.image;

import ai.nubase.ai.gateway.service.image.ZenmuxOpenAiImageClient.ImagePredictResult;
import ai.nubase.ai.gateway.service.image.ZenmuxOpenAiImageClient.ImageReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ZenmuxOpenAiImageClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockWebServer server;
    private ZenmuxOpenAiImageClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new ZenmuxOpenAiImageClient(
                "test-key",
                server.url("/api/vertex-ai").toString(),
                "v1",
                5_000,
                objectMapper);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void buildsOpenAiTextToImagePredictRequest() throws Exception {
        server.enqueue(jsonResponse("""
                {
                  "predictions": [{
                    "bytesBase64Encoded": "generated",
                    "mimeType": "image/jpeg",
                    "prompt": "enhanced prompt"
                  }]
                }
                """));

        ImagePredictResult result = client.generateImages(
                "Draw a quiet aquarium",
                Map.of(
                        "n", 1,
                        "size", "1024x1024",
                        "aspect_ratio", "4:5",
                        "output_format", "jpeg"));

        RecordedRequest recordedRequest = server.takeRequest();
        JsonNode body = objectMapper.readTree(recordedRequest.getBody().readUtf8());

        assertThat(recordedRequest.getPath())
                .isEqualTo("/api/vertex-ai/v1/publishers/openai/models/gpt-image-2:predict");
        assertThat(recordedRequest.getHeader("Authorization")).isEqualTo("Bearer test-key");
        assertThat(body.path("instances").get(0).path("prompt").asText())
                .isEqualTo("Draw a quiet aquarium");
        assertThat(body.path("parameters").path("sampleCount").asInt()).isEqualTo(1);
        assertThat(body.path("parameters").path("sampleImageSize").asText())
                .isEqualTo("1024x1024");
        assertThat(body.path("parameters").path("aspectRatio").asText()).isEqualTo("4:5");
        assertThat(body.path("parameters").path("outputOptions").path("mimeType").asText())
                .isEqualTo("image/jpeg");
        assertThat(result.generatedImages()).singleElement().satisfies(image -> {
            assertThat(image.imageBase64()).isEqualTo("generated");
            assertThat(image.enhancedPrompt()).isEqualTo("enhanced prompt");
        });
    }

    @Test
    void buildsOpenAiImageEditPredictRequest() throws Exception {
        server.enqueue(jsonResponse("""
                {
                  "predictions": [{
                    "bytesBase64Encoded": "edited",
                    "mimeType": "image/png"
                  }]
                }
                """));

        ImagePredictResult result = client.editImage(
                "Add soft blue lighting",
                List.of(new ImageReference(
                        2,
                        "source-image",
                        null,
                        "image/png",
                        "REFERENCE_TYPE_RAW")),
                Map.of("edit_mode", "EDIT_MODE_INPAINT_INSERTION"));

        RecordedRequest recordedRequest = server.takeRequest();
        JsonNode body = objectMapper.readTree(recordedRequest.getBody().readUtf8());
        JsonNode referenceImage = body.path("instances").get(0).path("referenceImages").get(0);

        assertThat(recordedRequest.getPath())
                .isEqualTo("/api/vertex-ai/v1/publishers/openai/models/gpt-image-2:predict");
        assertThat(referenceImage.path("referenceId").asInt()).isEqualTo(2);
        assertThat(referenceImage.path("referenceType").asText())
                .isEqualTo("REFERENCE_TYPE_RAW");
        assertThat(referenceImage.path("referenceImage").path("bytesBase64Encoded").asText())
                .isEqualTo("source-image");
        assertThat(body.path("parameters").path("editMode").asText())
                .isEqualTo("EDIT_MODE_INPAINT_INSERTION");
        assertThat(result.generatedImages().get(0).imageBase64()).isEqualTo("edited");
    }

    private MockResponse jsonResponse(String body) {
        return new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
