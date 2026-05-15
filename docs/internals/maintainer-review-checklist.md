# Maintainer review checklist (lessons learned)

This document captures **recurring review themes** (performance, correctness, memory, security, accessibility) that have come up during automated and human review of this plugin. Use it as a **pre-merge mental model** when touching the areas below—not as a substitute for reading `spec.md` or product constraints.

## React / TypeScript (`sources/src`)

### Persistence and streaming

- **Do not write `localStorage` on every `messages` change during SSE.** High-frequency updates (token deltas, tool progress) cause UI jank and can exhaust quota quickly.
- **Pattern we use:** persist only when **no message is streaming** (`isStreaming`), and **debounce** idle saves (e.g. a few hundred ms) so burst edits still coalesce.

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

- After sending a message, **move focus** to a sensible region of the transcript (e.g. a `Stack` with `tabIndex={-1}` and `aria-label="Conversation"`) so screen readers pick up streaming / heartbeat updates where `aria-live` is used.
- Use **`queueMicrotask` / `requestAnimationFrame`** if focus must run after React commits `setState`.

### TypeScript and timers

- In **browser** code, `window.setTimeout` / `clearTimeout` IDs are **`number`** in typical DOM typings. Avoid `ReturnType<typeof setTimeout>` for refs that hold browser timer ids when the project also pulls **Node** typings (union with `NodeJS.Timeout` causes assignability noise). Prefer `number | null` / `number | undefined` for those refs.

---

## Groovy / Studio scripts (`authoring/scripts/classes/…`)

Patterns that have bitten us in review or production:

- **`try` / `catch` / `finally`:** every `try` in Groovy must have **`catch` and/or `finally`**. A stray outer `try { … }` with only a closing `}` fails compilation and can surface as obscure `GroovyBugError` during unrelated script loads.
- **Thread pool construction:** if JVM overrides can shrink **`maximumPoolSize`** below the default **`corePoolSize`**, **clamp core ≤ max** before `new ThreadPoolExecutor(…)`.
- **`CallerRunsPolicy` on pools fed from a single scheduler thread:** under saturation, work can run **on the caller** and stall ticks. Prefer a policy that **does not** inline work on that thread (e.g. log-and-drop, `DiscardOldestPolicy`, or explicit queue + bounded behavior), and document the trade-off.
- **SSE terminal events:** multiple threads (worker vs servlet) can race to emit **completed / error** frames. Use a **single-winner** pattern (`AtomicBoolean.compareAndSet(false, true)`) before writing terminal SSE.
- **Defensive copies of nested state:** `new LinkedHashMap(existing)` is **shallow**; nested lists/maps (e.g. `humanTasks`, `executionHistory`) stay **aliased**. For in-memory stores returned to callers, use **deep copy** helpers for get/put/merge/snapshot paths when mutation safety matters.

---

## Related docs

| Topic | Document |
|-------|----------|
| Stream / SSE contracts | [stream-endpoint-design.md](stream-endpoint-design.md), [chat-and-tools-runtime.md](chat-and-tools-runtime.md) |
| Form control / `yarn package` verify | [studio-plugins-guide.md](../using-and-extending/studio-plugins-guide.md), repo `.cursor/rules/crafterq-form-panel-contract.mdc` |
| TinyMCE wiring (admin) | [tinymce-integration.md](../using-and-extending/tinymce-integration.md) |

When a review item becomes a **stable invariant** of the product (author-visible or contractual), mirror the requirement in **`spec.md`** (or the relevant user-facing doc) in the same change set where appropriate.
