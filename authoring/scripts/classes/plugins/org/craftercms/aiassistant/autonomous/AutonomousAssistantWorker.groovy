package plugins.org.craftercms.aiassistant.autonomous

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.ArrayList
import java.util.Collection
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.List
import java.util.Locale
import java.util.Map
import java.util.Collections
import java.util.Comparator
import java.util.Set
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import plugins.org.craftercms.aiassistant.llm.StudioAiLlmKind
import plugins.org.craftercms.aiassistant.llm.StudioAiLlmRuntimeFactory
import plugins.org.craftercms.aiassistant.llm.StudioAiRuntimeBuildRequest
import plugins.org.craftercms.aiassistant.orchestration.AiOrchestration
import plugins.org.craftercms.aiassistant.prompt.ToolPromptsSiteContext
import plugins.org.craftercms.aiassistant.rag.ExpertSkillVectorRegistry
import plugins.org.craftercms.aiassistant.tools.AiOrchestrationTools
import plugins.org.craftercms.aiassistant.tools.StudioToolOperations

/**
 * Runs a single autonomous assistant step. Requires a **tools-loop RestClient** session: built-in {@code openAI} (OpenAI vendor) / xAI /
 * DeepSeek / llama / genesis (gemini), or {@code script:…} site Groovy whose {@code buildSessionBundle} returns
 * {@code toolsLoopChatApiKey}, {@code resolvedChatModel}, and {@code toolsLoopChatBaseUrl}. **Claude** is not supported
 * for autonomous runs yet.
 * Always sends the Studio {@code tools[]} catalog and runs the native tool loop; for plain drafting without CMS
 * reads/writes in the same inner pass, the model should call the {@code GenerateTextNoTools} tool. Empty catalog,
 * missing {@link AutonomousAssistantRuntimeHooks} context, or LLM errors propagate.
 */
final class AutonomousAssistantWorker {

  private static final Logger log = LoggerFactory.getLogger(AutonomousAssistantWorker)

  /** Max human-task rows kept in memory; oldest rows are dropped after each merge. */
  static final int HUMAN_TASKS_MAX = 10

  /** Max entries for {@code pastRunReports} and {@code executionHistory} per agent (oldest dropped first). */
  static final int RUN_HISTORY_MAX = 50

  private AutonomousAssistantWorker() {}

