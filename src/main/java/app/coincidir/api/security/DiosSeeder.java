package app.coincidir.api.security;

import app.coincidir.api.domain.AppRole;
import app.coincidir.api.domain.PanelUser;
import app.coincidir.api.repository.AppRoleRepository;
import app.coincidir.api.repository.PanelUserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * DiosSeeder — al arrancar la app, garantiza que existe:
 *  1) Un AppRole "DIOS" con permisos {@code fullAccess=true}, marcado como system.
 *  2) Un PanelUser DIOS configurado por env vars (no se puede borrar).
 *
 * Variables de entorno:
 *  - {@code coincidir.dios.username}     → username (default "dios")
 *  - {@code coincidir.dios.password}     → password en texto plano (sin default)
 *  - {@code coincidir.dios.display-name} → display name (default "Dios")
 *
 * Cómo se maneja la password (BCrypt):
 *  - La password en texto plano sólo existe en la env var. NUNCA se persiste
 *    en BD ni en logs. En BD se guarda únicamente el hash BCrypt.
 *  - Cuando el backend arranca, lee la env var y verifica si el hash en BD
 *    matchea. Si matchea, no hace nada. Si no matchea (o el usuario no existe),
 *    re-genera el hash y lo guarda. Esto permite rotar la password cambiando
 *    sólo la env var en Railway y reiniciando.
 *  - Si la env var está vacía y el usuario YA existe en BD, no se toca el hash
 *    (asume que se configuró antes y se le borraron las env vars por algún motivo).
 *  - Si la env var está vacía y el usuario NO existe, se aborta el seed con un
 *    error ruidoso que indica al admin qué env var configurar.
 *
 * Razón de no tener default password:
 *  Tener una default conocida ("dios1234") es un riesgo de seguridad: si alguien
 *  despliega sin configurar la env var, el sistema queda con un super-admin
 *  cuya password está en el código fuente y por lo tanto en Git público.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiosSeeder {

    public static final String DIOS_ROLE_CODE = "DIOS";

    private final AppRoleRepository roleRepo;
    private final PanelUserRepository userRepo;
    private final PermissionsService permissionsService;

    @Value("${coincidir.dios.username:dios}")
    private String diosUsername;

    /** Password en texto plano. Sin default — viene únicamente de env var. */
    @Value("${coincidir.dios.password:}")
    private String diosPassword;

    @Value("${coincidir.dios.display-name:Dios}")
    private String diosDisplayName;

    @PostConstruct
    @Transactional
    public void seed() {
        // 1) Asegurar el rol DIOS
        AppRole diosRole = roleRepo.findByCodeIgnoreCase(DIOS_ROLE_CODE).orElseGet(() -> {
            AppRole r = new AppRole();
            r.setCode(DIOS_ROLE_CODE);
            r.setName("DIOS");
            r.setDescription("Rol con acceso total. No se puede borrar. Se siembra al boot.");
            r.setIsSystem(Boolean.TRUE);
            r.setPermissionsJson(permissionsService.buildPermissionsJson(
                    true, true, true, List.of(), List.of()
            ));
            log.info("[DIOS-SEED] Creando rol DIOS");
            return roleRepo.save(r);
        });

        // Aunque ya exista, garantizamos que sigue siendo system y full-access
        boolean dirty = false;
        if (!Boolean.TRUE.equals(diosRole.getIsSystem())) {
            diosRole.setIsSystem(Boolean.TRUE);
            dirty = true;
        }
        // Re-escribimos permissionsJson para garantizar fullAccess true (defensivo: no
        // queremos que un bug en la UI deje al rol DIOS con fullAccess=false)
        String fixedJson = permissionsService.buildPermissionsJson(true, true, true, List.of(), List.of());
        if (!fixedJson.equals(diosRole.getPermissionsJson())) {
            diosRole.setPermissionsJson(fixedJson);
            dirty = true;
        }
        if (dirty) roleRepo.save(diosRole);

        // 2) Asegurar el usuario DIOS
        String username = (diosUsername == null || diosUsername.isBlank()) ? "dios" : diosUsername.trim();
        PanelUser user = userRepo.findByUsername(username).orElse(null);
        boolean hasPasswordEnv = (diosPassword != null && !diosPassword.isBlank());

        if (user == null) {
            // Usuario nuevo: REQUIERE password vía env var. Si falta, abortamos
            // con un mensaje muy explícito en los logs para que el admin sepa
            // qué configurar. No queremos crear un super-admin con password
            // default conocida.
            if (!hasPasswordEnv) {
                log.error("");
                log.error("════════════════════════════════════════════════════════════════════");
                log.error("[DIOS-SEED] ⛔ No se puede crear el usuario DIOS '{}'", username);
                log.error("");
                log.error("    No hay password configurada. Definí la variable de entorno:");
                log.error("");
                log.error("        COINCIDIR_DIOS_PASSWORD=<tu-password-segura>");
                log.error("");
                log.error("    En Railway: panel del servicio > Variables > New Variable.");
                log.error("    En local:   tu .env.local o run config del IDE.");
                log.error("");
                log.error("    Una vez configurada, reiniciá el backend.");
                log.error("    El sistema arrancó pero NO se podrá hacer login como admin.");
                log.error("════════════════════════════════════════════════════════════════════");
                log.error("");
                return;
            }

            user = new PanelUser();
            user.setUsername(username);
            user.setDisplayName(diosDisplayName);
            user.setPasswordHash(BCrypt.hashpw(diosPassword, BCrypt.gensalt()));
            user.setRole(DIOS_ROLE_CODE);
            user.setRoleId(diosRole.getId());
            user.setActive(Boolean.TRUE);
            user.setIsSystem(Boolean.TRUE);
            user.setCreatedBy("system");
            userRepo.save(user);
            log.info("[DIOS-SEED] ✅ Usuario DIOS '{}' creado con password de env var.", username);
            return;
        }

        // El usuario ya existía: garantizamos invariantes:
        //   - sigue siendo system
        //   - sigue activo
        //   - rol = DIOS
        //   - si la env var trae password, sincronizamos el hash si difiere.
        boolean userDirty = false;
        if (!Boolean.TRUE.equals(user.getIsSystem())) { user.setIsSystem(Boolean.TRUE); userDirty = true; }
        if (!Boolean.TRUE.equals(user.getActive()))   { user.setActive(Boolean.TRUE);   userDirty = true; }
        if (!DIOS_ROLE_CODE.equalsIgnoreCase(user.getRole())) { user.setRole(DIOS_ROLE_CODE); userDirty = true; }
        if (user.getRoleId() == null || !user.getRoleId().equals(diosRole.getId())) {
            user.setRoleId(diosRole.getId()); userDirty = true;
        }

        if (hasPasswordEnv) {
            // Sólo sincronizamos password si no matchea con el hash actual.
            // (Evita reescribir el hash en cada boot, que cambiaría el salt
            // sin cambiar la password efectiva.)
            try {
                if (!BCrypt.checkpw(diosPassword, user.getPasswordHash())) {
                    user.setPasswordHash(BCrypt.hashpw(diosPassword, BCrypt.gensalt()));
                    userDirty = true;
                    log.info("[DIOS-SEED] 🔄 Password DIOS actualizada desde env var.");
                }
            } catch (Exception ignored) {
                // Hash corrupto en BD: re-generamos
                user.setPasswordHash(BCrypt.hashpw(diosPassword, BCrypt.gensalt()));
                userDirty = true;
                log.warn("[DIOS-SEED] Hash corrupto detectado, regenerando.");
            }
        } else {
            // El usuario existe pero la env var no está. Probablemente alguien
            // borró la variable post-deploy. NO tocamos el hash existente, pero
            // logueamos un warning para que el admin sepa que está con una pwd
            // que no se puede rotar sin la env var.
            log.warn("[DIOS-SEED] ⚠️  Usuario DIOS '{}' existe pero COINCIDIR_DIOS_PASSWORD " +
                    "no está configurada. La password actual sigue funcionando, pero no se " +
                    "puede rotar hasta que definas la env var.", username);
        }

        if (userDirty) {
            userRepo.save(user);
            log.info("[DIOS-SEED] Usuario DIOS '{}' actualizado.", username);
        } else {
            log.debug("[DIOS-SEED] Usuario DIOS '{}' ya existe y está OK.", username);
        }
    }
}
