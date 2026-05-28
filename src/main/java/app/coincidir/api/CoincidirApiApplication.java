package app.coincidir.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableScheduling
@SpringBootApplication
public class CoincidirApiApplication {
  public static void main(String[] args) {
    // Forzar modo headless ANTES de arrancar Spring. En Railway la JVM corre
    // sin entorno gráfico ni libfreetype, así que cualquier inicialización de
    // AWT/fuentes (sun.awt.X11FontManager) lanza UnsatisfiedLinkError. Apache
    // POI puede gatillar esa inicialización al generar Excel. Seteado headless
    // desde la primera línea, POI usa su fallback sin fuentes y no toca
    // libfreetype. Es idempotente y no afecta nada más de la app..
    System.setProperty("java.awt.headless", "true");
    SpringApplication.run(CoincidirApiApplication.class, args);
  }
}