  static void runStep(String siteId, String fullAgentId, Map definition) {
    Map state = AutonomousAssistantStateStore.getState(fullAgentId)
    if (state == null) {
      return
    }
    String initialStatus = state.get('status')?.toString()
    if (AutonomousAssistantStatus.DISABLED == initialStatus || AutonomousAssistantStatus.STOPPED == initialStatus ||
      AutonomousAssistantStatus.ERROR == initialStatus) {
      return
    }
    long t0 = System.currentTimeMillis()
    state.put('status', AutonomousAssistantStatus.RUNNING)
    ensureHumanTaskOwners(state, fullAgentId)
    AutonomousAssistantStateStore.putState(fullAgentId, state)
    try {
      String llm = (definition?.llm ?: 'openAI').toString().trim()
      String normLlm = StudioAiLlmKind.normalize(llm)
      if (!StudioAiLlmKind.supportsAutonomousNativeTools(normLlm)) {
        throw new IllegalStateException(
          "AutonomousAssistantWorker requires a tools-loop llm (openAI, xAI, deepSeek, llama, genesis/gemini) or script:… site Groovy LLM with tools-loop bundle fields; got llm='${llm}' (normalized='${normLlm}'). Claude (claude) is not supported for autonomous runs yet."
        )
      }
      String openAiImageExpertKey = AiOrchestration.resolveOpenAiApiKey(definition?.openAiApiKey)
      String chatApiKey = ''
      String model = ''
      String wireBaseUrl = ''
      String basePrompt = (definition?.prompt ?: 'You are a helpful assistant.').toString()
      boolean manageOtherAgentsHumanTasks = parseJsonBool(definition?.get('manageOtherAgentsHumanTasks'))
      String ownershipNote = manageOtherAgentsHumanTasks
        ? '\n\n**Human task ownership (this agent is allowed cross-agent work):** Each task row has `ownerAgentId` and optional `ownerName`. ' +
        'You may add tasks for another agent by setting `ownerAgentId` to their full agent id on a `humanTasks` object. ' +
        'You may dismiss/complete/reopen ids for any task present in the stored state JSON. Keep at most ~10 tasks owned by **this** agent id (`' +
        fullAgentId + '`) after merges; avoid touching other agents’ tasks unless intentional.'
        : '\n\n**Human task ownership (default):** Every `humanTasks` item you add is owned by **this** agent (`ownerAgentId` is forced to `' +
        fullAgentId + '`). `dismissHumanTaskIds`, `completeHumanTaskIds`, and `reopenHumanTaskIds` **only** apply to tasks whose `ownerAgentId` matches `' +
        fullAgentId + '` — do not try to change other agents’ tasks. Up to ' + HUMAN_TASKS_MAX + ' open tasks are kept for this agent (oldest dropped first).'
      String autonomousContract = basePrompt +
        '\n\nAfter reasoning, reply with **only** a single JSON object (no markdown fences) with keys: ' +
        '"report" (string summary of what you did), "nextStepRequired" (boolean), ' +
        '"stateNotes" (optional string for the next run), ' +
        '"humanTasks" (optional array of {"title":string,"prompt":string} — each **prompt** must be self-contained ' +
        'instructions a human can execute or paste into another assistant; use when a person must do something ' +
        'the autonomous agent cannot; omit or use [] when nothing is needed). ' +
        'You may include **optional** `assignedUsername` (Studio login) and `assignedName` (display label) on a ' +
        '`humanTasks` object when instructions say who should own the follow-up; omit both to leave the task unassigned.' +
        '\n\n**Site context for this run:** The user message includes an **OpenSearch digest** of indexed ' +
        'pages/components under `/site/website/` (paths, content types, titles). Use it when it helps your **mission** ' +
        '(from the instructions above — whatever mission the author configured for this agent): ' +
        'ground decisions in what already exists, avoid duplicating work or topics already covered on the site, ' +
        'and stay aligned with that mission. Ignore parts of the digest that are irrelevant to this run. ' +
        'For `humanTasks`, do not repeat prompts that already appear on non-dismissed tasks in the stored state. ' +
        'Add new `humanTasks` only when a person must do something you cannot; each `prompt` must be self-contained and actionable. ' +
        '**Human tasks vs mission:** use `humanTasks` only for real human-in-the-loop work (legal or business approval, CMS access you do not have, credentials, SME fact-check) or when the **author instructions** explicitly ask for a human step. ' +
        'Do **not** create tasks that merely offload or restate the primary mission configured for this agent (for example assigning a human to write an article when this agent is supposed to produce that content). ' +
        'If you can advance the mission without a human, return `"humanTasks": []`. ' +
        'Prefer zero or one new task per run unless the mission clearly needs more; if no new human follow-up is warranted, return `"humanTasks": []`.' +
        ownershipNote +
        '\n\n**Agent self-service (same JSON object, optional keys):** ' +
        '"dismissHumanTaskIds" (string[] of task ids), "completeHumanTaskIds" (string[]), "reopenHumanTaskIds" (string[]) — ' +
        'apply updates to existing human tasks in stored state before new `humanTasks` rows are merged. ' +
        '"stopSelf" (boolean) — if true, after this run the agent stays **stopped** (no further scheduled runs) until an author clicks **Start** for that agent.'
      String digest = ''
      try {
        digest = AutonomousSiteDigestBuilder.buildForSite(siteId)
      } catch (Throwable t) {
        digest = '(Digest error: ' + (t.message ?: t.class.simpleName) + ')'
      }
      Map slimState = slimStateForAutonomousLlmPrompt(state)
      String stateJson = JsonOutput.toJson(slimState)
      final int STATE_JSON_CAP = 56_000
      if (stateJson.length() > STATE_JSON_CAP) {
        stateJson = stateJson.substring(0, STATE_JSON_CAP) + '\n...[aiassistant: state JSON truncated for prompt size]'
      }
      String user =
        'Site: ' + (siteId ?: '') + '\nAgent id: ' + fullAgentId +
        '\nStored state JSON (summarized for prompt size; full state is kept server-side):\n' + stateJson +
        '\n\n--- Site index digest (Studio authoring OpenSearch) ---\n' + digest
      String assistant
      AutonomousAssistantRuntimeHooks.runWithCapturedSecurity {
        def app = AutonomousAssistantRuntimeHooks.applicationContext()
        if (app == null) {
          throw new IllegalStateException(
            'AutonomousAssistantWorker: Spring applicationContext is not registered; open Autonomous Assistants sync or status once from Studio (AutonomousAssistantRuntimeHooks.register).'
          )
        }
        String imageGenSpec = (definition?.imageGenerator ?: '').toString().trim()
        ToolPromptsSiteContext.enter(app, (siteId ?: '').toString())
        try {
        String imageModel = (definition?.imageModel ?: '').toString().trim()
        if (imageModel) {
          imageModel = AiOrchestration.normalizeOpenAiImagesApiModelId(imageModel)
        }
        StudioToolOperations studioOps = new StudioToolOperations(
          null,
          app,
          [siteId: (siteId ?: '').toString(), crafterSite: (siteId ?: '').toString()],
          null
        )
        List<Map> exNorm = ExpertSkillVectorRegistry.normalizeRequestExpertSkills(definition?.get('expertSkills'))
        AiOrchestration orch = new AiOrchestration(null, null, app, [siteId: (siteId ?: '').toString()], null)
        // Bundle only resolves API key / model / wire URL; tools are built below via buildWithDefaultWireConverter.
        // enableTools false avoids referencing Spring AI ToolCallResultConverter on the Studio script compile classpath.
        StudioAiRuntimeBuildRequest bundleReq = new StudioAiRuntimeBuildRequest(
          orchestration: orch,
          toolResultConverter: null,
          studioOps: studioOps,
          studioServletRequest: null,
          agentId: fullAgentId,
          chatId: null,
          llmNormalized: normLlm,
          openAiModelParam: definition?.llmModel?.toString(),
          openAiApiKeyFromRequest: definition?.openAiApiKey?.toString(),
          toolProgressListener: null,
          imageModelParam: definition?.imageModel?.toString(),
          imageGeneratorParam: imageGenSpec ?: null,
          fullSuppressRepoWrites: false,
          protectedFormItemPath: null,
          enableTools: false,
          agentEnabledBuiltInTools: null
        )
        Map bundle = StudioAiLlmRuntimeFactory.runtimeFor(normLlm).buildSessionBundle(bundleReq)
        chatApiKey = StudioAiLlmKind.toolsLoopChatApiKeyFromBundle(bundle)
        model = (bundle?.get('resolvedChatModel') ?: '').toString()
        wireBaseUrl = StudioAiLlmKind.toolsLoopChatBaseUrlFromBundle(bundle)
        if (!chatApiKey.trim() || !model.trim() || !wireBaseUrl.trim()) {
          throw new IllegalStateException(
            'Autonomous assistant requires a tools-loop session (toolsLoopChatApiKey, resolvedChatModel, toolsLoopChatBaseUrl). For script LLM, return these keys from buildSessionBundle.'
          )
        }
        String authoringStack = AiOrchestration.openAiAuthoringSystemOnlyForHeadless(
          (siteId ?: '').toString(),
          user,
          studioOps,
          openAiImageExpertKey,
          false,
          null,
          true,
          exNorm
        )
        String systemForTools = authoringStack +
          '\n\n--- Autonomous assistant run (JSON reply contract) ---\n' +
          autonomousContract +
          '\n\n**Studio tools:** This request includes the same CrafterCMS Studio tool catalog as interactive chat ' +
          '(function definitions on the API). Call tools when you need real repository or CMS data. ' +
          'For drafting or transforming text **without** using other CMS tools in that inner model call, use the ' +
          '**GenerateTextNoTools** tool (single completion; result includes **assistantText**). ' +
          'When you are finished using tools, your **final** assistant message must be **only** the single JSON object ' +
          'described above (no markdown fences, no commentary outside JSON).'
        Collection agentToolSubset = null
        def rawWl = definition?.get('enabledBuiltInTools')
        if (rawWl instanceof List && !((List) rawWl).isEmpty()) {
          Set s = new LinkedHashSet()
          for (Object o : (List) rawWl) {
            String nm = o?.toString()?.trim()
            if (nm) {
              s.add(nm)
            }
          }
          if (!s.isEmpty()) {
            agentToolSubset = s
          }
        }
        List tools = AiOrchestrationTools.buildWithDefaultWireConverter(
          studioOps,
          null,
          openAiImageExpertKey,
          imageModel ?: null,
          false,
          null,
          exNorm,
          model,
          normLlm,
          imageGenSpec ?: null,
          agentToolSubset
        )
        if (tools == null || tools.isEmpty()) {
          throw new IllegalStateException(
            'AutonomousAssistantWorker: Studio tool catalog is empty after AiOrchestrationTools.build (agentId=' + fullAgentId + ').'
          )
        }
        assistant = AiOrchestration.openAiHeadlessNativeToolsCompletion(
          chatApiKey,
          model,
          systemForTools,
          user,
          tools,
          fullAgentId,
          8192,
          180_000,
          'AutonomousAssistant',
          wireBaseUrl,
          bundle
        )
        } finally {
          ToolPromptsSiteContext.exit()
        }
      }
      Map parsed = tryParseJsonObject(assistant)
      String report = parsed?.get('report')?.toString() ?: (assistant ?: '')
      boolean next = Boolean.TRUE.equals(parsed?.get('nextStepRequired')) ||
        'true'.equalsIgnoreCase(parsed?.get('nextStepRequired')?.toString())
      String notes = parsed?.get('stateNotes')?.toString()

      List reports = (List) (state.get('pastRunReports') ?: [])
      reports.add([
        at       : new Date().toInstant().toString(),
        summary  : report.length() > 4000 ? report.substring(0, 4000) + '…' : report,
        model    : model
      ])
      while (reports.size() > RUN_HISTORY_MAX) {
        reports.remove(0)
      }
      List hist = (List) (state.get('executionHistory') ?: [])
      hist.add([at: new Date().toInstant().toString(), ms: System.currentTimeMillis() - t0, next: next])
      while (hist.size() > RUN_HISTORY_MAX) {
        hist.remove(0)
      }

      state.put('pastRunReports', reports)
      state.put('executionHistory', hist)
      state.put('lastRunMillis', System.currentTimeMillis())
      state.put('lastRunIso', new Date().toInstant().toString())
      state.put('nextStepRequired', next)
      state.put('lastStateNotes', notes)
      applyModelToolJsonInMemory(state, parsed, fullAgentId, manageOtherAgentsHumanTasks)
      mergeHumanTasksFromAiResponse(state, parsed, fullAgentId, manageOtherAgentsHumanTasks)
      ensureHumanTaskOwners(state, fullAgentId)
      trimHumanTasksInState(state, HUMAN_TASKS_MAX, fullAgentId, manageOtherAgentsHumanTasks)
      boolean stopSelf = Boolean.TRUE.equals(parsed?.get('stopSelf')) ||
        'true'.equalsIgnoreCase(parsed?.get('stopSelf')?.toString())
      state.put('status', stopSelf ? AutonomousAssistantStatus.STOPPED : AutonomousAssistantStatus.WAITING)
      if (stopSelf) {
        state.put('manualStop', Boolean.TRUE)
      } else {
        state.put('manualStop', Boolean.FALSE)
      }
      state.remove('lastError')
      AutonomousAssistantStateStore.putState(fullAgentId, state)
    } catch (Throwable t) {
      log.warn('AutonomousAssistantWorker failed agentId={}: {}', fullAgentId, t.message, t)
      List reports = (List) (state.get('pastRunReports') ?: [])
      reports.add([
        at     : new Date().toInstant().toString(),
        summary: 'ERROR: ' + (t.message ?: t.class.simpleName),
        model  : definition?.llmModel
      ])
      while (reports.size() > RUN_HISTORY_MAX) {
        reports.remove(0)
      }
      state.put('pastRunReports', reports)
      boolean stopOnFailure = parseStopOnFailure(definition)
      String stack = stackTraceSnippet(t, 12_000)
      state.put(
        'lastError',
        [
          message        : (t.message ?: t.class.simpleName),
          at             : Instant.now().toString(),
          exceptionClass : t.class.name,
          stackTrace     : stack,
          stopOnFailure  : stopOnFailure
        ]
      )
      if (stopOnFailure) {
        state.put('status', AutonomousAssistantStatus.ERROR)
        state.put('nextStepRequired', Boolean.FALSE)
        state.put('manualStop', Boolean.FALSE)
      } else {
        state.put('status', AutonomousAssistantStatus.WAITING)
        state.put('nextStepRequired', Boolean.TRUE)
        state.put('manualStop', Boolean.FALSE)
      }
      AutonomousAssistantStateStore.putState(fullAgentId, state)
    }
  }

