import { SITE_URL } from "@/lib/site";

export interface ModelCatalogPricing {
  currency: string;
  inputPer1M: number;
  outputPer1M: number;
  cacheCreationPer1M: number;
  cacheReadPer1M: number;
}

export interface ModelCatalogEntry {
  slug: string;
  name: string;
  provider: string;
  protocols: Array<"openai" | "anthropic">;
  pricing: ModelCatalogPricing | null;
}

export interface ModelCatalogEndpoints {
  openAI: {
    basePath: string;
    chatCompletionsPath: string;
    responsesPath: string;
    modelsPath: string;
  };
  anthropic: {
    basePath: string;
    messagesPath: string;
    countTokensPath: string;
    modelsPath: string;
  };
}

export interface ModelCatalogResponse {
  data: ModelCatalogEntry[];
  endpoints: ModelCatalogEndpoints;
}

export const FALLBACK_MODEL_ENDPOINTS: ModelCatalogEndpoints = {
  openAI: {
    basePath: "/v1",
    chatCompletionsPath: "/v1/chat/completions",
    responsesPath: "/v1/responses",
    modelsPath: "/v1/models",
  },
  anthropic: {
    basePath: "",
    messagesPath: "/v1/messages",
    countTokensPath: "/v1/messages/count_tokens",
    modelsPath: "/v1/models",
  },
};

const developmentGatewayUrl = "http://localhost:9999";

export const PUBLIC_GATEWAY_URL = (
  process.env.NEXT_PUBLIC_NUBASE_API_URL ??
  (process.env.NODE_ENV === "development" ? developmentGatewayUrl : SITE_URL)
).replace(/\/+$/, "");

const internalGatewayUrl = (
  process.env.NUBASE_INTERNAL_API_URL ?? PUBLIC_GATEWAY_URL
).replace(/\/+$/, "");

export async function fetchPublicModelCatalog(): Promise<ModelCatalogResponse> {
  const response = await fetch(`${internalGatewayUrl}/api/v1/models/public`, {
    next: { revalidate: 60 },
  });
  if (!response.ok) {
    throw new Error(
      `Model catalog request failed with status ${response.status}`,
    );
  }
  return (await response.json()) as ModelCatalogResponse;
}
