/** Default Groovy when creating a site user tool (see {@code StudioAiUserSiteTools#invokeRegisteredTool}). */
export const AI_ASSISTANT_USER_TOOL_GROOVY_STUB = `// InvokeSiteUserTool — bindings: studio, args, toolId, siteId, log
[
  ok     : true,
  message: 'Replace this stub — return a Map (ok, message, data, etc.).'
]
`;

/** Default Groovy for script image generator {@code script:{id}} (see {@code StudioAiScriptImageGenLoader}). */
export const AI_ASSISTANT_IMAGEGEN_GROOVY_STUB = `{ Map input, Map context ->
  String p = (input?.prompt ?: '').toString()
  if (p.length() > 120) {
    p = p.substring(0, 120) + '…'
  }
  [
    error  : true,
    message: 'Stub image generator — implement (input, context) -> Map per GenerateImage contract. prompt=' + p
  ]
}
`;

/** Default Groovy for script LLM {@code script:id} (see {@code StudioAiScriptLlmLoader} and demo runtime). */
export const AI_ASSISTANT_LLM_RUNTIME_GROOVY_STUB = `import plugins.org.craftercms.aiassistant.llm.OpenAiSpringAiLlmRuntime
import plugins.org.craftercms.aiassistant.llm.StudioAiLlmKind
import plugins.org.craftercms.aiassistant.llm.StudioAiRuntimeBuildRequest

[
  supportsNativeStudioTools: true,
  normalizedKind          : StudioAiLlmKind.SCRIPT_LLM_PREFIX + llmId,
  buildSessionBundle      : { StudioAiRuntimeBuildRequest r ->
    StudioAiRuntimeBuildRequest sub = new StudioAiRuntimeBuildRequest()
    sub.orchestration = r.orchestration
    sub.toolResultConverter = r.toolResultConverter
    sub.studioOps = r.studioOps
    sub.crafterQServletRequest = r.crafterQServletRequest
    sub.agentId = r.agentId
    sub.chatId = r.chatId
    sub.llmNormalized = StudioAiLlmKind.OPENAI_NATIVE
    sub.openAiModelParam = r.openAiModelParam
    sub.openAiApiKeyFromRequest = r.openAiApiKeyFromRequest
    sub.toolProgressListener = r.toolProgressListener
    sub.imageModelParam = r.imageModelParam
    sub.imageGeneratorParam = r.imageGeneratorParam
    sub.fullSuppressRepoWrites = r.fullSuppressRepoWrites
    sub.protectedFormItemPath = r.protectedFormItemPath
    sub.enableTools = r.enableTools
    sub.agentEnabledBuiltInTools = r.agentEnabledBuiltInTools
    OpenAiSpringAiLlmRuntime.INSTANCE.buildSessionBundle(sub)
  }
]
`;

export const AI_ASSISTANT_USER_TOOLS_REGISTRY_STUB = `{
  "tools": [
    {
      "id": "example_tool",
      "script": "ExampleTool.groovy",
      "description": "Example InvokeSiteUserTool registration"
    }
  ]
}
`;

/**
 * Starter {@code config/studio/scripts/aiassistant/config/tools.json} — built-in tool allow/deny and MCP
 * (see {@code StudioAiAssistantProjectConfig}).
 */
export const AI_ASSISTANT_TOOLS_JSON_STUB = `{
  "disabledBuiltInTools": [],
  "enabledBuiltInTools": [],
  "mcpEnabled": false,
  "mcpServers": [
    {
      "id": "example",
      "url": "https://your-mcp-host.example/mcp",
      "headers": {},
      "readTimeoutMs": 120000
    }
  ],
  "disabledMcpTools": []
}
`;

/** Starter markdown when creating a site override for {@code config/studio/scripts/aiassistant/prompts/&lt;KEY&gt;.md}. */
export function aiAssistantToolPromptMarkdownStub(key: string): string {
  return `# ${key}

Non-empty markdown replaces the built-in prompt for this key. Leave the file blank or delete it to keep the plugin default (see ToolPromptsLoader).

`;
}