  /**
   * Copy of in-memory state for the LLM user message only — avoids huge {@code pastRunReports} / {@code humanTasks}
   * / {@code lastError.stackTrace} blowing the OpenAI context window alongside tool results.
   */
  private static Map slimStateForAutonomousLlmPrompt(Map state) {
    if (state == null) {
      return [:]
    }
    Map out = new LinkedHashMap()
    for (String k : ['status', 'nextStepRequired', 'lastRunMillis', 'lastRunIso', 'lastStateNotes', 'agentId', 'displayName']) {
      if (state.containsKey(k)) {
        out.put(k, state.get(k))
      }
    }
    Object le = state.get('lastError')
    if (le instanceof Map) {
      Map lm = new LinkedHashMap((Map) le)
      Object st = lm.get('stackTrace')
      if (st != null && st.toString().length() > 3000) {
        lm.put('stackTrace', st.toString().substring(0, 3000) + '…')
      }
      out.put('lastError', lm)
    }
    Object ht = state.get('humanTasks')
    if (ht instanceof List) {
      List L = (List) ht
      int from = Math.max(0, L.size() - 15)
      List slimHt = []
      for (int i = from; i < L.size(); i++) {
        Object o = L.get(i)
        if (!(o instanceof Map)) {
          continue
        }
        Map t = new LinkedHashMap((Map) o)
        Object pr = t.get('prompt')
        if (pr != null && pr.toString().length() > 1500) {
          t.put('prompt', pr.toString().substring(0, 1500) + '…')
        }
        slimHt.add(t)
      }
      out.put('humanTasks', slimHt)
    }
    Object pr = state.get('pastRunReports')
    if (pr instanceof List) {
      List L = (List) pr
      int from = Math.max(0, L.size() - 8)
      List slimPr = []
      for (int i = from; i < L.size(); i++) {
        Object o = L.get(i)
        if (!(o instanceof Map)) {
          continue
        }
        Map m = new LinkedHashMap((Map) o)
        Object sum = m.get('summary')
        if (sum != null && sum.toString().length() > 800) {
          m.put('summary', sum.toString().substring(0, 800) + '…')
        }
        slimPr.add(m)
      }
      out.put('pastRunReports', slimPr)
    }
    Object eh = state.get('executionHistory')
    if (eh instanceof List) {
      List L = (List) eh
      int from = Math.max(0, L.size() - 25)
      out.put('executionHistory', new ArrayList(L.subList(from, L.size())))
    }
    out
  }

