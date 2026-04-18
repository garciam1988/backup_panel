// app/coincidir/api/service/MatchingService.java
package app.coincidir.api.service;

import app.coincidir.api.domain.*;
import app.coincidir.api.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingService {

    private final TravelRequestRepository requestRepository;
    private final TravelGroupRepository groupRepository;
    private final MemberPaymentsBootstrapService paymentsBootstrapService;

    @PersistenceContext
    private EntityManager em;

    /**
     * Podés dejarlo @Scheduled o llamarlo manualmente desde un endpoint.
     */
    @Transactional
    public int runOnce() {
        log.info("==> Iniciando matching de grupos");

        // 1) Traer todas las solicitudes NUEVAS sin grupo
        List<TravelRequest> pending = em.createQuery("""
            SELECT r
              FROM TravelRequest r
             WHERE r.status = :status
               AND r.group IS NULL
            """, TravelRequest.class)
                .setParameter("status", RequestStatus.NEW)
                .getResultList();

        if (pending.isEmpty()) {
            log.info("No hay solicitudes pendientes para agrupar.");
            return 0;
        }

        // 2) Agrupar por bucket de matching
        Map<String, List<TravelRequest>> buckets = pending.stream()
                .filter(r -> r.getGender() != null && !r.getGender().isBlank())
                .collect(Collectors.groupingBy(this::bucketKey, LinkedHashMap::new, Collectors.toList()));

        int groupsCreated = 0;

        for (Map.Entry<String, List<TravelRequest>> entry : buckets.entrySet()) {
            String key = entry.getKey();
            List<TravelRequest> candidates = entry.getValue();

            log.info("Procesando bucket {} con {} candidatos", key, candidates.size());

            // orden cronológico dentro del bucket
            candidates.sort(Comparator.comparing(TravelRequest::getCreatedAt));

            if (candidates.isEmpty()) continue;

            String compPref = Optional.ofNullable(candidates.get(0).getCompanionPreference())
                    .orElse("ANY");

            if ("SAME_GENDER".equalsIgnoreCase(compPref)) {
                groupsCreated += matchSameGenderBucket(candidates);
            } else {
                groupsCreated += matchMixedBucket(candidates);
            }
        }

        log.info("Matching finalizado. Grupos creados: {}", groupsCreated);
        return groupsCreated;
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void scheduledRun() {
        runOnce();
    }

    /**
     * Buckets con preferencia MIXTA (ANY).
     *
     * Reglas generales:
     *  - Primero intenta EXTENDER grupos en auto-search:
     *      * g.status = OPEN
     *      * g.autoSearchEnabled = true
     *      * puede agregar hasta 2 integrantes extra sobre la base de 4
     *      * si en un ciclo agrega 2 personas => pasa a IN_NEGOTIATION y apaga autoSearchEnabled
     *
     *  - Luego intenta EXTENDER grupos "normales" (status = FORMED) con la lógica vieja.
     *  - Finalmente, con lo que sobra, crea grupos NUEVOS (base 2y2) con createNewMixedGroups.
     *
     * IMPORTANTE: ya NO se filtra por smokeFree para agrupar.
     */
    private int matchMixedBucket(List<TravelRequest> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }

        // Usamos el primer request como muestra del bucket
        TravelRequest sample = candidates.get(0);

        String dest = Optional.ofNullable(sample.getDestination()).orElse("-");
        String when = Optional.ofNullable(sample.getWhenLabel()).orElse("-");
        String pref = Optional.ofNullable(sample.getCompanionPreference()).orElse("ANY");

        String ageB;
        if (sample.getAgeMin() != null && sample.getAgeMax() != null) {
            ageB = sample.getAgeMin() + "-" + sample.getAgeMax();
        } else {
            ageB = ageBucket(sample.getAgeMin(), sample.getAgeMax());
        }

        // Particionamos candidatos NEW por género
        List<TravelRequest> males = candidates.stream()
                .filter(r -> "HOMBRE".equalsIgnoreCase(r.getGender()))
                .collect(Collectors.toCollection(ArrayList::new));

        List<TravelRequest> females = candidates.stream()
                .filter(r -> "MUJER".equalsIgnoreCase(r.getGender()))
                .collect(Collectors.toCollection(ArrayList::new));

        int created = 0;

        // 1) Grupos en AUTO-SEARCH: status = OPEN + autoSearchEnabled = true
        List<TravelGroup> autoSearchGroups = em.createQuery("""
        SELECT g
          FROM TravelGroup g
         WHERE g.destination = :dest
           AND g.whenLabel = :when
           AND g.companionPreference = :pref
           AND g.ageBucket = :ageBucket
           AND g.status = :statusOpen
           AND g.autoSearchEnabled = true
        """, TravelGroup.class)
                .setParameter("dest", dest)
                .setParameter("when", when)
                .setParameter("pref", pref)
                .setParameter("ageBucket", ageB)
                .setParameter("statusOpen", GroupStatus.OPEN)
                .getResultList();

        for (TravelGroup g : autoSearchGroups) {
            int added = extendMixedGroupAutoSearch(g, males, females);
            if (added > 0) {
                log.info("Auto-search: grupo {} extendido con {} integrante(s).", g.getId(), added);
            }
        }

        // 2) Grupos ya FORMADOS (lógica vieja), sin auto-search
        List<TravelGroup> formedGroups = em.createQuery("""
        SELECT g
          FROM TravelGroup g
         WHERE g.destination = :dest
           AND g.whenLabel = :when
           AND g.companionPreference = :pref
           AND g.ageBucket = :ageBucket
           AND g.status = :statusFormed
        """, TravelGroup.class)
                .setParameter("dest", dest)
                .setParameter("when", when)
                .setParameter("pref", pref)
                .setParameter("ageBucket", ageB)
                .setParameter("statusFormed", GroupStatus.FORMED)
                .getResultList();

        for (TravelGroup group : formedGroups) {
            extendMixedGroupAutoSearch(group, males, females);
        }

        // 3) Con lo que sobra, crear grupos NUEVOS
        if (!males.isEmpty() || !females.isEmpty()) {
            created += createNewMixedGroups(males, females);
        }

        return created;
    }



    /**
     * Extiende grupos MIXTOS en modo auto-search:
     * - Grupo debe ser mixto (>=1 hombre y >=1 mujer) y tamaño >= 4.
     * - Usa el contador group.autoSearchAdded como "cuántos agregó auto-search" en esta activación.
     * - Puede agregar máx. 2 integrantes por activación y nunca pasar de 9 miembros.
     * - Respeta el balance de género (intenta mantener |H - M| lo más bajo posible).
     * - Si después de esta corrida autoSearchAdded >= 2 -> pasa a IN_NEGOTIATION y apaga autoSearchEnabled.
     * - Si no, deja el grupo en OPEN + autoSearchEnabled=true.
     *
     * Devuelve cuántas personas agregó efectivamente en este run.
     */
    private int extendMixedGroupAutoSearch(
            TravelGroup group,
            List<TravelRequest> maleCandidates,
            List<TravelRequest> femaleCandidates
    ) {
        // Miembros actuales
        List<TravelRequest> current = requestRepository.findByGroup(group);
        int size = current.size();

        // Grupo inválido para auto-search
        if (size < 4 || size >= 9) {
            return 0;
        }

        long currentM = current.stream()
                .filter(r -> "HOMBRE".equalsIgnoreCase(r.getGender()))
                .count();
        long currentF = current.stream()
                .filter(r -> "MUJER".equalsIgnoreCase(r.getGender()))
                .count();

        // Tiene que ser mixto
        if (currentM == 0 || currentF == 0) {
            return 0;
        }

        // Cuántos agregó ya este grupo por auto-search en esta activación
        int alreadyAdded = Optional.ofNullable(group.getAutoSearchAdded()).orElse(0);

        // Cupo restante de esta activación (máx. 2) y tope global 9
        int remainingByActivation = 2 - alreadyAdded;
        if (remainingByActivation <= 0) {
            // Ya completó los 2 de esta activación, debería quedar en negociación
            group.setStatus(GroupStatus.NEGOTIATION);
            group.setAutoSearchEnabled(false);
            groupRepository.save(group);
            return 0;
        }

        int remainingBySize = 9 - size;
        int remainingSlots = Math.min(remainingByActivation, remainingBySize);
        if (remainingSlots <= 0) {
            group.setStatus(GroupStatus.NEGOTIATION);
            group.setAutoSearchEnabled(false);
            groupRepository.save(group);
            return 0;
        }

        int addedThisRun = 0;

        while (remainingSlots > 0 && (!maleCandidates.isEmpty() || !femaleCandidates.isEmpty())) {
            String nextGender = null;
            long diff = currentM - currentF;

            if (diff == 0) {
                // Parejos -> elijo el género con más candidatos disponibles
                if (!maleCandidates.isEmpty() && !femaleCandidates.isEmpty()) {
                    nextGender = (maleCandidates.size() >= femaleCandidates.size()) ? "HOMBRE" : "MUJER";
                } else if (!maleCandidates.isEmpty()) {
                    nextGender = "HOMBRE";
                } else if (!femaleCandidates.isEmpty()) {
                    nextGender = "MUJER";
                }
            } else if (diff > 0) {
                // Hay más hombres en el grupo
                if (!femaleCandidates.isEmpty()) {
                    nextGender = "MUJER";
                } else if (!maleCandidates.isEmpty()) {
                    // No hay mujeres, si o si hombre
                    nextGender = "HOMBRE";
                }
            } else {
                // Hay más mujeres en el grupo
                if (!maleCandidates.isEmpty()) {
                    nextGender = "HOMBRE";
                } else if (!femaleCandidates.isEmpty()) {
                    nextGender = "MUJER";
                }
            }

            // No hay candidatos de ningún género compatible
            if (nextGender == null) {
                break;
            }

            TravelRequest r;
            if ("HOMBRE".equalsIgnoreCase(nextGender)) {
                if (maleCandidates.isEmpty()) {
                    break; // seguridad extra
                }
                r = maleCandidates.remove(0);
                currentM++;
            } else {
                if (femaleCandidates.isEmpty()) {
                    break; // seguridad extra
                }
                r = femaleCandidates.remove(0);
                currentF++;
            }

            r.setGroup(group);
            r.setStatus(RequestStatus.GROUPED);
            requestRepository.save(r);
            paymentsBootstrapService.bootstrapFromRequestIfNeeded(r);

            size++;
            remainingSlots--;
            addedThisRun++;
            alreadyAdded++;

            // Por seguridad, no pasar nunca de 9
            if (size >= 9) {
                break;
            }
        }

        // Actualizo contador y estado del grupo
        group.setAutoSearchAdded(alreadyAdded);

        if (alreadyAdded >= 2 || size >= 9) {
            group.setStatus(GroupStatus.NEGOTIATION);
            group.setAutoSearchEnabled(false);
        } else {
            // Todavía no llegó a 2 en esta activación -> sigue abierto y buscando
            group.setStatus(GroupStatus.OPEN);
            group.setAutoSearchEnabled(true);
        }

        groupRepository.save(group);

        return addedThisRun;
    }







    /**
     * Crea grupos mixtos NUEVOS a partir de las listas de candidatos (males/females).
     * Nueva lógica:
     *   - sólo arma grupos base de 4: 2 hombres + 2 mujeres
     *   - no extiende hasta 9; la extensión pasa por el modo auto-search.
     */
    private int createNewMixedGroups(List<TravelRequest> males,
                                     List<TravelRequest> females) {

        int created = 0;

        // Mientras haya material para armar al menos 2 y 2 (grupo base de 4)
        while (males.size() >= 2 && females.size() >= 2) {
            List<TravelRequest> groupMembers = new ArrayList<>();

            groupMembers.add(males.remove(0));
            groupMembers.add(males.remove(0));
            groupMembers.add(females.remove(0));
            groupMembers.add(females.remove(0));

            createGroupFromRequests(groupMembers);
            created++;
        }

        return created;
    }

    /**
     * Buckets con companionPreference = SAME_GENDER.
     * Nueva lógica:
     *  - intenta primero completar grupos del bucket que estén OPEN + autoSearchEnabled = true,
     *    agregando hasta 2 personas extra sobre la base de 4.
     *  - luego crea grupos nuevos del mismo género, de 4 personas (no extiende a 9).
     *
     * IMPORTANTE: ya NO se filtra por smokeFree para agrupar.
     */
    private int matchSameGenderBucket(List<TravelRequest> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }

        TravelRequest sample = candidates.get(0);

        String dest = Optional.ofNullable(sample.getDestination()).orElse("-");
        String when = Optional.ofNullable(sample.getWhenLabel()).orElse("-");
        String pref = Optional.ofNullable(sample.getCompanionPreference()).orElse("SAME_GENDER");

        String ageB;
        if (sample.getAgeMin() != null && sample.getAgeMax() != null) {
            ageB = sample.getAgeMin() + "-" + sample.getAgeMax();
        } else {
            ageB = ageBucket(sample.getAgeMin(), sample.getAgeMax());
        }

        // Particionamos por género
        List<TravelRequest> males = candidates.stream()
                .filter(r -> "HOMBRE".equalsIgnoreCase(r.getGender()))
                .collect(Collectors.toCollection(ArrayList::new));

        List<TravelRequest> females = candidates.stream()
                .filter(r -> "MUJER".equalsIgnoreCase(r.getGender()))
                .collect(Collectors.toCollection(ArrayList::new));

        // 1) Grupos en AUTO-SEARCH: status = OPEN + autoSearchEnabled = true
        List<TravelGroup> autoSearchGroups = em.createQuery("""
            SELECT g
              FROM TravelGroup g
             WHERE g.destination = :dest
               AND g.whenLabel = :when
               AND g.companionPreference = :pref
               AND g.ageBucket = :ageBucket
               AND g.status = :statusOpen
               AND g.autoSearchEnabled = true
            """, TravelGroup.class)
                .setParameter("dest", dest)
                .setParameter("when", when)
                .setParameter("pref", pref)
                .setParameter("ageBucket", ageB)
                .setParameter("statusOpen", GroupStatus.OPEN)
                .getResultList();

        // Ordenar por desbalance |H - M| descendente
        autoSearchGroups.sort((g1, g2) -> {
            long im1 = genderImbalance(g1);
            long im2 = genderImbalance(g2);
            return Long.compare(im2, im1);
        });

        for (TravelGroup g : autoSearchGroups) {
            int added = extendMixedGroupAutoSearch(g, males, females);
            if (added > 0) {
                log.info("Auto-search: grupo {} extendido con {} integrante(s).", g.getId(), added);
            }
        }


        // 2) Con lo que sobra, crear grupos nuevos de un solo género (4 pax)
        int created = 0;
        created += createSameGenderGroups(males);
        created += createSameGenderGroups(females);

        return created;
    }

    private long genderImbalance(TravelGroup group) {
        List<TravelRequest> current = requestRepository.findByGroup(group);
        long m = current.stream()
                .filter(r -> "HOMBRE".equalsIgnoreCase(r.getGender()))
                .count();
        long f = current.stream()
                .filter(r -> "MUJER".equalsIgnoreCase(r.getGender()))
                .count();
        return Math.abs(m - f);
    }


    /**
     * Extiende grupos SAME_GENDER existentes en modo auto-search:
     *  - agrega hasta 2 integrantes extra sobre la base de 4
     *  - respeta el género del grupo
     *  - SOLO si al final el grupo tiene 4 + 2 o más integrantes
     *    pasa a IN_NEGOTIATION y apaga autoSearchEnabled.
     *    Si no, sigue OPEN + autoSearchEnabled = true.
     */
    private int extendSameGenderGroupAutoSearch(TravelGroup group,
                                                List<TravelRequest> maleCandidates,
                                                List<TravelRequest> femaleCandidates) {

        List<TravelRequest> current = requestRepository.findByGroup(group);
        int size = current.size();

        if (size >= 9) {
            group.setStatus(GroupStatus.NEGOTIATION);
            group.setAutoSearchEnabled(false);
            return 0;
        }

        long malesInGroup = current.stream()
                .filter(r -> "HOMBRE".equalsIgnoreCase(r.getGender()))
                .count();
        long femalesInGroup = current.stream()
                .filter(r -> "MUJER".equalsIgnoreCase(r.getGender()))
                .count();

        String groupGender;
        if (malesInGroup > 0 && femalesInGroup == 0) {
            groupGender = "HOMBRE";
        } else if (femalesInGroup > 0 && malesInGroup == 0) {
            groupGender = "MUJER";
        } else {
            // si por algún motivo no es mono-género, no lo tocamos
            group.setStatus(GroupStatus.NEGOTIATION);
            group.setAutoSearchEnabled(false);
            return 0;
        }

        int extraAlready = Math.max(0, size - 4);
        int slotsRemaining = 2 - extraAlready;
        if (slotsRemaining <= 0) {
            group.setStatus(GroupStatus.NEGOTIATION);
            group.setAutoSearchEnabled(false);
            return 0;
        }

        List<TravelRequest> sourceList = "HOMBRE".equalsIgnoreCase(groupGender)
                ? maleCandidates
                : femaleCandidates;

        int added = 0;
        while (slotsRemaining > 0 && !sourceList.isEmpty()) {
            TravelRequest r = sourceList.remove(0);
            r.setGroup(group);
            r.setStatus(RequestStatus.GROUPED);
            requestRepository.save(r);
            paymentsBootstrapService.bootstrapFromRequestIfNeeded(r);
            size++;
            slotsRemaining--;
            added++;
        }

        int extraAfter = Math.max(0, size - 4);

        if (extraAfter >= 2) {
            group.setStatus(GroupStatus.NEGOTIATION);
            group.setAutoSearchEnabled(false);
        } else {
            group.setStatus(GroupStatus.OPEN);
            group.setAutoSearchEnabled(true);
        }

        return added;
    }


    /**
     * Crea grupos SAME_GENDER nuevos.
     * Nueva lógica: grupos fijos de 4 personas, no se extienden a 9.
     */
    private int createSameGenderGroups(List<TravelRequest> list) {
        int created = 0;
        while (list.size() >= 4) {
            List<TravelRequest> groupMembers = new ArrayList<>(list.subList(0, 4));
            list.subList(0, 4).clear();
            createGroupFromRequests(groupMembers);
            created++;
        }
        return created;
    }

    /**
     * Crea un TravelGroup a partir de las solicitudes dadas,
     * copia los campos relevantes y marca las requests como GROUPED.
     *
     * Nueva lógica: el grupo nace con status = OPEN.
     */
    private void createGroupFromRequests(List<TravelRequest> members) {
        if (members == null || members.isEmpty()) return;

        TravelRequest seed = members.get(0);

        String dest = seed.getDestination();
        String when = seed.getWhenLabel();
        String compPref = seed.getCompanionPreference();
        boolean smoke = seed.isSmokeFreeSafe();
        Integer ageMin = seed.getAgeMin();
        Integer ageMax = seed.getAgeMax();

        String ageBucket = (ageMin != null && ageMax != null)
                ? ageMin + "-" + ageMax
                : ageBucket(ageMin, ageMax);

        TravelGroup group = TravelGroup.builder()
                .status(GroupStatus.NEGOTIATION)          // antes: FORMED
                .destination(dest)
                .whenLabel(when)
                .travelDateLabel(when)
                .companionPreference(compPref)
                .smokeFree(smoke)
                .sizeTarget(9) // máximo teórico de personas en el grupo
                .ageBucket(ageBucket)
                .createdAt(Instant.now())
                .autoSearchEnabled(false)         // por defecto, no busca más integrantes
                .build();

        groupRepository.save(group);

        // Asignar las requests al grupo
        for (TravelRequest r : members) {
            r.setGroup(group);
            r.setStatus(RequestStatus.GROUPED);
            requestRepository.save(r);
            paymentsBootstrapService.bootstrapFromRequestIfNeeded(r);
        }

        log.info("Creado grupo {} con {} miembros (destino={}, when={}, pref={})",
                group.getId(),
                members.size(),
                dest,
                when,
                compPref
        );
    }

    /**
     * Bucket clave para agrupar candidatos:
     * destino + whenLabel + companionPreference + rango edad + paxMin/paxMax.
     *
     * IMPORTANTE: ya NO usa smokeFree para agrupar.
     */
    private String bucketKey(TravelRequest r) {
        String dest = Optional.ofNullable(r.getDestination()).orElse("-");
        String when = Optional.ofNullable(r.getWhenLabel()).orElse("-");
        String pref = Optional.ofNullable(r.getCompanionPreference()).orElse("ANY");

        String ageB;
        if (r.getAgeMin() != null && r.getAgeMax() != null) {
            ageB = r.getAgeMin() + "-" + r.getAgeMax(); // rango exacto
        } else {
            ageB = ageBucket(r.getAgeMin(), r.getAgeMax());
        }

        int pmin = r.getPaxMin() == null ? 4 : r.getPaxMin();
        int pmax = r.getPaxMax() == null ? 9 : r.getPaxMax();

        // Sin smokeFree en la clave
        return String.join("|", dest, when, pref, ageB, pmin + "-" + pmax);
    }


    private String ageBucket(Integer min, Integer max) {
        int a = min == null ? 18 : min;
        int b = max == null ? a : max;
        // Bucket de 5 años (fallback cuando no hay rango explícito)
        int start = (a / 5) * 5;
        int end = (b / 5) * 5 + (b % 5 == 0 ? 0 : 4);
        return start + "-" + end;
    }
}
