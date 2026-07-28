"use client";

import { useMemo, useState } from "react";
import {
  Braces,
  Check,
  Copy,
  KeyRound,
  MessageSquareText,
  Search,
  TerminalSquare,
} from "lucide-react";
import type { ModelsCopy } from "./copy";
import type {
  ModelCatalogEndpoints,
  ModelCatalogEntry,
} from "@/lib/model-catalog";

type ProtocolFilter = "all" | "openai" | "anthropic";

interface ModelCatalogProps {
  models: ModelCatalogEntry[];
  endpoints: ModelCatalogEndpoints;
  gatewayUrl: string;
  catalogAvailable: boolean;
  copy: ModelsCopy;
}

export function ModelCatalog({
  models,
  endpoints,
  gatewayUrl,
  catalogAvailable,
  copy,
}: ModelCatalogProps) {
  const [query, setQuery] = useState("");
  const [protocol, setProtocol] = useState<ProtocolFilter>("all");
  const [copied, setCopied] = useState<string | null>(null);

  const filteredModels = useMemo(() => {
    const normalizedQuery = query.trim().toLowerCase();
    return models.filter((model) => {
      const matchesQuery =
        !normalizedQuery ||
        model.slug.toLowerCase().includes(normalizedQuery) ||
        model.name.toLowerCase().includes(normalizedQuery);
      const matchesProtocol =
        protocol === "all" || model.protocols.includes(protocol);
      return matchesQuery && matchesProtocol;
    });
  }, [models, protocol, query]);

  async function copyValue(id: string, value: string) {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(id);
      window.setTimeout(
        () => setCopied((current) => (current === id ? null : current)),
        1600,
      );
    } catch {
      setCopied(null);
    }
  }

  const openAIBaseUrl = joinUrl(gatewayUrl, endpoints.openAI.basePath);
  const anthropicBaseUrl = joinUrl(gatewayUrl, endpoints.anthropic.basePath);
  const openAIExample =
    `curl "${gatewayUrl}${endpoints.openAI.chatCompletionsPath}" \\\n` +
    '  -H "Authorization: Bearer $NUBASE_AI_GATEWAY_KEY" \\\n' +
    '  -H "Content-Type: application/json" \\\n' +
    '  --data \'{"model":"MODEL_ID","messages":[{"role":"user","content":"Hello"}]}\'';
  const anthropicExample =
    `curl "${gatewayUrl}${endpoints.anthropic.messagesPath}" \\\n` +
    '  -H "x-api-key: $NUBASE_AI_GATEWAY_KEY" \\\n' +
    '  -H "anthropic-version: 2023-06-01" \\\n' +
    '  -H "Content-Type: application/json" \\\n' +
    '  --data \'{"model":"MODEL_ID","max_tokens":128,"messages":[{"role":"user","content":"Hello"}]}\'';

  return (
    <>
      <section className="container pb-20">
        <div className="mb-7 flex flex-col justify-between gap-3 md:flex-row md:items-end">
          <div>
            <p className="font-mono text-[11px] uppercase tracking-[0.2em] text-green">
              01 / API contracts
            </p>
            <h2 className="mt-2 text-2xl font-semibold sm:text-3xl">
              {copy.endpointTitle}
            </h2>
          </div>
          <p className="max-w-xl text-sm leading-6 text-[var(--nb-dim)]">
            {copy.endpointIntro}
          </p>
        </div>

        <div className="grid overflow-hidden rounded-[18px] border border-white/10 bg-[#111a17] text-white shadow-[0_30px_80px_-45px_rgba(4,35,27,0.8)] lg:grid-cols-2">
          <EndpointPanel
            index="01"
            title={copy.openAICompatible}
            icon={<Braces className="h-5 w-5" />}
            accent="bg-[#ffd23f] text-[#3f3300]"
            baseUrl={openAIBaseUrl}
            rows={[
              [copy.primaryRequest, endpoints.openAI.chatCompletionsPath],
              [copy.responsesRequest, endpoints.openAI.responsesPath],
              [copy.listModels, endpoints.openAI.modelsPath],
            ]}
            example={openAIExample}
            copy={copy}
            copied={copied === "openai"}
            onCopy={() => copyValue("openai", openAIExample)}
          />
          <EndpointPanel
            index="02"
            title={copy.anthropicCompatible}
            icon={<MessageSquareText className="h-5 w-5" />}
            accent="bg-[#34d3a6] text-[#06231b]"
            baseUrl={anthropicBaseUrl}
            rows={[
              [copy.primaryRequest, endpoints.anthropic.messagesPath],
              [copy.tokenCount, endpoints.anthropic.countTokensPath],
              [copy.listModels, endpoints.anthropic.modelsPath],
            ]}
            example={anthropicExample}
            copy={copy}
            copied={copied === "anthropic"}
            onCopy={() => copyValue("anthropic", anthropicExample)}
          />
        </div>
        <div className="mt-3 flex items-center gap-2 text-xs text-[var(--nb-dim)]">
          <KeyRound className="h-3.5 w-3.5 text-green" />
          {copy.keyNote}
        </div>
      </section>

      <section
        id="catalog"
        className="scroll-mt-16 border-y border-[var(--nb-line)] bg-[var(--nb-bg-2)] py-20"
      >
        <div className="container">
          <div className="flex flex-col justify-between gap-5 lg:flex-row lg:items-end">
            <div>
              <p className="font-mono text-[11px] uppercase tracking-[0.2em] text-green">
                02 / Model index
              </p>
              <h2 className="mt-2 text-3xl font-semibold sm:text-4xl">
                {copy.catalogTitle}
              </h2>
              <p className="mt-3 max-w-2xl text-sm leading-6 text-[var(--nb-dim)]">
                {copy.catalogIntro}
              </p>
            </div>
            <div className="font-mono text-xs text-[var(--nb-dim)]">
              <span className="text-lg font-semibold text-[var(--nb-ink)]">
                {filteredModels.length}
              </span>{" "}
              {copy.models}
            </div>
          </div>

          {!catalogAvailable ? (
            <div className="mt-8 rounded-xl border border-[var(--nb-orange)]/30 bg-[var(--nb-orange-soft)] px-4 py-3 text-sm text-[#6f2a18] dark:text-[#ffb7a3]">
              {copy.catalogUnavailable}
            </div>
          ) : null}

          <div className="mt-8 flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
            <label className="relative block w-full md:max-w-md">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[var(--nb-dim)]" />
              <input
                type="search"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder={copy.searchPlaceholder}
                aria-label={copy.searchPlaceholder}
                className="h-11 w-full rounded-xl border border-[var(--nb-line)] bg-[var(--nb-surface)] pl-10 pr-4 text-sm outline-none transition focus:border-[var(--nb-mint)] focus:ring-2 focus:ring-[var(--nb-mint-soft)]"
              />
            </label>
            <div
              role="group"
              aria-label={copy.protocol}
              className="flex w-fit rounded-xl border border-[var(--nb-line)] bg-[var(--nb-surface)] p-1"
            >
              {(
                [
                  ["all", copy.allProtocols],
                  ["openai", copy.openAI],
                  ["anthropic", copy.anthropic],
                ] as Array<[ProtocolFilter, string]>
              ).map(([value, label]) => (
                <button
                  key={value}
                  type="button"
                  onClick={() => setProtocol(value)}
                  aria-pressed={protocol === value}
                  className={
                    "rounded-lg px-3 py-2 text-xs font-medium transition " +
                    (protocol === value
                      ? "bg-[var(--nb-ink)] text-[var(--nb-bg)]"
                      : "text-[var(--nb-dim)] hover:text-[var(--nb-ink)]")
                  }
                >
                  {label}
                </button>
              ))}
            </div>
          </div>

          <div className="mt-5 overflow-x-auto rounded-[16px] border border-[var(--nb-line)] bg-[var(--nb-surface)]">
            <div className="min-w-[900px]">
              <div className="grid grid-cols-[minmax(260px,1.6fr)_180px_repeat(4,minmax(110px,0.7fr))] border-b border-[var(--nb-line)] bg-[var(--nb-surface-2)] px-5 py-3 font-mono text-[10px] uppercase tracking-[0.13em] text-[var(--nb-dim)]">
                <span>{copy.model}</span>
                <span>{copy.protocol}</span>
                <span>{copy.input}</span>
                <span>{copy.output}</span>
                <span>{copy.cacheWrite}</span>
                <span>{copy.cacheRead}</span>
              </div>
              {filteredModels.map((model) => (
                <ModelRow key={model.slug} model={model} copy={copy} />
              ))}
              {filteredModels.length === 0 ? (
                <div className="px-5 py-12 text-center text-sm text-[var(--nb-dim)]">
                  {copy.noMatches}
                </div>
              ) : null}
            </div>
          </div>
        </div>
      </section>
    </>
  );
}