  /** When {@code true} (default), a failed run sets {@code status: error} until cleared; when {@code false}, state stays {@code waiting} with {@code nextStepRequired} so the next supervisor tick retries. */
  private static boolean parseStopOnFailure(Map definition) {
    try {
      Object v = definition?.get('stopOnFailure') ?: definition?.get('stop_on_failure')
      if (v == null) {
        return true
      }
      if (v instanceof Boolean) {
        return (Boolean) v
      }
      String s = v.toString().trim().toLowerCase()
      if (s == 'false' || s == '0' || s == 'no') {
        return false
      }
      return true
    } catch (Throwable ignored) {
      return true
    }
  }

  private static String stackTraceSnippet(Throwable t, int maxLen) {
    if (t == null || maxLen <= 0) {
      return ''
    }
    try {
      StringWriter sw = new StringWriter()
      PrintWriter pw = new PrintWriter(sw)
      t.printStackTrace(pw)
      pw.close()
      String s = sw.toString()
      if (s.length() > maxLen) {
        return s.substring(0, maxLen) + '…'
      }
      return s
    } catch (Throwable ignored) {
      return ''
    }
  }

  /**
   * Applies optional model-driven human-task updates on the in-memory {@code state} map (same JSON reply as
   * {@code humanTasks} append) so we do not overwrite this run’s report/history with a stale {@code putState}.
   */
  private static void applyModelToolJsonInMemory(Map state, Map parsed, String fullAgentId, boolean manageOtherAgentsHumanTasks) {
    if (state == null || parsed == null) {
      return
    }
    applyTaskIdsOnTaskList(state, parsed.get('dismissHumanTaskIds'), 'dismissed', fullAgentId, manageOtherAgentsHumanTasks)
    applyTaskIdsOnTaskList(state, parsed.get('completeHumanTaskIds'), 'done', fullAgentId, manageOtherAgentsHumanTasks)
    applyTaskIdsOnTaskList(state, parsed.get('reopenHumanTaskIds'), 'open', fullAgentId, manageOtherAgentsHumanTasks)
  }

