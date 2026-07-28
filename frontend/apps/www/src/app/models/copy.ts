import type { Lang } from "@/lib/i18n";

export interface ModelsCopy {
  eyebrow: string;
  title: string;
  titleAccent: string;
  intro: string;
  liveCatalog: string;
  configuredModels: string;
  endpointTitle: string;
  endpointIntro: string;
  openAICompatible: string;
  anthropicCompatible: string;
  baseUrl: string;
  primaryRequest: string;
  responsesRequest: string;
  tokenCount: string;
  listModels: string;
  copyExample: string;
  copied: string;
  keyNote: string;
  catalogTitle: string;
  catalogIntro: string;
  searchPlaceholder: string;
  allProtocols: string;
  openAI: string;
  anthropic: string;
  models: string;
  model: string;
  protocol: string;
  input: string;
  output: string;
  cacheWrite: string;
  cacheRead: string;
  perMillion: string;
  unavailable: string;
  noMatches: string;
  catalogUnavailable: string;
}

const copies: Record<Lang, ModelsCopy> = {
  en: {
    eyebrow: "Model routing / live catalog",
    title: "One model list.",
    titleAccent: "Two familiar protocols.",
    intro:
      "Route supported models through Nubase using the OpenAI or Anthropic contract your client already understands.",
    liveCatalog: "Live catalog",
    configuredModels: "configured models",
    endpointTitle: "Choose your wire protocol",
    endpointIntro:
      "The gateway key stays the same. Only the base URL, request shape, and protocol headers change.",
    openAICompatible: "OpenAI-compatible",
    anthropicCompatible: "Anthropic-compatible",
    baseUrl: "Base URL",
    primaryRequest: "Primary request",
    responsesRequest: "Responses API",
    tokenCount: "Count tokens",
    listModels: "List models",
    copyExample: "Copy example",
    copied: "Copied",
    keyNote: "Use a project AI Gateway key. Never expose it in browser code.",
    catalogTitle: "Available models",
    catalogIntro:
      "Models explicitly configured on active platform routes. Pricing is shown in USD per one million tokens.",
    searchPlaceholder: "Search model ID or display name",
    allProtocols: "All protocols",
    openAI: "OpenAI",
    anthropic: "Claude",
    models: "models",
    model: "Model",
    protocol: "Protocol",
    input: "Input",
    output: "Output",
    cacheWrite: "Cache write",
    cacheRead: "Cache read",
    perMillion: "/ 1M tokens",
    unavailable: "Price unavailable",
    noMatches: "No models match this search.",
    catalogUnavailable:
      "The live model catalog is temporarily unavailable. Endpoint contracts remain available below.",
  },
  zh: {
    eyebrow: "模型路由 / 实时目录",
    title: "一份模型目录，",
    titleAccent: "两套熟悉协议。",
    intro:
      "通过 Nubase 调用已支持的模型，继续使用客户端熟悉的 OpenAI 或 Anthropic 请求契约。",
    liveCatalog: "实时目录",
    configuredModels: "个已配置模型",
    endpointTitle: "选择请求协议",
    endpointIntro:
      "Gateway Key 保持不变，只需切换 Base URL、请求体和协议请求头。",
    openAICompatible: "兼容 OpenAI",
    anthropicCompatible: "兼容 Anthropic",
    baseUrl: "Base URL",
    primaryRequest: "主要请求",
    responsesRequest: "Responses API",
    tokenCount: "计算 Token",
    listModels: "模型列表",
    copyExample: "复制示例",
    copied: "已复制",
    keyNote: "请使用项目 AI Gateway Key，切勿将其暴露在浏览器代码中。",
    catalogTitle: "可用模型",
    catalogIntro:
      "仅展示活动平台路由显式配置的模型。价格单位为 USD/百万 Token。",
    searchPlaceholder: "搜索模型 ID 或展示名称",
    allProtocols: "全部协议",
    openAI: "OpenAI",
    anthropic: "Claude",
    models: "个模型",
    model: "模型",
    protocol: "协议",
    input: "输入",
    output: "输出",
    cacheWrite: "缓存写入",
    cacheRead: "缓存读取",
    perMillion: "/ 百万 Token",
    unavailable: "暂无价格",
    noMatches: "没有匹配的模型。",
    catalogUnavailable: "实时模型目录暂时不可用，下方接口契约仍可正常查看。",
  },
  ja: {
    eyebrow: "モデルルーティング / ライブカタログ",
    title: "1 つのモデル一覧、",
    titleAccent: "2 つの使い慣れたプロトコル。",
    intro:
      "Nubase でサポート済みモデルを、既存クライアントの OpenAI または Anthropic 契約から呼び出せます。",
    liveCatalog: "ライブカタログ",
    configuredModels: "設定済みモデル",
    endpointTitle: "通信プロトコルを選択",
    endpointIntro:
      "Gateway Key は共通です。Base URL、リクエスト形式、プロトコルヘッダーだけが変わります。",
    openAICompatible: "OpenAI 互換",
    anthropicCompatible: "Anthropic 互換",
    baseUrl: "Base URL",
    primaryRequest: "主要リクエスト",
    responsesRequest: "Responses API",
    tokenCount: "トークン計算",
    listModels: "モデル一覧",
    copyExample: "例をコピー",
    copied: "コピー済み",
    keyNote:
      "プロジェクトの AI Gateway Key を使用し、ブラウザコードには公開しないでください。",
    catalogTitle: "利用可能なモデル",
    catalogIntro:
      "有効なプラットフォームルートに明示設定されたモデルです。価格は 100 万トークンあたりの USD。",
    searchPlaceholder: "モデル ID または表示名を検索",
    allProtocols: "すべてのプロトコル",
    openAI: "OpenAI",
    anthropic: "Claude",
    models: "モデル",
    model: "モデル",
    protocol: "プロトコル",
    input: "入力",
    output: "出力",
    cacheWrite: "キャッシュ書込",
    cacheRead: "キャッシュ読込",
    perMillion: "/ 100 万 Token",
    unavailable: "価格未設定",
    noMatches: "一致するモデルがありません。",
    catalogUnavailable:
      "ライブモデル一覧は一時的に利用できません。エンドポイント契約は引き続き確認できます。",
  },
  fr: {
    eyebrow: "Routage des modèles / catalogue en direct",
    title: "Une liste de modèles.",
    titleAccent: "Deux protocoles familiers.",
    intro:
      "Appelez les modèles pris en charge via Nubase avec le contrat OpenAI ou Anthropic déjà compris par votre client.",
    liveCatalog: "Catalogue en direct",
    configuredModels: "modèles configurés",
    endpointTitle: "Choisissez votre protocole",
    endpointIntro:
      "La clé Gateway reste identique. Seuls l’URL de base, le format et les en-têtes changent.",
    openAICompatible: "Compatible OpenAI",
    anthropicCompatible: "Compatible Anthropic",
    baseUrl: "URL de base",
    primaryRequest: "Requête principale",
    responsesRequest: "API Responses",
    tokenCount: "Compter les tokens",
    listModels: "Lister les modèles",
    copyExample: "Copier l’exemple",
    copied: "Copié",
    keyNote:
      "Utilisez une clé AI Gateway du projet. Ne l’exposez jamais dans le navigateur.",
    catalogTitle: "Modèles disponibles",
    catalogIntro:
      "Modèles explicitement configurés sur les routes actives. Prix en USD par million de tokens.",
    searchPlaceholder: "Rechercher un ID ou un nom",
    allProtocols: "Tous les protocoles",
    openAI: "OpenAI",
    anthropic: "Claude",
    models: "modèles",
    model: "Modèle",
    protocol: "Protocole",
    input: "Entrée",
    output: "Sortie",
    cacheWrite: "Écriture cache",
    cacheRead: "Lecture cache",
    perMillion: "/ 1 M tokens",
    unavailable: "Prix indisponible",
    noMatches: "Aucun modèle ne correspond.",
    catalogUnavailable:
      "Le catalogue en direct est temporairement indisponible. Les contrats d’API restent visibles.",
  },
};

export function getModelsCopy(lang: Lang): ModelsCopy {
  return copies[lang];
}
