-- Mark the existing ZenMux OpenAI platform upstream as capable of serving the fixed Seedance model.
-- The auth token and base URL stay unchanged; the video service derives the Vertex AI proxy path.

UPDATE public.ai_gateway_platform_upstreams
SET supported_models = COALESCE(supported_models, '[]'::jsonb)
        || jsonb_build_array('bytedance/doubao-seedance-2.0'),
    updated_at = NOW()
WHERE name = 'zenmux-openai-api'
  AND NOT COALESCE(supported_models, '[]'::jsonb)
      @> '["bytedance/doubao-seedance-2.0"]'::jsonb;