  private static List<Map> cloneHumanTasks(Object cur) {
    List<Map> tasks = new ArrayList<>()
    if (cur instanceof List) {
      for (Object o : (List) cur) {
        if (o instanceof Map) {
          tasks.add(new LinkedHashMap((Map) o))
        }
      }
    }
    tasks
  }

  private static void applyTaskIdsOnTaskList(
    Map state,
    Object rawIds,
    String newStatus,
    String fullAgentId,
    boolean manageOtherAgentsHumanTasks
  ) {
    if (!(rawIds instanceof List) || !newStatus?.trim()) {
      return
    }
    List<Map> tasks = cloneHumanTasks(state.get('humanTasks'))
    String ns = newStatus.trim().toLowerCase(Locale.ROOT)
    for (Object idObj : (List) rawIds) {
      String tid = idObj?.toString()?.trim()
      if (!tid) {
        continue
      }
      for (Map t : tasks) {
        if (tid.equals(t?.get('id')?.toString())) {
          String owner = t?.get('ownerAgentId')?.toString()?.trim()
          if (!manageOtherAgentsHumanTasks && owner && !fullAgentId.equals(owner)) {
            log.info('AutonomousAssistantWorker skip task status change (cross-agent) taskId={} owner={} runner={}', tid, owner, fullAgentId)
            break
          }
          t.put('status', ns)
          t.put('updatedAt', Instant.now().toString())
          break
        }
      }
    }
    state.put('humanTasks', tasks)
  }

