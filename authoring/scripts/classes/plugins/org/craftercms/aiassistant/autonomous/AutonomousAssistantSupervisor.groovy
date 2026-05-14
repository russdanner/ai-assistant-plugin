package plugins.org.craftercms.aiassistant.autonomous

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Prototype supervisor: wakes on a fixed interval ({@link #TICK_MS}) and evaluates every registered agent.
 * <p><b>Important:</b> {@link #TICK_MS} is only how often we <em>check</em> — it is <b>not</b> each agent’s run cadence.
 * Per-agent timing uses {@code definition.schedule} → {@link AutonomousScheduleProbe#inferPeriodMillis} plus
 * {@code state.lastRunMillis} (and {@code nextStepRequired}) inside {@link #tick()}.
 */
final class AutonomousAssistantSupervisor {

  private static final Logger log = LoggerFactory.getLogger(AutonomousAssistantSupervisor)

  /** Global poll interval (ms): how often {@link #tick()} runs — not the same as any one agent’s {@code schedule}. */
  private static final long TICK_MS = 10_000L

  /** Off until an author calls {@link #enableSupervisor()} from the widget (tick thread may run but {@link #tick()} no-ops while false). */
  private static volatile boolean supervisorEnabled = false
  private static ScheduledExecutorService supervisorExec
  private static ExecutorService workerPool
  private static volatile ScheduledFuture<?> supervisorFuture
  private static final ConcurrentHashMap<String, Boolean> RUNNING = new ConcurrentHashMap<>()

  /** When set, {@link #haltSupervisorAfterAgentFailure} disabled the supervisor (legacy); worker failures no longer set this. */
  private static volatile String supervisorHaltReason = ''

  private AutonomousAssistantSupervisor() {}

  static synchronized void ensureStarted() {
    if (supervisorExec == null || supervisorExec.isShutdown()) {
      supervisorExec = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        Thread newThread(Runnable r) {
          Thread t = new Thread(r, 'aiassistant-autonomous-supervisor')
          t.setDaemon(true)
          t
        }
      })
    }
    if (workerPool == null || workerPool.isShutdown()) {
      workerPool = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        Thread newThread(Runnable r) {
          Thread t = new Thread(r, 'aiassistant-autonomous-worker')
          t.setDaemon(true)
          t
        }
      })
    }
    if (supervisorFuture == null || supervisorFuture.isCancelled()) {
      supervisorFuture = supervisorExec.scheduleAtFixedRate(
        { tick() },
        2,
        TICK_MS,
        TimeUnit.MILLISECONDS
      )
      log.info('AutonomousAssistantSupervisor started (tick every {} ms)', TICK_MS)
    }
  }

  static synchronized void disableSupervisor() {
    supervisorEnabled = false
    if (supervisorFuture != null) {
      supervisorFuture.cancel(false)
      supervisorFuture = null
    }
    log.info('AutonomousAssistantSupervisor disabled')
  }

  /**
   * Stops the supervisor schedule (legacy hook). Worker failures no longer call this so other agents keep running;
   * authors may still use **Stop system** / {@code disable_supervisor} explicitly.
   */
  static synchronized void haltSupervisorAfterAgentFailure(String reason) {
    disableSupervisor()
    supervisorHaltReason = (reason ?: 'Agent run failed').toString().trim()
    log.warn('AutonomousAssistantSupervisor halted: {}', supervisorHaltReason)
  }

  static String getSupervisorHaltReason() {
    supervisorHaltReason ?: ''
  }

  static synchronized void enableSupervisor() {
    supervisorEnabled = true
    supervisorHaltReason = ''
    // Always reschedule: a non-cancelled future can survive disable in edge cases; authors expect Start to arm ticks.
    if (supervisorFuture != null) {
      supervisorFuture.cancel(false)
      supervisorFuture = null
    }
    ensureStarted()
  }

  static synchronized void shutdownPools() {
    if (supervisorFuture != null) {
      supervisorFuture.cancel(false)
      supervisorFuture = null
    }
    if (supervisorExec != null) {
      supervisorExec.shutdown()
      try {
        supervisorExec.awaitTermination(5, TimeUnit.SECONDS)
      } catch (Throwable ignored) {}
      supervisorExec = null
    }
    if (workerPool != null) {
      workerPool.shutdown()
      try {
        workerPool.awaitTermination(10, TimeUnit.SECONDS)
      } catch (Throwable ignored) {}
      workerPool = null
    }
    log.info('AutonomousAssistantSupervisor pools shut down')
  }

  static synchronized void destroyInMemoryStore() {
    disableSupervisor()
    AutonomousAssistantStateStore.clearAll()
    AutonomousAssistantRegistry.clearAll()
    RUNNING.clear()
    supervisorHaltReason = ''
    AutonomousAssistantRuntimeHooks.clear()
    log.info('AutonomousAssistantSupervisor in-memory store cleared (re-sync agents from the UI after this)')
  }

  static void clearSite(String siteId) {
    if (!siteId?.trim()) {
      return
    }
    AutonomousAssistantRegistry.removeSite(siteId.trim())
    AutonomousAssistantStateStore.removeKeysForSite(siteId.trim())
  }

  static boolean isSupervisorEnabled() {
    supervisorEnabled
  }

  static Map statusSnapshot() {
    [
      supervisorEnabled   : supervisorEnabled,
      tickMs              : TICK_MS,
      supervisorRunning   : supervisorFuture != null && !supervisorFuture.isCancelled(),
      workerPoolActive    : workerPool != null && !workerPool.isTerminated(),
      supervisorHaltReason: getSupervisorHaltReason()
    ]
  }

  private static void tick() {
    if (!supervisorEnabled || workerPool == null) {
      return
    }
    try {
      Map sites = AutonomousAssistantRegistry.snapshotAllSites()
      long now = System.currentTimeMillis()
      for (Object siteKey : sites.keySet()) {
        String siteId = siteKey.toString()
        ConcurrentHashMap<String, Map> agents = AutonomousAssistantRegistry.agentsForSite(siteId)
        for (Map.Entry<String, Map> e : agents.entrySet()) {
          String agentId = e.key
          Map agentDef = e.value
          if (agentDef == null) {
            continue
          }
          Map st = AutonomousAssistantStateStore.getState(agentId)
          if (st == null) {
            continue
          }
          String stName = st.get('status')?.toString()
          if ('disabled'.equals(stName) || 'stopped'.equals(stName) || 'error'.equals(stName)) {
            continue
          }
          boolean nextStep = Boolean.TRUE.equals(st.get('nextStepRequired')) ||
            'true'.equalsIgnoreCase(st.get('nextStepRequired')?.toString())
          long last = 0L
          try {
            Object lr = st.get('lastRunMillis')
            if (lr instanceof Number) {
              last = ((Number) lr).longValue()
            }
          } catch (Throwable ignored) {}
          String sched = agentDef.get('schedule')?.toString()
          // Per-agent minimum spacing between runs (from cron-ish schedule), independent of TICK_MS.
          long period = AutonomousScheduleProbe.inferPeriodMillis(sched)
          boolean due = nextStep || (now - last >= period)
          if (!due) {
            continue
          }
          if (RUNNING.putIfAbsent(agentId, Boolean.TRUE) != null) {
            continue
          }
          workerPool.submit({
            try {
              AutonomousAssistantWorker.runStep(siteId, agentId, agentDef)
            } finally {
              RUNNING.remove(agentId)
            }
          } as Runnable)
        }
      }
    } catch (Throwable t) {
      log.warn('AutonomousAssistantSupervisor tick failed: {}', t.message)
    }
  }
}
