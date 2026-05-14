/**
 * Built-in Studio AI orchestration tool names (Spring AI wire). Keep aligned with
 * `AiOrchestrationTools.groovy` {@code FunctionToolCallback.builder('…')}.
 * Use {@link STUDIO_AI_MCP_ALL_TOKEN} in agent JSON to retain every dynamic {@code mcp_*} tool after site policy.
 */
export const STUDIO_AI_MCP_ALL_TOKEN = 'mcp:*';

/** Checkbox order for Project Tools → AI Assistant Agents (subset may be omitted at runtime). */
export const STUDIO_AI_BUILTIN_TOOL_IDS: readonly string[] = [
  'GenerateTextNoTools',
  'TranslateContentBatch',
  'TranslateContentItem',
  'TransformContentSubgraph',
  'GetContentSubgraph',
  'GetContent',
  'ListContentTranslationScope',
  'ListStudioContentTypes',
  'GetContentTypeFormDefinition',
  'GetContentVersionHistory',
  'GetPreviewHtml',
  'FetchHttpUrl',
  'QueryExpertGuidance',
  'WriteContent',
  'ListPagesAndComponents',
  'update_template',
  'update_content',
  'update_content_type',
  'analyze_template',
  'publish_content',
  'ConsultCrafterQExpert',
  'ListCrafterQAgentChats',
  'GetCrafterQAgentChat',
  'GetCrafterizingPlaybook',
  'revert_change',
  'GenerateImage',
  'InvokeSiteUserTool',
  STUDIO_AI_MCP_ALL_TOKEN
] as const;

/** LLM values accepted by {@link StudioAiLlmKind#normalize} (POST / stream `llm`). */
export const STUDIO_AI_LLM_VENDOR_IDS: readonly string[] = [
  'openAI',
  'xAI',
  'deepSeek',
  'llama',
  'gemini',
  'claude',
  'crafterQ',
  'script'
] as const;

export type StudioAiLlmVendorId = (typeof STUDIO_AI_LLM_VENDOR_IDS)[number];

/** Default chat models for **tools-loop** vendors (UI hints; includes common OpenAI **vendor** defaults; server may accept others). */
export const STUDIO_AI_TOOLS_LOOP_CHAT_MODELS: readonly string[] = [
  'gpt-4o-mini',
  'gpt-4o',
  'gpt-4.1',
  'gpt-4.1-mini',
  'o3-mini',
  'o1',
  'o1-mini'
] as const;

/** @deprecated Use {@link STUDIO_AI_TOOLS_LOOP_CHAT_MODELS}. */
export const STUDIO_AI_OPENAI_WIRE_CHAT_MODELS = STUDIO_AI_TOOLS_LOOP_CHAT_MODELS;

export const STUDIO_AI_CLAUDE_CHAT_MODELS: readonly string[] = [
  'claude-3-5-sonnet-20241022',
  'claude-3-5-haiku-20241022',
  'claude-3-opus-20240229'
] as const;

export const STUDIO_AI_DEFAULT_IMAGE_MODEL = 'gpt-image-1';
