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

import java.time.Instant;
import java.util.List;

/**
 * DiosSeeder — al arrancar la app, garantiza que existe:
 *  1) Un AppRole "DIOS" con permisos {@code fullAccess=true}, marcado como system.
 *  2) Un PanelUser DIOS hardcodeado por env vars (no se puede borrar).
 *
 * Variables de entorno (con defaults para dev):
 *  - {@code coincidir.dios.username}  → default "dios"
 *  - {@code coincidir.dios.password}  → default "dios1234"  (sólo para arranque inicial)
 *  - {@code coincidir.dios.display-name} → default "Dios"
 *
 * Si el usuario DIOS ya existe en la BD, sólo se sincroniza la password
 * cuando la env var indica una password explícita distinta a la default.
 * Si la env var trae el default y el usuario ya tiene password en BD, no
 * la pisamos (porque podría haber sido cambiada legítimamente desde la UI...
 * aunque hoy bloqueamos el cambio de password de DIOS — ver UserController).
 *
 * El password de DIOS sólo se cambia regenerando con la env var. Nunca desde la UI.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiosSeeder {

    public static final String DIOS_ROLE_CODE = "DIOS";
    private static final String DEFAULT_PASSWORD = "dios1234";

    private final AppRoleRepository roleRepo;
    private final PanelUserRepository userRepo;
    private final PermissionsService permissionsService;

    @Value("${coincidir.dios.username:dios}")
    private String diosUsername;

    @Value("${coincidir.dios.password:" + DEFAULT_PASSWORD + "}")
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

        if (user == null) {
            user = new PanelUser();
            user.setUsername(username);
            user.setDisplayName(diosDisplayName);
            user.setPasswordHash(BCrypt.hashpw(safePassword(), BCrypt.gensalt()));
            user.setRole(DIOS_ROLE_CODE);
            user.setRoleId(diosRole.getId());
            user.setActive(Boolean.TRUE);
            user.setIsSystem(Boolean.TRUE);
            user.setCreatedBy("system");
            userRepo.save(user);
            log.info("[DIOS-SEED] Creado usuario DIOS '{}'", username);
            return;
        }

        // El usuario ya existía: nos limitamos a garantizar invariantes:
        //   - sigue siendo system
        //   - sigue activo
        //   - rol = DIOS
        //   - si la env var trae password NO default, la sincronizamos.
        boolean userDirty = false;
        if (!Boolean.TRUE.equals(user.getIsSystem())) { user.setIsSystem(Boolean.TRUE); userDirty = true; }
        if (!Boolean.TRUE.equals(user.getActive()))   { user.setActive(Boolean.TRUE);   userDirty = true; }
        if (!DIOS_ROLE_CODE.equalsIgnoreCase(user.getRole())) { user.setRole(DIOS_ROLE_CODE); userDirty = true; }
        if (user.getRoleId() == null || !user.getRoleId().equals(diosRole.getId())) {
            user.setRoleId(diosRole.getId()); userDirty = true;
        }

        if (diosPassword != null && !diosPassword.isBlank() && !DEFAULT_PASSWORD.equals(diosPassword)) {
            // Sólo sincronizamos password si no matchea con el hash actual.
            // (Evita reescribir el hash en cada boot, que cambiaría el salt.)
            try {
                if (!BCrypt.checkpw(diosPassword, user.getPasswordHash())) {
                    user.setPasswordHash(BCrypt.hashpw(diosPassword, BCrypt.gensalt()));
                    userDirty = true;
                    log.info("[DIOS-SEED] Password DIOS actualizada desde env vars");
                }
            } catch (Exception ignored) {
                // Hash corrupto: re-generamos
                user.setPasswordHash(BCrypt.hashpw(diosPassword, BCrypt.gensalt()));
                userDirty = true;
            }
        }

        if (userDirty) {
            userRepo.save(user);
            log.info("[DIOS-SEED] Usuario DIOS '{}' actualizado", username);
        } else {
            log.debug("[DIOS-SEED] Usuario DIOS '{}' ya existe y está OK", username);
        }
    }

    private String safePassword() {
        if (diosPassword == null || diosPassword.isBlank()) return DEFAULT_PASSWORD;
        return diosPassword;
    }
}
