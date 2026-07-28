import type { Metadata } from "next";
import { Activity, ArrowDown } from "lucide-react";
import { getLang } from "@/lib/get-lang";
import {
  FALLBACK_MODEL_ENDPOINTS,
  PUBLIC_GATEWAY_URL,
  fetchPublicModelCatalog,
} from "@/lib/model-catalog";
import { SITE, url } from "@/lib/site";
import { getModelsCopy } from "./copy";
import { ModelCatalog } from "./model-catalog";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "Supported AI models and gateway endpoints",
  description:
    "Browse models available through Nubase AI Gateway and connect using OpenAI-compatible or Anthropic-compatible endpoints.",
  alternates: { canonical: url("/models") },
  openGraph: {
    title: "Nubase AI Gateway model catalog",
    description:
      "Supported models, protocol compatibility, gateway endpoints and public token pricing.",
    url: url("/models"),
    images: [SITE.ogImage],
  },
};

export default async function ModelsPage() {
  const copy = getModelsCopy(getLang());
  let catalog = null;

  try {
    catalog = await fetchPublicModelCatalog();
  } catch {
    // Keep the endpoint contract usable when the live catalog is temporarily unavailable.
  }

  const models = catalog?.data ?? [];
  const endpoints = catalog?.endpoints ?? FALLBACK_MODEL_ENDPOINTS;

  return (
    <main>
      <section className="relative isolate overflow-hidden pb-20 pt-20 sm:pb-24 sm:pt-24">
        <div className="nb-grid absolute inset-0 -z-20" />
        <div className="nb-glow absolute inset-0 -z-10" />
        <div className="container">
          <div className="grid items-end gap-10 lg:grid-cols-[minmax(0,1fr)_320px]">
            <div className="max-w-4xl">
              <div className="inline-flex items-center gap-2 rounded-full border border-[var(--nb-line)] bg-[var(--nb-surface)] px-3 py-1.5 font-mono text-[10px] uppercase tracking-[0.18em] text-[var(--nb-dim)] shadow-sm">
                <Activity className="h-3.5 w-3.5 text-green" />
                {copy.eyebrow}
              </div>
              <h1 className="mt-7 text-5xl font-bold leading-[0.98] tracking-[-0.045em] sm:text-6xl lg:text-7xl">
                {copy.title}
                <br />
                <span className="nb-gradient-text">{copy.titleAccent}</span>
              </h1>
              <p className="mt-6 max-w-2xl text-base leading-7 text-[var(--nb-dim)] sm:text-lg">
                {copy.intro}
              </p>
            </div>
            <div className="relative overflow-hidden rounded-[18px] border border-[var(--nb-line)] bg-[var(--nb-surface)] p-6 shadow-[0_24px_60px_-42px_rgba(7,121,90,0.7)]">
              <div className="absolute right-0 top-0 h-20 w-20 rounded-bl-full bg-[var(--nb-mint-soft)]" />
              <div className="relative">
                <p className="font-mono text-[10px] uppercase tracking-[0.2em] text-[var(--nb-dim)]">
                  {copy.liveCatalog}
                </p>
                <p className="mt-3 text-5xl font-semibold tabular-nums">
                  {models.length}
                </p>
                <p className="mt-2 text-sm text-[var(--nb-dim)]">
                  {copy.configuredModels}
                </p>
                <a
                  href="#catalog"
                  className="mt-6 inline-flex items-center gap-2 text-xs font-semibold text-green"
                >
                  {copy.catalogTitle}
                  <ArrowDown className="h-3.5 w-3.5" />
                </a>
              </div>
            </div>
          </div>
        </div>
      </section>

      <ModelCatalog
        models={models}
        endpoints={endpoints}
        gatewayUrl={PUBLIC_GATEWAY_URL}
        catalogAvailable={catalog !== null}
        copy={copy}
      />
    </main>
  );
}