function EndpointPanel({
  index,
  title,
  icon,
  accent,
  baseUrl,
  rows,
  example,
  copy,
  copied,
  onCopy,
}: {
  index: string;
  title: string;
  icon: React.ReactNode;
  accent: string;
  baseUrl: string;
  rows: Array<[string, string]>;
  example: string;
  copy: ModelsCopy;
  copied: boolean;
  onCopy: () => void;
}) {
  return (
    <article className="relative border-white/10 p-6 first:border-b lg:p-8 lg:first:border-b-0 lg:first:border-r">
      <div className="absolute right-6 top-5 font-mono text-[10px] tracking-[0.22em] text-white/30">
        {index}
      </div>
      <div
        className={`flex h-10 w-10 items-center justify-center rounded-xl ${accent}`}
      >
        {icon}
      </div>
      <h3 className="mt-5 text-xl font-semibold">{title}</h3>
      <dl className="mt-6 divide-y divide-white/10 border-y border-white/10">
        <div className="grid gap-1 py-3 sm:grid-cols-[130px_1fr]">
          <dt className="text-xs text-white/50">{copy.baseUrl}</dt>
          <dd className="break-all font-mono text-xs text-white/90">
            {baseUrl}
          </dd>
        </div>
        {rows.map(([label, value]) => (
          <div key={label} className="grid gap-1 py-3 sm:grid-cols-[130px_1fr]">
            <dt className="text-xs text-white/50">{label}</dt>
            <dd className="font-mono text-xs text-white/90">{value}</dd>
          </div>
        ))}
      </dl>
      <div className="mt-5 overflow-hidden rounded-xl border border-white/10 bg-black/30">
        <div className="flex items-center justify-between border-b border-white/10 px-3 py-2">
          <span className="flex items-center gap-1.5 font-mono text-[10px] uppercase tracking-wider text-white/40">
            <TerminalSquare className="h-3.5 w-3.5" />
            curl
          </span>
          <button
            type="button"
            onClick={onCopy}
            className="inline-flex items-center gap-1.5 rounded-md px-2 py-1 text-[11px] text-white/60 transition hover:bg-white/10 hover:text-white"
          >
            {copied ? (
              <Check className="h-3.5 w-3.5 text-[#34d3a6]" />
            ) : (
              <Copy className="h-3.5 w-3.5" />
            )}
            {copied ? copy.copied : copy.copyExample}
          </button>
        </div>
        <pre className="overflow-x-auto p-3 font-mono text-[11px] leading-5 text-white/70">
          <code>{example}</code>
        </pre>
      </div>
    </article>
  );
}