  /**
   * Appends new open human tasks from the model; skips prompts that duplicate an existing non-dismissed task.
   */
  private static void mergeHumanTasksFromAiResponse(Map state, Map parsed, String fullAgentId, boolean manageOtherAgentsHumanTasks) {
    if (state == null || parsed == null) {
      return
    }
    Object rawList = parsed.get('humanTasks')
    if (!(rawList instanceof List) || ((List) rawList).isEmpty()) {
      return
    }
    List<Map> existing = cloneHumanTasks(state.get('humanTasks'))
    ensureHumanTaskOwnersOnList(existing, fullAgentId)
    Set<String> seen = new HashSet<>()
    for (Map t : existing) {
      String st = t?.get('status')?.toString()?.trim()?.toLowerCase(Locale.ROOT)
      if ('dismissed'.equals(st)) {
        continue
      }
      String p = t?.get('prompt')?.toString()?.trim()
      if (p) {
        seen.add(p.toLowerCase(Locale.ROOT))
      }
    }
    for (Object o : (List) rawList) {
      if (!(o instanceof Map)) {
        continue
      }
      Map tm = (Map) o
      String pr = tm.get('prompt')?.toString()?.trim()
      if (!pr) {
        continue
      }
      String norm = pr.toLowerCase(Locale.ROOT)
      if (seen.contains(norm)) {
        continue
      }
      seen.add(norm)
      String title = tm.get('title')?.toString()?.trim()
      if (!title) {
        title = tm.get('label')?.toString()?.trim()
      }
      String tid = 't-' + System.nanoTime() + '-' + (int) (Math.random() * 1_000_000)
      String effectiveOwner = fullAgentId
      if (manageOtherAgentsHumanTasks) {
        String declared = tm.get('ownerAgentId')?.toString()?.trim()
        if (declared) {
          effectiveOwner = declared
        }
      }
      String ownerName = tm.get('ownerName')?.toString()?.trim()
      if (!ownerName) {
        ownerName = humanReadableAgentSuffix(effectiveOwner)
      }
      Map newRow = [
        id           : tid,
        ownerAgentId : effectiveOwner,
        ownerName    : ownerName,
        title        : title ?: '',
        prompt       : pr,
        status       : 'open',
        createdAt    : Instant.now().toString()
      ]
      applyAssigneeFromModelToHumanTaskRow(newRow, tm)
      existing.add(newRow)
    }
    state.put('humanTasks', existing)
  }

