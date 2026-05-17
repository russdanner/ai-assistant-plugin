# Reference: Spring AI Completions API With Tools

**Internals** — archived pattern reference. **Doc index:** [README.md](../README.md).

This document preserves an older example of using Spring AI for chat completions with function/tool callbacks (no RAG). Tools are currently handled a different way in this plugin; this is for reference when integrating a similar pattern.

## Dependencies (Groovy @Grab)

```groovy
@GrabResolver(name='custom', root='https://repo.spring.io/snapshot', m2Compatible='true')
@Grab(group='org.springframework.ai', module='spring-ai-core', version='1.0.0-SNAPSHOT', initClass=false)
@Grab(group='org.springframework.ai', module='spring-ai-openai', version='1.0.0-SNAPSHOT', initClass=false)
@Grab(group='com.squareup.okhttp3', module='okhttp', version='4.12.0', initClass=false)
```

## Imports

```groovy
import java.util.*
import java.util.function.Function

import org.springframework.http.HttpHeaders
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClient.Builder
import org.springframework.web.reactive.function.client.WebClient

import org.springframework.ai.model.function.FunctionCallbackWrapper

import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.chat.client.DefaultChatClientBuilder
import org.springframework.ai.chat.messages.UserMessage

import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest

import org.springframework.stereotype.Component
import org.springframework.core.io.DefaultResourceLoader

```

## Chat Client With Tools (Function Calling Only)

- **Chat model**: `OpenAiChatModel` + `OpenAiChatOptions` (e.g. `gpt-4o-mini`).
- **Tools**: Wrapped with `FunctionCallbackWrapper.builder(yourTool).withName("...").withDescription("...").withResponseConverter(...).build()`.
- **Call**: `chatClient.prompt().user(userPrompt).functions(checkAvailabilityFuncCallWrapper).call()`.

```groovy
public class AiChatClient {

    def chatClient
    def static oneInstance

    def static instance() {
        if (!this.oneInstance) {
            this.oneInstance = new AiChatClient()
            this.oneInstance.init()
        }
        return this.oneInstance
    }

    def prompt(userPrompt) {
        def checkAvailabilityFuncCallWrapper = FunctionCallbackWrapper.builder(new CheckAvailabilityTool())
            .withName("CheckAvailability")
            .withDescription("Returns true if rooms are available")
            .withResponseConverter((response) -> "" + response.available())
            .build()

        def response = this.chatClient.prompt()
            .user(userPrompt)
            .functions(checkAvailabilityFuncCallWrapper)
            .call()
        def answer = response.content()

        return answer
    }

    def init() {
        def openAIKey = "sk-..."
        def webClientBuilder = WebClient.builder()
        def restClientBuilder = RestClient.builder()
        restClientBuilder.defaultHeaders { it.set(HttpHeaders.ACCEPT_ENCODING, "gzip, deflate") }

        def llmApi = new OpenAiApi("https://api.openai.com", openAIKey, restClientBuilder, webClientBuilder)

        def llmChatOptions = OpenAiChatOptions.builder().withModel("gpt-4o-mini").build()

        def chatModel = new OpenAiChatModel(llmApi, llmChatOptions)

        this.chatClient = new DefaultChatClientBuilder(chatModel).build()
    }

    // tool-only example omitted: no vector store / embeddings needed
}
```

## Example Tool (Function Callback)

```groovy
@Component
public class CheckAvailabilityTool implements Function<CheckAvailabilityTool.Request, CheckAvailabilityTool.Response> {

    public CheckAvailabilityTool() {}
    public CheckAvailabilityTool(boolean available) {}

    public record Request(String date) {}
    public record Response(CheckAvailabilityTool tool, java.lang.Boolean available) {}

    @Override
    public Response apply(Request request) {
        return new Response(true)
    }
}
```

## Summary

- **Completions**: `DefaultChatClientBuilder(chatModel).build()` then `.prompt().user(...).call()`.
- **Tools**: Implement `Function<Request, Response>`, wrap with `FunctionCallbackWrapper`, pass to `.functions(...)`.
- **Note**: This is tool-calling only (no RAG).

For this plugin, tool execution is handled in `AiOrchestration` / `AiOrchestrationTools` (Spring AI native tools and RestClient loops). See `AiOrchestration` for current server-side orchestration.
