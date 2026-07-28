package ai.nubase.ai.gateway.service;

import ai.nubase.ai.gateway.dto.ImageGenerationRequest;
import ai.nubase.ai.gateway.dto.ImageGenerationResponse;
import ai.nubase.ai.gateway.service.image.ZenmuxOpenAiImageClient;
import ai.nubase.ai.gateway.service.image.ZenmuxOpenAiImageClient.GeneratedImage;
import ai.nubase.ai.gateway.service.image.ZenmuxOpenAiImageClient.ImagePredictResult;
import ai.nubase.ai.gateway.service.image.ZenmuxOpenAiImageClient.ImageReference;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ImageGenerationService {

    private static final String MODEL = "gpt-image-2";
    private static final String QUALIFIED_MODEL = "openai/gpt-image-2";

    private final ZenmuxOpenAiImageClient zenmuxOpenAiImageClient;

    @Value("${nubase.ai-gateway.image.default-model:openai/gpt-image-2}")
    private String defaultModel;

    public ImageGenerationResponse generate(ImageGenerationRequest request) throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        request.normalizeAndValidate(defaultModel);
        if (!supports(request.getModel())) {
            throw new IllegalArgumentException("Unsupported image model: " + request.getModel());
        }

        ImagePredictResult result = ImageGenerationRequest.TASK_IMAGE_TO_IMAGE.equals(request.getTask())
                ? zenmuxOpenAiImageClient.editImage(
                        request.getPrompt(),
                        toImageReferences(request.getInputImages()),
                        request.getConfig())
                : zenmuxOpenAiImageClient.generateImages(request.getPrompt(), request.getConfig());
        List<ImageGenerationResponse.Output> outputs = toOutputs(result.generatedImages());
        if (outputs.isEmpty()) {
            throw new IOException("Zenmux returned no generated images");
        }

        return ImageGenerationResponse.builder()
                .id("imggen_" + UUID.randomUUID().toString().replace("-", ""))
                .model(request.getModel())
                .task(request.getTask())
                .outputs(outputs)
                .upstream(ImageGenerationResponse.Upstream.builder()
                        .provider("zenmux")
                        .action(ImageGenerationRequest.TASK_IMAGE_TO_IMAGE.equals(request.getTask())
                                ? "edit_image"
                                : "generate_images")
                        .build())
                .build();
    }

    private boolean supports(String model) {
        return MODEL.equals(model) || QUALIFIED_MODEL.equals(model);
    }

    private List<ImageReference> toImageReferences(List<ImageGenerationRequest.ImageInput> inputImages) {
        List<ImageReference> references = new ArrayList<>();
        for (ImageGenerationRequest.ImageInput inputImage : inputImages) {
            references.add(new ImageReference(
                    inputImage.getReferenceId(),
                    inputImage.getData(),
                    inputImage.getUri(),
                    inputImage.getMimeType(),
                    inputImage.getReferenceType()));
        }
        return references;
    }

    private List<ImageGenerationResponse.Output> toOutputs(List<GeneratedImage> generatedImages) {
        List<ImageGenerationResponse.Output> outputs = new ArrayList<>();
        if (generatedImages == null) {
            return outputs;
        }

        for (GeneratedImage generatedImage : generatedImages) {
            if (generatedImage.imageBase64() == null && generatedImage.uri() == null) {
                continue;
            }
            outputs.add(ImageGenerationResponse.Output.builder()
                    .type("image")
                    .mimeType(generatedImage.mimeType())
                    .b64Json(generatedImage.imageBase64())
                    .uri(generatedImage.uri())
                    .raiFilteredReason(generatedImage.raiFilteredReason())
                    .enhancedPrompt(generatedImage.enhancedPrompt())
                    .safetyAttributes(generatedImage.safetyAttributes())
                    .build());
        }
        return outputs;
    }
}
