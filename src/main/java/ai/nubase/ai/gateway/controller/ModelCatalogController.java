package ai.nubase.ai.gateway.controller;

import ai.nubase.ai.gateway.catalog.PublicModelCatalogDtos.CatalogResponse;
import ai.nubase.ai.gateway.catalog.PublicModelCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Public model catalog consumed by the marketing /models page.
 */
@RestController
@RequestMapping("/api/v1/models")
@RequiredArgsConstructor
public class ModelCatalogController {

    private final PublicModelCatalogService publicModelCatalogService;

    @GetMapping("/public")
    public ResponseEntity<CatalogResponse> publicModels() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(publicModelCatalogService.getCatalog());
    }
}
