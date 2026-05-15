# Maintainer review checklist (lessons learned)

This document captures **recurring review themes** (performance, correctness, memory, security, accessibility) that have come up during automated and human review of this plugin. Use it as a **pre-merge mental model** when touching the areas below—not as a substitute for reading `spec.md` or product constraints.

## When to add a new entry (anti-pattern → preferred pattern)

Whenever **any** review (human, bot, or local audit) surfaces a **systemic** issue—not a one-off typo—capture it here so the same mistake is not reintroduced:

1. **Anti-pattern** — What we were doing wrong (symptoms, why it hurts: perf, security, correctness, a11y, etc.).
2. **Preferred pattern** — What we do instead (concrete enough to apply in new code; link types/files when helpful).

Put new bullets in the **right section** (React/TS vs Groovy) or add a small `###` subsection if the topic is new. Keep entries **short**; if the lesson becomes a **product or wire contract**, also mirror it in **`spec.md`** (or the relevant user-facing doc) in the same change set.

## React / TypeScript (`sources/src`)

### Persistence and streaming

- **Do not write `localStorage` on every `messages` change during SSE.** High-frequency updates (token deltas, tool progress) cause UI jank and can exhaust quota quickly.
- **Pattern we use:** persist only when **no message is streaming** (`isStreaming`), and **debounce** idle saves (e.g. a few hundred ms) so burst edits still coalesce.
- **Teardown:** if cleanup **only** `clearTimeout`s the debounce, the last idle conversation can be lost on unmount or fast navigation—**flush one save** when canceling a pending timer, using a **snapshot ref** (`siteId` / `agentId` / `chatId` / `messages` captured when the timer was scheduled) so agent switches do not write the wrong site’s blob.

### In-memory debug / support buffers

- **Bound any append-only log** (e.g. raw SSE lines + client send markers). Unbounded arrays can grow to tens of MB in long sessions.
- **Pattern we use:** cap by line count and drop from the **head** (FIFO) so recent context stays available.

### Clipboard and sensitive strings

- **“Copy full session log”** can include wire prompts and metadata not shown in the chat UI. Treat it as **potentially sensitive**.
- **Pattern we use:** best-effort **redaction** on copy (e.g. obvious `authorization` / `bearer` / `token` / `previewToken`-shaped JSON fragments). Prefer **not** logging secrets in the first place.

### Studio identity in TinyMCE and other non-Redux shells

- **Never hard-code usernames** for `AiAssistantProps.userName` (or similar). Attribution and downstream logic depend on the real Studio user.
- **Pattern we use:** read from `craftercms.getStore()?.getState()?.user?.username` with a safe fallback (e.g. `anonymous`) when the store is missing.
- **Imports:** prefer **extensionless** ESM paths (`./Foo`, `import type { … } from './Bar'`) so `moduleResolution` / bundlers stay predictable across Rollup and Studio.

### Caches that hold Promises

- **`Map<string, Promise<…>>` dedupe caches** can pin completed work for the whole authoring session if entries are never removed.
- **Pattern we use:** **evict the key on success** (errors may still delete so callers can retry). Optional TTL is fine if you need short-lived dedupe.

### Markdown, images, and HTML preview

- **Sanitization:** keep `rehype-sanitize` aligned with what we actually emit; document why extra protocols (e.g. `data` / `blob` for inline images) are allowed.
- **No iframes from markdown** as a first-class embed path; constrained **sandboxed** HTML preview is separate.
- **HTML preview iframe:** an **empty `sandbox`** is intentional (no `allow-scripts`, no `allow-same-origin`)—document that so future edits do not “helpfully” widen it.
- **Images:** `loading="lazy"` on chat images reduces layout thrash on long answers.

### Accessibility

- Prefer **`aria-live`** (and existing heartbeat / tool-progress regions) for streaming updates so screen readers announce progress **without** moving focus out of the prompt field—automatically focusing the transcript after send hurts keyboard users who expect to type the next prompt.
- If you add a “jump to transcript” behavior, keep it **explicit** (author-triggered), not automatic on every send.

### TypeScript and timers

- In **browser** code, `window.setTimeout` / `clearTimeout` IDs are **`number`** in typical DOM typings. Avoid `ReturnType<typeof setTimeout>` for refs that hold browser timer ids when the project also pulls **Node** typings (union with `NodeJS.Timeout` causes assignability noise). Prefer `number | null` / `number | undefined` for those refs.

---

## Groovy / Studio scripts (`authoring/scripts/classes/…`)

Patterns that have bitten us in review or production:

- **`try` / `catch` / `finally`:** every `try` in Groovy must have **`catch` and/or `finally`**. A stray outer `try { … }` with only a closing `}` fails compilation and can surface as obscure `GroovyBugError` during unrelated script loads.
- **Thread pool construction:** if JVM overrides can shrink **`maximumPoolSize`** below the default **`corePoolSize`**, **clamp core ≤ max** before `new ThreadPoolExecutor(…)`.
- **`CallerRunsPolicy` on pools fed from a single scheduler thread:** under saturation, work can run **on the caller** and stall ticks. Prefer a policy that **does not** inline work on that thread (e.g. log-and-drop, `DiscardOldestPolicy`, or explicit queue + bounded behavior), and document the trade-off.
- **SSE terminal events:** multiple threads (worker vs servlet) can race to emit **completed / error** frames. Use a **single-winner** pattern (`AtomicBoolean.compareAndSet(false, true)`) **only** around the terminal **`metadata.completed`** (or error-with-completed) write—not around the last assistant **text** chunk, or a servlet path that claimed first would **silently drop** a valid reply on a still-connected client.
- **Tools-loop diag session vs legacy global phase:** when a worker thread **binds** a per-stream session id, **`crafterQToolWorkerDiagSessionEnd`** must **not** clear the global legacy `CRAFTERQ_TOOL_WORKER_DIAG_PHASE_REF`—only unbound / fallback workers own that slot; clearing it from a bound worker can erase another thread’s advertised phase.
- **Defensive copies of nested state:** `new LinkedHashMap(existing)` is **shallow**; nested lists/maps (e.g. `humanTasks`, `executionHistory`) stay **aliased**. For in-memory stores returned to callers, use **deep copy** helpers for get/put/merge/snapshot paths when mutation safety matters.

---

## Related docs

| Topic | Document |
|-------|----------|
| Stream / SSE contracts | [stream-endpoint-design.md](stream-endpoint-design.md), [chat-and-tools-runtime.md](chat-and-tools-runtime.md) |
| Form control / `yarn package` verify | [studio-plugins-guide.md](../using-and-extending/studio-plugins-guide.md), repo `.cursor/rules/crafterq-form-panel-contract.mdc` |
| TinyMCE wiring (admin) | [tinymce-integration.md](../using-and-extending/tinymce-integration.md) |

When a review item becomes a **stable invariant** of the product (author-visible or contractual), mirror the requirement in **`spec.md`** (or the relevant user-facing doc) in the same change set where appropriate. **Style-only or one-off nits** usually do not need a new checklist row.