function ModelRow({
  model,
  copy,
}: {
  model: ModelCatalogEntry;
  copy: ModelsCopy;
}) {
  return (
    <div className="grid grid-cols-[minmax(260px,1.6fr)_180px_repeat(4,minmax(110px,0.7fr))] items-center border-b border-[var(--nb-line)] px-5 py-4 last:border-b-0">
      <div className="min-w-0 pr-5">
        <p className="truncate text-sm font-semibold text-[var(--nb-ink)]">
          {model.name}
        </p>
        <p className="mt-1 truncate font-mono text-[11px] text-[var(--nb-dim)]">
          {model.slug}
        </p>
      </div>
      <div className="flex flex-wrap gap-1.5">
        {model.protocols.map((protocol) => (
          <span
            key={protocol}
            className={
              "rounded-md border px-2 py-1 font-mono text-[10px] uppercase tracking-wide " +
              (protocol === "openai"
                ? "border-[#e0b300]/30 bg-[var(--nb-yellow-soft)] text-[#6c5600]"
                : "border-[var(--nb-mint)]/30 bg-[var(--nb-mint-soft)] text-[var(--nb-green-deep)]")
            }
          >
            {protocol === "openai" ? copy.openAI : copy.anthropic}
          </span>
        ))}
      </div>
      {model.pricing ? (
        <>
          <Price
            value={model.pricing.inputPer1M}
            currency={model.pricing.currency}
          />
          <Price
            value={model.pricing.outputPer1M}
            currency={model.pricing.currency}
          />
          <Price
            value={model.pricing.cacheCreationPer1M}
            currency={model.pricing.currency}
          />
          <Price
            value={model.pricing.cacheReadPer1M}
            currency={model.pricing.currency}
          />
        </>
      ) : (
        <div className="col-span-4 text-xs text-[var(--nb-dim)]">
          {copy.unavailable}
        </div>
      )}
    </div>
  );
}

function Price({ value, currency }: { value: number; currency: string }) {
  return (
    <span className="font-mono text-xs tabular-nums text-[var(--nb-ink)]">
      {currency === "USD" ? "$" : `${currency} `}
      {formatPrice(value)}
    </span>
  );
}

function formatPrice(value: number): string {
  return Number(value).toLocaleString("en-US", {
    minimumFractionDigits: 0,
    maximumFractionDigits: 8,
  });
}

function joinUrl(baseUrl: string, path: string): string {
  const base = baseUrl.replace(/\/+$/, "");
  const suffix = path ? `/${path.replace(/^\/+/, "")}` : "";
  return `${base}${suffix}`;
}
