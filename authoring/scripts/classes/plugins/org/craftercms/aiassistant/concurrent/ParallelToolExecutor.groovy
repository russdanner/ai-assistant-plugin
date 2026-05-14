package plugins.org.craftercms.aiassistant.concurrent

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM-wide bounded executor for CrafterQ parallel server tool work (e.g. {@code TranslateContentBatch} cells).
 * <p>Uses named <strong>daemon</strong> threads (not Tomcat HTTP workers). Callers bound per-job parallelism with a
 * {@link java.util.concurrent.Semaphore}; this pool only provides stable capacity + a bounded queue so bursts do not
 * spawn unbounded short-lived pools or risk queue growth without limit.</p>
 */
final class ParallelToolExecutor {
  private static final Logger log = LoggerFactory.getLogger(ParallelToolExecutor.class)
  private static final AtomicInteger THREAD_SEQ = new AtomicInteger(1)
  private static volatile ThreadPoolExecutor INSTANCE

  private ParallelToolExecutor() {}

  private static int resolveIntProp(String sysKey, int defaultValue, int min, int max) {
    try {
      def p = System.getProperty(sysKey)?.toString()?.trim()
      if (p) {
        int v = Integer.parseInt(p)
        return Math.max(min, Math.min(max, v))
      }
    } catch (Throwable ignored) {}
    return defaultValue
  }

  static ThreadPoolExecutor executor() {
    ThreadPoolExecutor ex = INSTANCE
    if (ex != null && !ex.isShutdown()) {
      return ex
    }
    synchronized (ParallelToolExecutor.class) {
      ex = INSTANCE
      if (ex != null && !ex.isShutdown()) {
        return ex
      }
      int n = Math.max(1, Runtime.runtime.availableProcessors())
      int maxPool = resolveIntProp('crafterq.parallelToolPoolMax', Math.min(32, Math.max(8, n * 2)), 2, 64)
      int corePool = resolveIntProp('crafterq.parallelToolPoolCore', Math.min(maxPool, Math.max(2, n)), 1, maxPool)
      if (corePool > maxPool) {
        corePool = maxPool
      }
      int queueCap = resolveIntProp('crafterq.parallelToolPoolQueue', 512, 16, 4096)
      ThreadFactory tf = { Runnable r ->
        Thread t = new Thread(r, 'crafterq-parallel-tools-' + THREAD_SEQ.getAndIncrement())
        t.setDaemon(true)
        t.setUncaughtExceptionHandler(
          new Thread.UncaughtExceptionHandler() {
            void uncaughtException(Thread th, Throwable err) {
              log.error('Uncaught exception on CrafterQ parallel tool thread {}', th?.name, err)
            }
          })
        t
      } as ThreadFactory
      ex = new ThreadPoolExecutor(
        corePool,
        maxPool,
        120L,
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<Runnable>(queueCap),
        tf,
        new ThreadPoolExecutor.CallerRunsPolicy()
      )
      ex.allowCoreThreadTimeOut(true)
      INSTANCE = ex
      log.info(
        'ParallelToolExecutor: started core={} max={} queueCap={}',
        corePool,
        maxPool,
        queueCap
      )
      return ex
    }
  }

  static <T> Future<T> submit(Callable<T> task) {
    return executor().submit(task)
  }
}
