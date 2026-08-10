package ai.nubase.ai.gateway.service;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRuntimeLogConfigurationTest {

    private static final Set<String> RESPONSE_LOGGERS = Set.of(
            "ClaudeResponseLogger",
            "OpenAIResponseLogger");

    @Test
    void responsePayloadLoggersAreDisabledInEveryProfile() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        try (InputStream input = getClass().getResourceAsStream("/logback-spring.xml")) {
            assertThat(input).isNotNull();
            NodeList loggers = factory.newDocumentBuilder().parse(input).getElementsByTagName("logger");
            int responseLoggerCount = 0;
            for (int index = 0; index < loggers.getLength(); index++) {
                Element logger = (Element) loggers.item(index);
                if (RESPONSE_LOGGERS.contains(logger.getAttribute("name"))) {
                    responseLoggerCount++;
                    assertThat(logger.getAttribute("level")).isEqualTo("OFF");
                }
            }
            assertThat(responseLoggerCount).isEqualTo(8);
        }
    }

    @Test
    void byteMetadataUsesUtf8Length() {
        String multibyteText = "A\u4E2D";

        assertThat(OpenAIApiService.utf8Length(null)).isZero();
        assertThat(OpenAIApiService.utf8Length(multibyteText)).isEqualTo(4);
        assertThat(OpenAINativeApiService.utf8Length(multibyteText)).isEqualTo(4);
    }
}
