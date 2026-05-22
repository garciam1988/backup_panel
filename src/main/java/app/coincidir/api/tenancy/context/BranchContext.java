package app.coincidir.api.tenancy.context;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * BranchContext — contexto de tenancy del request actual.
 *
 * Guarda en un ThreadLocal el {@link BranchScope} (brandId + branchId) que
 * corresponde al request HTTP en curso. Lo poblan los filtros
 * (BranchResolverFilter) al inicio del request y lo consumen los services
 * y repositorios cuando necesitan filtrar por sucursal.
 *
 * ── ATENCIÓN: propagación a threads async ─────────────────────────────────
 *
 * El ThreadLocal NO se propaga automáticamente a:
 *   - Métodos anotados con @Async
 *   - Tareas en ExecutorService
 *   - Jobs de @Scheduled
 *   - CompletableFuture.runAsync sin executor configurado
 *
 * Esto ya nos mordió antes con SecurityContextHolder en el audit logging
 * (ver AuditEventListener — bug del thread async sin SecurityContext).
 * Acá lo prevenimos así:
 *
 *   1. Para @Async: configurar el TaskDecorator (BranchAwareTaskDecorator)
 *      en AsyncConfig para que copie el contexto al thread del pool.
 *
 *   2. Para jobs @Scheduled: no hay request en curso, así que el job DEBE
 *      armar su propio contexto iterando branches con BranchContext.runAs(...).
 *      Ver EmailReminderJob en bloques posteriores.
 *
 *   3. Para código manual con executors: usar BranchContext.wrap(runnable)
 *      que captura el contexto del thread actual y lo restaura en el
 *      thread donde se ejecute.
 *
 * Si en runtime ves "BranchContext requerido pero no encontrado" en los logs,
 * es porque algún flow async no propagó el contexto. La solución es siempre
 * arriba (en el lugar que lanza el thread), no en el lugar que lo consume.
 */
@Slf4j
public final class BranchContext {

    private static final ThreadLocal<BranchScope> CURRENT = new ThreadLocal<>();

    /**
     * Flag de modo "cross-branch": cuando es true, significa que el request
     * actual es DIOS pidiendo explícitamente ver TODAS las sucursales
     * (vía header X-Branch-All=true). En ese caso CURRENT es null pero la
     * ausencia es DELIBERADA, no por bug.
     *
     * Los services lo usan para distinguir:
     *   - scope=null + crossBranch=true   → modo all-branches deliberado
     *     (lectura: sin filtro; escritura: rechazar con 400).
     *   - scope=null + crossBranch=false  → falta de contexto por bug
     *     (típicamente un job async sin propagación; loguear warning).
     */
    private static final ThreadLocal<Boolean> CROSS_BRANCH = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private BranchContext() { /* utility class */ }

    // ─── API principal ────────────────────────────────────────────────────

    /** Setea el contexto para el thread actual. Llamado por el filtro HTTP. */
    public static void set(BranchScope scope) {
        if (scope == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(scope);
        }
    }

    /** Limpia el contexto del thread actual. Llamado al fin de cada request. */
    public static void clear() {
        CURRENT.remove();
        CROSS_BRANCH.remove();
    }

    /**
     * Devuelve el contexto actual. Si no hay contexto (por ej. en un job que
     * no lo seteó), devuelve null. Los servicios deben validar y fallar
     * explícito en vez de asumir.
     */
    public static BranchScope current() {
        return CURRENT.get();
    }

    /** True si el request actual es modo cross-branch deliberado (DIOS + X-Branch-All). */
    public static boolean isCrossBranch() {
        return Boolean.TRUE.equals(CROSS_BRANCH.get());
    }

    /** Setea el flag cross-branch. Solo lo invoca BranchResolverFilter. */
    public static void setCrossBranch(boolean enabled) {
        if (enabled) CROSS_BRANCH.set(Boolean.TRUE);
        else CROSS_BRANCH.remove();
    }

    /**
     * Igual que current() pero falla con excepción si no hay contexto. Usar
     * en servicios que NO pueden funcionar sin tenancy (la mayoría).
     */
    public static BranchScope requireCurrent() {
        BranchScope s = CURRENT.get();
        if (s == null) {
            throw new IllegalStateException(
                "BranchContext requerido pero no encontrado en el thread actual. " +
                "Si esto pasa en un job async, asegurate de propagar el contexto con " +
                "BranchContext.runAs(...) o configurando el TaskDecorator.");
        }
        return s;
    }

    // ─── Helpers para propagación segura ──────────────────────────────────

    /**
     * Ejecuta el runnable con un contexto específico, restaurando el
     * anterior al terminar. Útil para jobs que iteran por sucursales.
     *
     *   for (Branch b : branchRepo.findAll()) {
     *       BranchContext.runAs(BranchScope.of(b), () -> {
     *           // este código corre con b como branch activa
     *       });
     *   }
     */
    public static void runAs(BranchScope scope, Runnable action) {
        BranchScope previous = CURRENT.get();
        try {
            CURRENT.set(scope);
            action.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * Envuelve un Runnable para que cuando se ejecute en otro thread,
     * use el contexto que tenía el thread "padre" en el momento de envolver.
     *
     *   Runnable safe = BranchContext.wrap(() -> hacerCosa());
     *   executor.submit(safe);
     */
    public static Runnable wrap(Runnable target) {
        BranchScope captured = CURRENT.get();
        return () -> runAs(captured, target);
    }

    // ─── Scope ────────────────────────────────────────────────────────────

    /**
     * BranchScope — datos mínimos que viajan en el contexto. Inmutable y
     * liviano. NO ponemos acá la entidad Brand/Branch completa porque eso
     * arrastraría una transacción JPA.
     */
    @Getter
    @ToString
    @AllArgsConstructor
    public static final class BranchScope {
        private final Long brandId;
        private final Long branchId;
        private final String brandSlug;
        private final String branchSlug;

        public static BranchScope of(Long brandId, Long branchId, String brandSlug, String branchSlug) {
            return new BranchScope(brandId, branchId, brandSlug, branchSlug);
        }
    }
}
