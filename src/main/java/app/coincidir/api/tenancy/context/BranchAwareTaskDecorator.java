package app.coincidir.api.tenancy.context;

import app.coincidir.api.tenancy.context.BranchContext.BranchScope;
import org.springframework.core.task.TaskDecorator;

/**
 * BranchAwareTaskDecorator — propaga el {@link BranchContext} desde el
 * thread del request HTTP al thread del pool de @Async.
 *
 * Sin este decorator, un método anotado con @Async corre en un thread del
 * pool de Spring que NO tiene el ThreadLocal del request — y BranchContext.
 * current() devuelve null adentro del método async. Ese fue exactamente el
 * bug que ya peleamos antes con SecurityContextHolder en el audit logging.
 *
 * Forma de activarlo (en una @Configuration con @EnableAsync):
 *
 *   @Bean
 *   public TaskExecutor taskExecutor() {
 *       ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
 *       exec.setTaskDecorator(new BranchAwareTaskDecorator());
 *       exec.setCorePoolSize(...);
 *       exec.initialize();
 *       return exec;
 *   }
 *
 * IMPORTANTE: si tu app ya tiene un TaskDecorator (por ejemplo, uno que
 * propaga SecurityContextHolder), hay que combinarlos. Si no, este
 * decorator pisa al anterior y rompés la auth en código async.
 */
public class BranchAwareTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // Capturamos el scope en el thread "padre" en el momento de envolver.
        BranchScope captured = BranchContext.current();
        return () -> {
            BranchScope previous = BranchContext.current();
            try {
                BranchContext.set(captured);
                runnable.run();
            } finally {
                BranchContext.set(previous);
            }
        };
    }
}