  /**
   * Copies optional Studio assignee from a model {@code humanTasks} map entry ({@code assignedUsername} /
   * {@code assignedName}, or aliases {@code assigneeUsername} / {@code assigneeName}) after sanitizing.
   */
  private static void applyAssigneeFromModelToHumanTaskRow(Map row, Map tm) {
    if (row == null || tm == null) {
      return
    }
    String raw =
      firstNonBlank(tm.get('assignedUsername'), tm.get('assigneeUsername'))?.toString()?.trim()
    String user = sanitizeStudioUsernameForTaskAssignee(raw)
    if (!user) {
      return
    }
    String name = firstNonBlank(tm.get('assignedName'), tm.get('assigneeName'))?.toString()?.trim()
    if (!name) {
      name = user
    } else if (name.length() > 200) {
      name = name.substring(0, 200)
    }
    row.put('assignedUsername', user)
    row.put('assignedName', name)
  }

  private static Object firstNonBlank(Object a, Object b) {
    String sa = a?.toString()?.trim()
    if (sa) {
      return sa
    }
    String sb = b?.toString()?.trim()
    sb ? sb : null
  }

  /** Studio usernames: trim, strip whitespace inside, strip angle brackets, length cap. */
  private static String sanitizeStudioUsernameForTaskAssignee(String raw) {
    if (!raw?.trim()) {
      return ''
    }
    String t = raw.trim().replaceAll(/\s/, '')
    t = t.replace('<', '').replace('>', '')
    if (!t) {
      return ''
    }
    if (t.length() > 128) {
      t = t.substring(0, 128)
    }
    t
  }

  /**
   * Keeps at most {@code max} tasks by removing the oldest (by {@code createdAt}) first.
   */
  private static void trimHumanTasksToMax(List<Map> tasks, int max) {
    if (tasks == null || tasks.size() <= max) {
      return
    }
    try {
      Collections.sort(
        tasks,
        new Comparator<Map>() {
          @Override
          int compare(Map a, Map b) {
            String ca = a?.get('createdAt')?.toString() ?: ''
            String cb = b?.get('createdAt')?.toString() ?: ''
            ca.compareTo(cb)
          }
        }
      )
    } catch (Throwable ignored) {}
    while (tasks.size() > max) {
      tasks.remove(0)
    }
  }

  private static void trimHumanTasksInState(Map state, int maxOwn, String fullAgentId, boolean allowForeignOwned) {
    if (state == null) {
      return
    }
    Object cur = state.get('humanTasks')
    if (!(cur instanceof List)) {
      return
    }
    List<Map> tasks = new ArrayList<>()
    for (Object o : (List) cur) {
      if (o instanceof Map) {
        tasks.add(new LinkedHashMap((Map) o))
      }
    }
    ensureHumanTaskOwnersOnList(tasks, fullAgentId)
    List<Map> own = new ArrayList<>()
    List<Map> foreign = new ArrayList<>()
    for (Map t : tasks) {
      String o = t?.get('ownerAgentId')?.toString()?.trim() ?: fullAgentId
      if (fullAgentId.equals(o)) {
        own.add(t)
      } else {
        foreign.add(t)
      }
    }
    if (!allowForeignOwned) {
      foreign.clear()
    } else {
      trimHumanTasksToMax(foreign, Math.min(maxOwn, 10))
    }
    trimHumanTasksToMax(own, maxOwn)
    List<Map> merged = new ArrayList<>(own)
    merged.addAll(foreign)
    state.put('humanTasks', merged)
  }

  private static boolean parseJsonBool(Object v) {
    if (Boolean.TRUE.equals(v)) {
      return true
    }
    if (v instanceof String) {
      return 'true'.equalsIgnoreCase(((String) v).trim()) || '1'.equals(((String) v).trim())
    }
    return false
  }

  private static void ensureHumanTaskOwners(Map state, String fullAgentId) {
    if (state == null) {
      return
    }
    Object raw = state.get('humanTasks')
    if (!(raw instanceof List)) {
      return
    }
    List<Map> out = new ArrayList<>()
    for (Object o : (List) raw) {
      if (o instanceof Map) {
        Map t = new LinkedHashMap((Map) o)
        if (!t.get('ownerAgentId')?.toString()?.trim()) {
          t.put('ownerAgentId', fullAgentId)
        }
        if (!t.get('ownerName')?.toString()?.trim()) {
          t.put('ownerName', humanReadableAgentSuffix(t.get('ownerAgentId')?.toString() ?: fullAgentId))
        }
        out.add(t)
      }
    }
    state.put('humanTasks', out)
  }

  private static void ensureHumanTaskOwnersOnList(List<Map> tasks, String fullAgentId) {
    if (tasks == null) {
      return
    }
    for (Map t : tasks) {
      if (!t.get('ownerAgentId')?.toString()?.trim()) {
        t.put('ownerAgentId', fullAgentId)
      }
      if (!t.get('ownerName')?.toString()?.trim()) {
        t.put('ownerName', humanReadableAgentSuffix(t.get('ownerAgentId')?.toString() ?: fullAgentId))
      }
    }
  }

  private static String humanReadableAgentSuffix(String fullAgentId) {
    if (!fullAgentId?.trim()) {
      return ''
    }
    String s = fullAgentId.trim()
    int i = s.lastIndexOf('-')
    return i >= 0 ? s.substring(i + 1) : s
  }

  private static Map tryParseJsonObject(String raw) {
    if (!raw?.trim()) {
      return null
    }
    String s = raw.trim()
    int b = s.indexOf('{')
    int e = s.lastIndexOf('}')
    if (b >= 0 && e > b) {
      s = s.substring(b, e + 1)
    }
    try {
      Object o = new JsonSlurper().parseText(s)
      return o instanceof Map ? (Map) o : null
    } catch (Throwable ignored) {
      null
    }
  }
}
