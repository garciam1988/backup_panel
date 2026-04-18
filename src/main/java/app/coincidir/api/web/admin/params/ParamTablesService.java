package app.coincidir.api.web.admin.params;

import app.coincidir.api.web.admin.params.model.ParamFieldDef;
import app.coincidir.api.web.admin.params.model.ParamTableDef;

import app.coincidir.api.domain.UserAccount;
import app.coincidir.api.repository.UserAccountRepository;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class ParamTablesService {

    private final JdbcTemplate jdbc;


    private final UserAccountRepository userAccountRepo;
    private final PasswordEncoder passwordEncoder;

    private final AtomicReference<SchemaCache> cacheRef = new AtomicReference<>();
    private static final long CACHE_TTL_MS = 60_000;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");


    private static final String USER_ACCOUNT_TABLE = "user_account";

    private static boolean isUserAccount(String tableKey) {
        return tableKey != null && USER_ACCOUNT_TABLE.equalsIgnoreCase(tableKey);
    }

    private ParamTableDef userAccountDef() {
        return new ParamTableDef(
                USER_ACCOUNT_TABLE,
                "User Account",
                "Seguridad",
                List.of("id"),
                List.of(
                        new ParamFieldDef("email", "Email", "text", true, true, null),
                        new ParamFieldDef("password", "Password", "password", true, true, null),
                        new ParamFieldDef("role", "Rol", "text", true, true, null),
                        new ParamFieldDef("firstName", "Nombre", "text", false, true, null),
                        new ParamFieldDef("lastName", "Apellido", "text", false, true, null)
                )
        );
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String strOrNull(Object v) {
        String s = str(v);
        return s.isBlank() ? null : s;
    }

    private Map<String, Object> toUserAccountRow(UserAccount ua) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ua.getId());
        m.put("email", ua.getEmail());
        m.put("role", ua.getRole());
        m.put("firstName", ua.getFirstName());
        m.put("lastName", ua.getLastName());
        m.put("canHardDelete", true);
        return m;
    }

    private List<Map<String, Object>> listUserAccounts(String q) {
        String term = q == null ? "" : q.trim().toLowerCase();
        List<UserAccount> all = userAccountRepo.findAll(Sort.by(Sort.Direction.ASC, "email"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserAccount ua : all) {
            if (!term.isBlank()) {
                String blob = (str(ua.getEmail()) + " " + str(ua.getRole()) + " " + str(ua.getFirstName()) + " " + str(ua.getLastName())).toLowerCase();
                if (!blob.contains(term)) continue;
            }
            out.add(toUserAccountRow(ua));
        }
        return out;
    }

    private Map<String, Object> createUserAccount(Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;

        String email = str(b.get("email"));
        String role = str(b.get("role"));
        String password = str(b.get("password"));

        if (email.isBlank()) throw new IllegalArgumentException("Email es obligatorio");
        if (role.isBlank()) throw new IllegalArgumentException("Rol es obligatorio");
        if (password.isBlank()) throw new IllegalArgumentException("Password es obligatorio");

        userAccountRepo.findByEmailIgnoreCase(email).ifPresent(x -> {
            throw new IllegalArgumentException("Ya existe un usuario con ese email");
        });

        UserAccount ua = UserAccount.builder()
                .email(email)
                .role(role)
                .password(passwordEncoder.encode(password))
                .firstName(strOrNull(b.get("firstName")))
                .lastName(strOrNull(b.get("lastName")))
                .build();

        UserAccount saved = userAccountRepo.save(ua);
        return toUserAccountRow(saved);
    }

    private Map<String, Object> updateUserAccount(String pkEncoded, Map<String, Object> body) {
        long id;
        try {
            id = Long.parseLong(str(pkEncoded));
        } catch (Exception e) {
            throw new IllegalArgumentException("ID inválido");
        }

        UserAccount ua = userAccountRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));
        Map<String, Object> b = body == null ? Map.of() : body;

        String newEmail = str(b.get("email"));
        if (!newEmail.isBlank() && !newEmail.equalsIgnoreCase(str(ua.getEmail()))) {
            userAccountRepo.findByEmailIgnoreCase(newEmail).ifPresent(x -> {
                throw new IllegalArgumentException("Ya existe un usuario con ese email");
            });
            ua.setEmail(newEmail);
        }

        String newRole = str(b.get("role"));
        if (!newRole.isBlank()) ua.setRole(newRole);

        if (b.containsKey("firstName")) ua.setFirstName(strOrNull(b.get("firstName")));
        if (b.containsKey("lastName")) ua.setLastName(strOrNull(b.get("lastName")));

        String newPassword = str(b.get("password"));
        if (!newPassword.isBlank()) {
            ua.setPassword(passwordEncoder.encode(newPassword));
        }

        UserAccount saved = userAccountRepo.save(ua);
        return toUserAccountRow(saved);
    }

    private void hardDeleteUserAccount(String pkEncoded) {
        long id;
        try {
            id = Long.parseLong(str(pkEncoded));
        } catch (Exception e) {
            throw new IllegalArgumentException("ID inválido");
        }
        try {
            if (!userAccountRepo.existsById(id)) throw new IllegalArgumentException("Usuario no encontrado");
            userAccountRepo.deleteById(id);
        } catch (DataIntegrityViolationException dive) {
            throw new IllegalStateException("No se puede eliminar: tiene registros relacionados (FK).");
        }
    }


    public List<ParamTableDef> listTableDefs() {
        Schema schema = loadSchema();
        List<ParamTableDef> out = new ArrayList<>();
        for (TableInfo t : schema.tablesByKey.values()) {
            out.add(t.toDef());
        }
        out.add(userAccountDef());
        out.sort(Comparator.comparing(ParamTableDef::category).thenComparing(ParamTableDef::label));
        return out;
    }

    public List<Map<String, Object>> listRows(String tableKey, String q, boolean includeInactive) {
        if (isUserAccount(tableKey)) {
            return listUserAccounts(q);
        }
        Schema schema = loadSchema();
        TableInfo t = requireTable(schema, tableKey);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(t.selectClause()).append(" FROM ").append(backtick(t.tableName)).append(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();

        if (!includeInactive && t.softDeleteMode != SoftDeleteMode.NONE) {
            sql.append(" AND ").append(t.activeWhereClause(true));
        }

        if (q != null && !q.isBlank()) {
            String term = "%" + q.trim().toLowerCase() + "%";
            sql.append(" AND (");

            // PK (simple o compuesta)
            for (int i = 0; i < t.pkColumns.size(); i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("CAST(").append(backtick(t.pkColumns.get(i))).append(" AS CHAR) LIKE ?");
                args.add(term);
            }

            if (t.descriptorColumn != null) {
                sql.append(" OR LOWER(").append(backtick(t.descriptorColumn)).append(") LIKE ?");
                args.add(term);
            }

            sql.append(")");
        }

        // Orden
        if (t.descriptorColumn != null) {
            sql.append(" ORDER BY ").append(backtick(t.descriptorColumn)).append(" ASC");
        } else {
            for (int i = 0; i < t.pkColumns.size(); i++) {
                if (i == 0) sql.append(" ORDER BY ");
                else sql.append(", ");
                sql.append(backtick(t.pkColumns.get(i))).append(" ASC");
            }
        }

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> out = new ArrayList<>();

        for (Map<String, Object> r : rows) {
            Map<String, Object> mapped = t.mapRowToLogical(r);
            Pk pk = t.pkFromLogicalRow(mapped);
            mapped.put("canHardDelete", pk != null && canHardDelete(schema, t, pk));
            out.add(mapped);
        }

        return out;
    }

    /**
     * Devuelve opciones de combo para columnas FK de una tabla (principalmente tablas de relación).
     *
     * Formato:
     * {
     *   "columns": {
     *     "id_alojamiento": {
     *        "refTable": "alojamientos",
     *        "refColumn": "id",
     *        "items": [{"id":1,"label":"Hotel X","activo":true}, ...]
     *     }
     *   }
     * }
     */
    public Map<String, Object> fkOptions(String tableKey) {
        if (isUserAccount(tableKey)) {
            return Map.of("columns", Map.of());
        }
        Schema schema = loadSchema();
        TableInfo t = requireTable(schema, tableKey);

        Map<String, FkRef> outgoing = loadOutgoingFks(t.tableName);

        // Fallback: si la tabla no tiene FK declaradas (constraints), inferimos por nombre de columna.
        // Ej: prestador_id -> prestadores.id, excursion_id -> excursiones.id
        if (outgoing.isEmpty() && t.relation && t.pkColumns != null && !t.pkColumns.isEmpty()) {
            Set<String> tableNames = loadAllTableNames();
            for (String fkCol : t.pkColumns) {
                String inferredTable = inferTableFromFkColumn(fkCol, tableNames);
                if (inferredTable == null) continue;
                String inferredPk = inferPrimaryKeyColumn(schema, inferredTable);
                outgoing.put(fkCol, new FkRef(inferredTable, inferredPk));
            }
        }
        Map<String, Object> colsOut = new LinkedHashMap<>();

        for (var e : outgoing.entrySet()) {
            String col = e.getKey();
            FkRef fk = e.getValue();

            String refTable = fk.referencedTable();
            String refCol = fk.referencedColumn();

            String labelCol = findDescriptorColumn(refTable);
            SoftDeleteRef sdr = findSoftDeleteRef(refTable);

            StringBuilder sql = new StringBuilder();
            sql.append("SELECT ").append(backtick(refCol)).append(" AS id");
            if (labelCol != null) {
                sql.append(", ").append(backtick(labelCol)).append(" AS label");
            } else {
                sql.append(", CAST(").append(backtick(refCol)).append(" AS CHAR) AS label");
            }
            sql.append(", ").append(sdr.activeSelectExpr()).append(" AS activo");
            sql.append(" FROM ").append(backtick(refTable));

            // no filtramos por activo para que si existe un registro inactivo actualmente asignado, siga apareciendo.
            if (labelCol != null) {
                sql.append(" ORDER BY ").append(backtick(labelCol)).append(" ASC");
            } else {
                sql.append(" ORDER BY ").append(backtick(refCol)).append(" ASC");
            }

            List<Map<String, Object>> rows = jdbc.queryForList(sql.toString());
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                Object id = r.get("id");
                Object label = r.get("label");
                Object act = r.get("activo");
                boolean active = true;
                if (act instanceof Boolean b) active = b;
                else if (act instanceof Number n) active = n.intValue() != 0;
                else if (act instanceof byte[] bytes && bytes.length == 1) active = bytes[0] != 0;

                String lbl = label == null ? (id == null ? "" : String.valueOf(id)) : String.valueOf(label);
                if (!active) lbl = lbl + " (Inactivo)";
                items.add(new LinkedHashMap<>(Map.of(
                        "id", id,
                        "label", lbl,
                        "activo", active
                )));
            }

            colsOut.put(col, new LinkedHashMap<>(Map.of(
                    "refTable", refTable,
                    "refColumn", refCol,
                    "items", items
            )));
        }

        return new LinkedHashMap<>(Map.of("columns", colsOut));
    }

    public Map<String, Object> create(String tableKey, Map<String, Object> body) {
        if (isUserAccount(tableKey)) {
            return createUserAccount(body);
        }
        Schema schema = loadSchema();
        TableInfo t = requireTable(schema, tableKey);

        Map<String, Object> logical = body == null ? Map.of() : body;
        Map<String, Object> physicalValues = t.mapLogicalToPhysicalForWrite(logical, true);
        t.applyAutoFields(physicalValues, true);

        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        MapSqlParameterSource params = new MapSqlParameterSource();

        int i = 0;
        for (var e : physicalValues.entrySet()) {
            String col = e.getKey();
            if (!t.columns.containsKey(col)) continue;
            if (t.isAutoIncrement(col)) continue;

            Object v = normalizeIncomingValue(t.columns.get(col), e.getValue());
            if (v == null && t.columns.get(col).hasDefault) {
                continue;
            }
            if (i++ > 0) {
                cols.append(", ");
                vals.append(", ");
            }
            cols.append(backtick(col));
            String p = "p_" + col;
            vals.append(":" + p);
            params.addValue(p, v);
        }

        if (i == 0) {
            throw new IllegalArgumentException("No hay campos para insertar");
        }

        String sql = "INSERT INTO " + backtick(t.tableName) + " (" + cols + ") VALUES (" + vals + ")";
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc);

        KeyHolder kh = new GeneratedKeyHolder();
        named.update(sql, params, kh);

        // Si hay PK auto-increment simple
        if (t.pkColumns.size() == 1 && t.isAutoIncrement(t.pkColumns.get(0))) {
            Number key = kh.getKey();
            if (key != null) {
                Pk pk = Pk.single(t.pkColumns.get(0), key.longValue());
                return getByPk(schema, t, pk);
            }
        }

        // PK compuesta o no auto-increment: requiere PK en el body
        Pk pk = t.pkFromPhysicalForCreate(physicalValues);
        if (pk != null) {
            return getByPk(schema, t, pk);
        }

        return Map.of("ok", true);
    }

    public Map<String, Object> update(String tableKey, String pkEncoded, Map<String, Object> body) {
        if (isUserAccount(tableKey)) {
            return updateUserAccount(pkEncoded, body);
        }
        Schema schema = loadSchema();
        TableInfo t = requireTable(schema, tableKey);

        Pk pk = t.parsePk(pkEncoded);

        // En tablas de relación (PK compuesta), permitir "editar" cambiando la PK: reemplaza fila (DELETE + INSERT)
        if (t.relation) {
            Map<String, Object> logical = body == null ? Map.of() : body;
            Map<String, Object> physicalValues = t.mapLogicalToPhysicalForWrite(logical, false);

            // si no viene un PK nuevo, usa el actual
            for (String pkCol : t.pkColumns) {
                if (!physicalValues.containsKey(pkCol)) {
                    physicalValues.put(pkCol, pk.values.get(pkCol));
                }
            }

            // normaliza PK
            Map<String, Object> newPkVals = new LinkedHashMap<>();
            for (String pkCol : t.pkColumns) {
                ColumnInfo ci = t.columns.get(pkCol);
                Object v = normalizeIncomingValue(ci, physicalValues.get(pkCol));
                if (v == null) throw new IllegalArgumentException("Clave inválida");
                newPkVals.put(pkCol, v);
            }
            Pk newPk = new Pk(newPkVals);

            if (pk.values.equals(newPk.values)) {
                return getByPk(schema, t, pk);
            }

            // delete actual
            StringBuilder del = new StringBuilder();
            del.append("DELETE FROM ").append(backtick(t.tableName)).append(" WHERE ");
            List<Object> delArgs = new ArrayList<>();
            for (int i = 0; i < t.pkColumns.size(); i++) {
                if (i > 0) del.append(" AND ");
                String col = t.pkColumns.get(i);
                del.append(backtick(col)).append(" = ?");
                delArgs.add(pk.values.get(col));
            }
            jdbc.update(del.toString(), delArgs.toArray());

            // insert nuevo
            Map<String, Object> toInsert = new LinkedHashMap<>(physicalValues);
            // fuerza valores PK normalizados
            for (String pkCol : t.pkColumns) {
                toInsert.put(pkCol, newPk.values.get(pkCol));
            }

            insertRow(t, toInsert);
            return getByPk(schema, t, newPk);
        }

        Map<String, Object> logical = body == null ? Map.of() : body;
        Map<String, Object> physicalValues = t.mapLogicalToPhysicalForWrite(logical, false);
        t.applyAutoFields(physicalValues, false);

        StringBuilder set = new StringBuilder();
        MapSqlParameterSource params = new MapSqlParameterSource();
        int i = 0;

        for (var e : physicalValues.entrySet()) {
            String col = e.getKey();
            if (!t.columns.containsKey(col)) continue;
            if (t.pkColumns.stream().anyMatch(c -> c.equalsIgnoreCase(col))) continue;
            if (t.isAutoIncrement(col)) continue;
            if (t.isCreatedAt(col)) continue;

            Object v = normalizeIncomingValue(t.columns.get(col), e.getValue());
            if (i++ > 0) set.append(", ");
            String p = "p_" + col;
            set.append(backtick(col)).append(" = :").append(p);
            params.addValue(p, v);
        }

        if (i == 0) {
            return getByPk(schema, t, pk);
        }

        // WHERE PK
        StringBuilder where = new StringBuilder();
        for (int idx = 0; idx < t.pkColumns.size(); idx++) {
            if (idx > 0) where.append(" AND ");
            String col = t.pkColumns.get(idx);
            String p = "pk" + idx;
            where.append(backtick(col)).append(" = :").append(p);
            params.addValue(p, pk.values.get(col));
        }

        String sql = "UPDATE " + backtick(t.tableName) + " SET " + set + " WHERE " + where;
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc);
        int changed = named.update(sql, params);
        if (changed == 0) {
            throw new IllegalArgumentException("No se encontró el registro");
        }
        return getByPk(schema, t, pk);
    }

    private void insertRow(TableInfo t, Map<String, Object> physicalValues) {
        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        MapSqlParameterSource params = new MapSqlParameterSource();
        int i = 0;

        for (var e : physicalValues.entrySet()) {
            String col = e.getKey();
            if (!t.columns.containsKey(col)) continue;
            if (t.isAutoIncrement(col)) continue;
            if (t.shouldSkipColumn(col)) continue;

            Object v = normalizeIncomingValue(t.columns.get(col), e.getValue());
            if (v == null && t.columns.get(col).hasDefault) {
                continue;
            }

            if (i++ > 0) {
                cols.append(", ");
                vals.append(", ");
            }
            cols.append(backtick(col));
            String p = "p_" + col;
            vals.append(":" + p);
            params.addValue(p, v);
        }

        if (i == 0) throw new IllegalArgumentException("No hay campos para insertar");
        String sql = "INSERT INTO " + backtick(t.tableName) + " (" + cols + ") VALUES (" + vals + ")";
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc);
        named.update(sql, params);
    }

    public void softDelete(String tableKey, String pkEncoded) {
        if (isUserAccount(tableKey)) {
            throw new IllegalArgumentException("La tabla user_account no soporta baja lógica.");
        }
        Schema schema = loadSchema();
        TableInfo t = requireTable(schema, tableKey);
        Pk pk = t.parsePk(pkEncoded);

        if (t.softDeleteMode == SoftDeleteMode.NONE) {
            hardDelete(tableKey, pkEncoded);
            return;
        }

        StringBuilder sql = new StringBuilder();
        sql.append("UPDATE ").append(backtick(t.tableName))
                .append(" SET ").append(t.activeUpdateClause(false)).append(t.updatedAtClause())
                .append(" WHERE ");

        List<Object> args = new ArrayList<>();
        for (int i = 0; i < t.pkColumns.size(); i++) {
            if (i > 0) sql.append(" AND ");
            String col = t.pkColumns.get(i);
            sql.append(backtick(col)).append(" = ?");
            args.add(pk.values.get(col));
        }

        int changed = jdbc.update(sql.toString(), args.toArray());
        if (changed == 0) throw new IllegalArgumentException("No se encontró el registro");
    }

    public void restore(String tableKey, String pkEncoded) {
        if (isUserAccount(tableKey)) {
            throw new IllegalArgumentException("La tabla user_account no soporta restaurar.");
        }
        Schema schema = loadSchema();
        TableInfo t = requireTable(schema, tableKey);
        Pk pk = t.parsePk(pkEncoded);

        if (t.softDeleteMode == SoftDeleteMode.NONE) return;

        StringBuilder sql = new StringBuilder();
        sql.append("UPDATE ").append(backtick(t.tableName))
                .append(" SET ").append(t.activeUpdateClause(true)).append(t.updatedAtClause())
                .append(" WHERE ");

        List<Object> args = new ArrayList<>();
        for (int i = 0; i < t.pkColumns.size(); i++) {
            if (i > 0) sql.append(" AND ");
            String col = t.pkColumns.get(i);
            sql.append(backtick(col)).append(" = ?");
            args.add(pk.values.get(col));
        }

        int changed = jdbc.update(sql.toString(), args.toArray());
        if (changed == 0) throw new IllegalArgumentException("No se encontró el registro");
    }

    public void hardDelete(String tableKey, String pkEncoded) {
        if (isUserAccount(tableKey)) {
            hardDeleteUserAccount(pkEncoded);
            return;
        }
        Schema schema = loadSchema();
        TableInfo t = requireTable(schema, tableKey);
        Pk pk = t.parsePk(pkEncoded);

        if (!canHardDelete(schema, t, pk)) {
            throw new IllegalStateException("No se puede eliminar definitivamente: tiene registros relacionados.");
        }

        StringBuilder sql = new StringBuilder();
        sql.append("DELETE FROM ").append(backtick(t.tableName)).append(" WHERE ");
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < t.pkColumns.size(); i++) {
            if (i > 0) sql.append(" AND ");
            String col = t.pkColumns.get(i);
            sql.append(backtick(col)).append(" = ?");
            args.add(pk.values.get(col));
        }

        try {
            int changed = jdbc.update(sql.toString(), args.toArray());
            if (changed == 0) throw new IllegalArgumentException("No se encontró el registro");
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalStateException("No se puede eliminar definitivamente: tiene registros relacionados.");
        }
    }

    // ---------------- schema + hard delete guard ----------------

    private Map<String, Object> getByPk(Schema schema, TableInfo t, Pk pk) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < t.pkColumns.size(); i++) {
            if (i > 0) where.append(" AND ");
            String col = t.pkColumns.get(i);
            where.append(backtick(col)).append(" = ?");
            args.add(pk.values.get(col));
        }

        String sql = "SELECT " + t.selectClause() + " FROM " + backtick(t.tableName) + " WHERE " + where;
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args.toArray());
        if (rows.isEmpty()) throw new IllegalArgumentException("No se encontró el registro");

        Map<String, Object> mapped = t.mapRowToLogical(rows.get(0));
        mapped.put("canHardDelete", canHardDelete(schema, t, pk));
        return mapped;
    }

    private boolean canHardDelete(Schema schema, TableInfo t, Pk pk) {
        List<RefGroup> refs = schema.refsByTable.computeIfAbsent(t.tableName, k -> loadReferences(t.tableName));
        if (refs.isEmpty()) return true;

        for (RefGroup rg : refs) {
            // si no tenemos el valor de algún referenced col, bloquea por seguridad
            boolean missing = false;
            for (RefPair p : rg.pairs) {
                if (!pk.values.containsKey(p.referencedColumn)) {
                    missing = true;
                    break;
                }
            }
            if (missing) return false;

            StringBuilder sql = new StringBuilder();
            sql.append("SELECT COUNT(1) FROM ").append(backtick(rg.tableName)).append(" WHERE ");
            List<Object> args = new ArrayList<>();
            for (int i = 0; i < rg.pairs.size(); i++) {
                if (i > 0) sql.append(" AND ");
                RefPair p = rg.pairs.get(i);
                sql.append(backtick(p.columnName)).append(" = ?");
                args.add(pk.values.get(p.referencedColumn));
            }
            Number n = jdbc.queryForObject(sql.toString(), Number.class, args.toArray());
            if (n != null && n.longValue() > 0) return false;
        }

        return true;
    }

    private List<RefGroup> loadReferences(String tableName) {
        String sql = "SELECT TABLE_NAME, COLUMN_NAME, REFERENCED_COLUMN_NAME, CONSTRAINT_NAME, ORDINAL_POSITION " +
                "FROM information_schema.KEY_COLUMN_USAGE " +
                "WHERE REFERENCED_TABLE_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME = ? " +
                "ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION";

        List<Map<String, Object>> rows = jdbc.queryForList(sql, tableName);
        Map<String, List<RefPair>> grouped = new LinkedHashMap<>();
        Map<String, String> refTableByKey = new HashMap<>();

        for (Map<String, Object> r : rows) {
            String refTable = String.valueOf(r.get("TABLE_NAME"));
            String col = String.valueOf(r.get("COLUMN_NAME"));
            String refCol = String.valueOf(r.get("REFERENCED_COLUMN_NAME"));
            String constraint = String.valueOf(r.get("CONSTRAINT_NAME"));
            if (refTable == null || col == null || refCol == null || constraint == null) continue;
            String k = refTable + "|" + constraint;
            grouped.computeIfAbsent(k, kk -> new ArrayList<>()).add(new RefPair(col, refCol));
            refTableByKey.put(k, refTable);
        }

        List<RefGroup> out = new ArrayList<>();
        for (var e : grouped.entrySet()) {
            out.add(new RefGroup(refTableByKey.get(e.getKey()), e.getValue()));
        }
        return out;
    }

    // ---------------- FK lookups (combos) ----------------

    private record FkRef(String referencedTable, String referencedColumn) {
    }

    private record SoftDeleteRef(SoftDeleteMode mode, String column) {
        String activeSelectExpr() {
            if (mode == SoftDeleteMode.ACTIVE_FLAG && column != null) {
                return backtick(column);
            }
            if (mode == SoftDeleteMode.DELETED_FLAG && column != null) {
                return "(NOT " + backtick(column) + ")";
            }
            return "TRUE";
        }
    }

    private Map<String, FkRef> loadOutgoingFks(String tableName) {
        String sql = "SELECT COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME " +
                "FROM information_schema.KEY_COLUMN_USAGE " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND REFERENCED_TABLE_NAME IS NOT NULL";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, tableName);
        Map<String, FkRef> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String col = String.valueOf(r.get("COLUMN_NAME"));
            String refTable = String.valueOf(r.get("REFERENCED_TABLE_NAME"));
            String refCol = String.valueOf(r.get("REFERENCED_COLUMN_NAME"));
            if (col == null || refTable == null || refCol == null) continue;
            out.put(col, new FkRef(refTable, refCol));
        }
        return out;
    }

    private String findDescriptorColumn(String tableName) {
        // preferimos un descriptor típico
        List<String> preferred = List.of(
                "descripcion", "description", "nombre", "name", "label", "titulo", "title", "code", "codigo"
        );

        Map<String, String> cols = loadColumnNamesLower(tableName);
        for (String p : preferred) {
            if (cols.containsKey(p)) {
                return cols.get(p);
            }
        }
        return null;
    }

    private SoftDeleteRef findSoftDeleteRef(String tableName) {
        Map<String, String> cols = loadColumnNamesLower(tableName);
        // activo
        for (String k : cols.keySet()) {
            if (k.equals("activo") || k.equals("active") || k.equals("enabled") || k.equals("habilitado") || k.equals("is_active")) {
                return new SoftDeleteRef(SoftDeleteMode.ACTIVE_FLAG, cols.get(k));
            }
        }
        // deleted
        for (String k : cols.keySet()) {
            if (k.equals("deleted") || k.equals("is_deleted")) {
                return new SoftDeleteRef(SoftDeleteMode.DELETED_FLAG, cols.get(k));
            }
        }
        return new SoftDeleteRef(SoftDeleteMode.NONE, null);
    }

    private Set<String> loadAllTableNames() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT TABLE_NAME AS t FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()"
        );
        Set<String> out = new HashSet<>();
        for (Map<String, Object> r : rows) {
            Object t = r.get("t");
            if (t != null) out.add(String.valueOf(t).toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private String inferTableFromFkColumn(String fkColumn, Set<String> tableNames) {
        if (fkColumn == null) return null;
        String col = fkColumn.toLowerCase(Locale.ROOT).trim();
        String base = col;
        if (base.startsWith("id_")) base = base.substring(3);
        if (base.endsWith("_id")) base = base.substring(0, base.length() - 3);
        if (base.isBlank()) return null;

        // candidatos
        List<String> candidates = new ArrayList<>();
        candidates.add(base);
        if (!base.endsWith("s")) candidates.add(base + "s");
        candidates.add(base + "es");

        for (String cand : candidates) {
            if (tableNames.contains(cand)) return cand;
        }
        return null;
    }

    private String inferPrimaryKeyColumn(Schema schema, String tableName) {
        if (tableName == null) return "id";
        TableInfo t = schema.tablesByKey.get(tableName);
        if (t != null && t.pkColumns != null && !t.pkColumns.isEmpty()) return t.pkColumns.get(0);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT COLUMN_NAME AS c FROM information_schema.KEY_COLUMN_USAGE " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND CONSTRAINT_NAME = 'PRIMARY' " +
                        "ORDER BY ORDINAL_POSITION ASC LIMIT 1",
                tableName
        );
        if (!rows.isEmpty() && rows.get(0).get("c") != null) return String.valueOf(rows.get(0).get("c"));
        return "id";
    }

    private Map<String, String> loadColumnNamesLower(String tableName) {
        String sql = "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, tableName);
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String col = String.valueOf(r.get("COLUMN_NAME"));
            if (col == null) continue;
            out.put(col.toLowerCase(), col);
        }
        return out;
    }

    // ---------------- schema scan + cache ----------------

    private Schema loadSchema() {
        SchemaCache cur = cacheRef.get();
        long now = System.currentTimeMillis();
        if (cur != null && (now - cur.loadedAt) < CACHE_TTL_MS) return cur.schema;
        Schema fresh = scanSchema();
        cacheRef.set(new SchemaCache(fresh, now));
        return fresh;
    }

    private Schema scanSchema() {
        // columnas
        String colsSql = "SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA " +
                "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()";
        List<Map<String, Object>> colRows = jdbc.queryForList(colsSql);

        Map<String, Map<String, ColumnInfo>> colsByTable = new LinkedHashMap<>();
        for (Map<String, Object> r : colRows) {
            String table = String.valueOf(r.get("TABLE_NAME"));
            String col = String.valueOf(r.get("COLUMN_NAME"));
            String dataType = String.valueOf(r.get("DATA_TYPE"));
            String isNullable = String.valueOf(r.get("IS_NULLABLE"));
            Object def = r.get("COLUMN_DEFAULT");
            String extra = String.valueOf(r.get("EXTRA"));

            colsByTable.computeIfAbsent(table, k -> new LinkedHashMap<>())
                    .put(col, new ColumnInfo(
                            col,
                            dataType,
                            "YES".equalsIgnoreCase(isNullable),
                            def != null,
                            extra != null && extra.toLowerCase().contains("auto_increment")
                    ));
        }

        // PK (soporta compuesta)
        String pkSql = "SELECT TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION " +
                "FROM information_schema.KEY_COLUMN_USAGE " +
                "WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'PRIMARY' " +
                "ORDER BY TABLE_NAME, ORDINAL_POSITION";
        List<Map<String, Object>> pkRows = jdbc.queryForList(pkSql);
        Map<String, List<String>> pkColsByTable = new HashMap<>();
        for (Map<String, Object> r : pkRows) {
            String table = String.valueOf(r.get("TABLE_NAME"));
            String col = String.valueOf(r.get("COLUMN_NAME"));
            if (table == null || col == null) continue;
            pkColsByTable.computeIfAbsent(table, k -> new ArrayList<>()).add(col);
        }

        Map<String, TableInfo> tablesByKey = new LinkedHashMap<>();

        for (var e : colsByTable.entrySet()) {
            String tableName = e.getKey();
            Map<String, ColumnInfo> columns = e.getValue();
            List<String> pkCols = pkColsByTable.get(tableName);
            if (pkCols == null || pkCols.isEmpty()) continue;

            boolean isRelation = isRelationTable(tableName);

            // --- tablas puente / relación ---
            if (isRelation) {
                // PK compuesta (o al menos 2) y numérica
                if (pkCols.size() < 2) continue;
                boolean allNumeric = true;
                for (String pk : pkCols) {
                    ColumnInfo ci = columns.get(pk);
                    if (ci == null || !isNumberType(ci.dataType)) {
                        allNumeric = false;
                        break;
                    }
                }
                if (!allNumeric) continue;

                TableInfo info = TableInfo.relation(tableName, pkCols, columns);
                tablesByKey.put(info.key, info);
                continue;
            }

            // --- tablas de parametría "clásicas" (id + descripcion/nombre + activo) ---
            if (pkCols.size() != 1) continue;
            String pk = pkCols.get(0);
            ColumnInfo pkInfo = columns.get(pk);
            if (pkInfo == null || !isNumberType(pkInfo.dataType)) continue;

            // soft delete
            String activeCol = firstPresent(columns, List.of("activo", "active", "enabled", "habilitado", "is_active"));
            String deletedCol = firstPresent(columns, List.of("deleted", "is_deleted"));
            SoftDeleteMode sdm = SoftDeleteMode.NONE;
            String softCol = null;
            if (activeCol != null) {
                sdm = SoftDeleteMode.ACTIVE_FLAG;
                softCol = activeCol;
            } else if (deletedCol != null) {
                sdm = SoftDeleteMode.DELETED_FLAG;
                softCol = deletedCol;
            }

            // descriptor
            String descCol = firstPresent(columns, List.of(
                    "descripcion", "description", "nombre", "name", "label", "titulo", "title", "code"
            ));

            // criterio: debe tener soft-delete y descriptor
            if (sdm == SoftDeleteMode.NONE) continue;
            if (descCol == null) continue;

            TableInfo info = TableInfo.param(tableName, pk, descCol, softCol, sdm, columns);
            tablesByKey.put(info.key, info);
        }

        return new Schema(tablesByKey, new HashMap<>());
    }

    private static boolean isRelationTable(String tableName) {
        return tableName != null && tableName.toLowerCase().contains("_x_");
    }

    private static String firstPresent(Map<String, ColumnInfo> cols, List<String> names) {
        for (String n : names) {
            for (String k : cols.keySet()) {
                if (k.equalsIgnoreCase(n)) return k;
            }
        }
        return null;
    }

    private static TableInfo requireTable(Schema schema, String key) {
        TableInfo t = schema.tablesByKey.get(key);
        if (t == null) throw new IllegalArgumentException("Tabla no disponible: " + key);
        return t;
    }

    // ---------------- value normalization ----------------

    private Object normalizeIncomingValue(ColumnInfo col, Object v) {
        if (v == null) return null;
        String type = col == null ? null : col.dataType;
        if (type == null) return v;

        try {
            if ("time".equalsIgnoreCase(type)) {
                if (v instanceof String s) {
                    String ss = s.trim();
                    if (ss.isEmpty()) return null;
                    LocalTime lt = ss.length() == 5 ? LocalTime.parse(ss, TIME_FMT) : LocalTime.parse(ss);
                    return Time.valueOf(lt);
                }
                if (v instanceof Time t) return t;
                if (v instanceof LocalTime lt) return Time.valueOf(lt);
            }

            if (isBooleanType(type)) {
                if (v instanceof Boolean b) return b;
                if (v instanceof Number n) return n.intValue() != 0;
                if (v instanceof String s) {
                    String ss = s.trim().toLowerCase();
                    return ss.equals("1") || ss.equals("true") || ss.equals("si") || ss.equals("sí");
                }
            }

            if (isNumberType(type)) {
                if (v instanceof Number) return v;
                if (v instanceof String s) {
                    String ss = s.trim();
                    if (ss.isEmpty()) return null;
                    if (type.equalsIgnoreCase("bigint") || type.equalsIgnoreCase("int") || type.equalsIgnoreCase("smallint") ||
                            type.equalsIgnoreCase("mediumint") || type.equalsIgnoreCase("tinyint")) {
                        return Long.parseLong(ss);
                    }
                    return Double.parseDouble(ss);
                }
            }
        } catch (Exception ignored) {
            // si falla parseo, devuelve original
        }

        return v;
    }

    private static boolean isBooleanType(String dataType) {
        if (dataType == null) return false;
        String t = dataType.toLowerCase();
        return t.equals("bit") || t.equals("boolean") || t.equals("bool") || t.equals("tinyint");
    }

    private static boolean isNumberType(String dataType) {
        if (dataType == null) return false;
        String t = dataType.toLowerCase();
        return t.equals("int") || t.equals("bigint") || t.equals("smallint") || t.equals("mediumint") || t.equals("tinyint")
                || t.equals("decimal") || t.equals("double") || t.equals("float") || t.equals("numeric");
    }

    private static String backtick(String name) {
        if (name == null) return "";
        if (!name.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Identificador inválido");
        }
        return "`" + name + "`";
    }

    // ---------------- internal model ----------------

    private record Schema(Map<String, TableInfo> tablesByKey, Map<String, List<RefGroup>> refsByTable) {
    }

    private record SchemaCache(Schema schema, long loadedAt) {
    }

    private enum SoftDeleteMode {
        NONE,
        ACTIVE_FLAG,
        DELETED_FLAG
    }

    private record ColumnInfo(
            String name,
            String dataType,
            boolean nullable,
            boolean hasDefault,
            boolean autoIncrement
    ) {
    }

    private record RefPair(String columnName, String referencedColumn) {
    }

    private record RefGroup(String tableName, List<RefPair> pairs) {
    }

    private static final class Pk {
        final Map<String, Object> values; // keyed by referenced column names (PK cols)

        private Pk(Map<String, Object> values) {
            this.values = values;
        }

        static Pk single(String col, Object v) {
            return new Pk(new LinkedHashMap<>(Map.of(col, v)));
        }
    }

    private static final class TableInfo {
        final String tableName;
        final String key;
        final boolean relation;
        final List<String> pkColumns;
        final String descriptorColumn; // nullable
        final String softDeleteColumn; // nullable
        final SoftDeleteMode softDeleteMode;
        final Map<String, ColumnInfo> columns;

        private TableInfo(
                String tableName,
                boolean relation,
                List<String> pkColumns,
                String descriptorColumn,
                String softDeleteColumn,
                SoftDeleteMode softDeleteMode,
                Map<String, ColumnInfo> columns
        ) {
            this.tableName = tableName;
            this.key = tableName;
            this.relation = relation;
            this.pkColumns = List.copyOf(pkColumns);
            this.descriptorColumn = descriptorColumn;
            this.softDeleteColumn = softDeleteColumn;
            this.softDeleteMode = softDeleteMode;
            this.columns = columns;
        }

        static TableInfo relation(String tableName, List<String> pkCols, Map<String, ColumnInfo> cols) {
            return new TableInfo(tableName, true, pkCols, null, null, SoftDeleteMode.NONE, cols);
        }

        static TableInfo param(String tableName, String pkCol, String descCol, String softCol, SoftDeleteMode sdm, Map<String, ColumnInfo> cols) {
            return new TableInfo(tableName, false, List.of(pkCol), descCol, softCol, sdm, cols);
        }

        ParamTableDef toDef() {
            if (relation) {
                List<ParamFieldDef> fields = new ArrayList<>();
                for (String pk : pkColumns) {
                    fields.add(fieldFromColumn(pk, true, false, null));
                }
                // En relaciones, no agregamos activo (hard delete por defecto)
                return new ParamTableDef(key, toLabelTable(tableName), "Relaciones", new ArrayList<>(pkColumns), fields);
            }

            List<ParamFieldDef> fields = new ArrayList<>();
            boolean isParametrosTable = tableName != null && tableName.equalsIgnoreCase("parametros");

            // descriptor lógico
            ColumnInfo desc = columns.get(descriptorColumn);
            fields.add(new ParamFieldDef(
                    "descripcion",
                    isParametrosTable ? "Código / Descripción" : "Descripción",
                    "text",
                    desc != null && !desc.nullable,
                    true,
                    null
            ));

            // Campos relevantes extra (sin destination/destino)
            List<String> preferred = isParametrosTable
                    ? List.of(
                            "value", "valor",
                            "code", "codigo",
                            "horario_salida", "horario_regreso",
                            "costo_usd", "sort_order", "orden",
                            "service_code", "status_code", "color", "label"
                    )
                    : List.of(
                            "code", "codigo",
                            "horario_salida", "horario_regreso",
                            "costo_usd", "sort_order", "orden",
                            "service_code", "status_code", "color", "label"
                    );

            Set<String> used = new HashSet<>();
            used.addAll(pkColumns.stream().map(String::toLowerCase).toList());
            if (descriptorColumn != null) used.add(descriptorColumn.toLowerCase());
            if (softDeleteColumn != null) used.add(softDeleteColumn.toLowerCase());

            for (String p : preferred) {
                String col = findColumnIgnoreCase(p);
                if (col == null) continue;
                if (shouldSkipColumn(col)) continue;
                used.add(col.toLowerCase());
                if (isParametrosTable && (col.equalsIgnoreCase("value") || col.equalsIgnoreCase("valor"))) {
                    ColumnInfo valueCol = columns.get(col);
                    String type = "text";
                    if (valueCol != null) {
                        String dt = valueCol.dataType == null ? "" : valueCol.dataType.toLowerCase();
                        if (dt.equals("time")) type = "time";
                        else if (isBooleanType(dt)) type = "boolean";
                        else if (isNumberType(dt) && !dt.equals("tinyint")) type = "number";
                    }
                    fields.add(new ParamFieldDef(
                            col,
                            "Valor cotización",
                            type,
                            valueCol != null && !valueCol.nullable,
                            true,
                            null
                    ));
                } else {
                    fields.add(fieldFromColumn(col, false, true, null));
                }
            }

            // si hay pocos campos, agrega algunos más (máx 8 sin activo)
            for (String col : columns.keySet()) {
                if (fields.size() >= 8) break;
                String lc = col.toLowerCase();
                if (used.contains(lc)) continue;
                if (shouldSkipColumn(col)) continue;
                if (lc.endsWith("_id") || lc.startsWith("id_")) continue;
                used.add(lc);
                fields.add(fieldFromColumn(col, false, true, null));
            }

            // activo lógico
            ColumnInfo act = columns.get(softDeleteColumn);
            fields.add(new ParamFieldDef(
                    "activo",
                    "Activo",
                    "boolean",
                    act != null && !act.nullable,
                    true,
                    true
            ));

            return new ParamTableDef(key, isParametrosTable ? "Parámetros de cotización" : toLabelTable(tableName), toCategory(tableName), null, fields);
        }

        String selectClause() {
            StringBuilder sb = new StringBuilder();

            if (relation) {
                // devuelve PK con sus nombres reales
                for (int i = 0; i < pkColumns.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(backtick(pkColumns.get(i))).append(" AS ").append(backtick(pkColumns.get(i)));
                }
                return sb.toString();
            }

            // PK simple como id
            sb.append(backtick(pkColumns.get(0))).append(" AS id");
            if (descriptorColumn != null) {
                sb.append(", ").append(backtick(descriptorColumn)).append(" AS descripcion");
            }
            sb.append(", ").append(activeSelectClause());

            // extras
            for (ParamFieldDef f : toDef().fields()) {
                String k = f.key();
                if (k == null) continue;
                if (k.equals("descripcion") || k.equals("activo")) continue;
                String physical = logicalToPhysical(k);
                if (physical == null) continue;
                if (!columns.containsKey(physical)) continue;
                if (shouldSkipColumn(physical)) continue;
                if (physical.equalsIgnoreCase(pkColumns.get(0)) || physical.equalsIgnoreCase(descriptorColumn) || physical.equalsIgnoreCase(softDeleteColumn)) {
                    continue;
                }
                sb.append(", ").append(backtick(physical)).append(" AS ").append(backtick(k));
            }

            return sb.toString();
        }

        Map<String, Object> mapRowToLogical(Map<String, Object> row) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (var e : row.entrySet()) {
                String k = String.valueOf(e.getKey());
                Object v = e.getValue();
                if (v instanceof Time t) {
                    out.put(k, t.toLocalTime().format(TIME_FMT));
                } else if (v instanceof Timestamp ts) {
                    out.put(k, ts.toInstant().toString());
                } else if (v instanceof byte[] bytes && bytes.length == 1) {
                    out.put(k, bytes[0] != 0);
                } else {
                    out.put(k, v);
                }
            }

            Object act = out.get("activo");
            if (act instanceof Number n) out.put("activo", n.intValue() != 0);
            if (act instanceof byte[] bytes && bytes.length == 1) out.put("activo", bytes[0] != 0);
            return out;
        }

        Pk pkFromLogicalRow(Map<String, Object> logicalRow) {
            if (logicalRow == null) return null;
            Map<String, Object> m = new LinkedHashMap<>();
            if (relation) {
                for (String pk : pkColumns) {
                    Object v = logicalRow.get(pk);
                    if (v == null) return null;
                    m.put(pk, v);
                }
                return new Pk(m);
            }
            Object id = logicalRow.get("id");
            if (id == null) return null;
            m.put(pkColumns.get(0), id);
            return new Pk(m);
        }

        Pk pkFromPhysicalForCreate(Map<String, Object> physicalValues) {
            if (physicalValues == null) return null;
            Map<String, Object> m = new LinkedHashMap<>();
            for (String pk : pkColumns) {
                Object v = physicalValues.get(pk);
                if (v == null) return null;
                m.put(pk, v);
            }
            return new Pk(m);
        }

        Pk parsePk(String encoded) {
            if (encoded == null || encoded.isBlank()) throw new IllegalArgumentException("Clave inválida");
            String dec = encoded.trim();

            if (relation) {
                String[] parts = dec.split("\\|");
                if (parts.length != pkColumns.size()) throw new IllegalArgumentException("Clave inválida");
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = 0; i < pkColumns.size(); i++) {
                    String col = pkColumns.get(i);
                    ColumnInfo ci = columns.get(col);
                    Object v = normalizePkPart(ci, parts[i]);
                    m.put(col, v);
                }
                return new Pk(m);
            }

            ColumnInfo ci = columns.get(pkColumns.get(0));
            Object v = normalizePkPart(ci, dec);
            return Pk.single(pkColumns.get(0), v);
        }

        private static Object normalizePkPart(ColumnInfo col, String raw) {
            if (raw == null) return null;
            String s = raw.trim();
            if (s.isEmpty()) return null;
            if (col != null && isNumberType(col.dataType)) {
                return Long.parseLong(s);
            }
            return s;
        }

        Map<String, Object> mapLogicalToPhysicalForWrite(Map<String, Object> logical, boolean isCreate) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (logical == null) logical = Map.of();

            if (!relation) {
                // descriptor
                if (logical.containsKey("descripcion") && descriptorColumn != null) {
                    out.put(descriptorColumn, logical.get("descripcion"));
                }

                // activo
                if (logical.containsKey("activo") && softDeleteColumn != null) {
                    Object v = logical.get("activo");
                    if (softDeleteMode == SoftDeleteMode.ACTIVE_FLAG) {
                        out.put(softDeleteColumn, v);
                    } else if (softDeleteMode == SoftDeleteMode.DELETED_FLAG) {
                        Boolean b = (v instanceof Boolean bb) ? bb : null;
                        if (b != null) out.put(softDeleteColumn, !b);
                    }
                } else if (isCreate && softDeleteColumn != null) {
                    if (softDeleteMode == SoftDeleteMode.ACTIVE_FLAG) out.put(softDeleteColumn, true);
                    else if (softDeleteMode == SoftDeleteMode.DELETED_FLAG) out.put(softDeleteColumn, false);
                }
            }

            for (var e : logical.entrySet()) {
                String k = e.getKey();
                if (k == null) continue;
                if (k.equals("canHardDelete")) continue;
                if (!relation) {
                    if (k.equals("id") || k.equals("descripcion") || k.equals("activo")) continue;
                }
                String physical = logicalToPhysical(k);
                if (physical == null) continue;
                if (!columns.containsKey(physical)) continue;
                if (shouldSkipColumn(physical)) continue;
                out.put(physical, e.getValue());
            }

            return out;
        }

        void applyAutoFields(Map<String, Object> physicalValues, boolean isCreate) {
            Instant nowI = Instant.now();
            LocalDateTime nowLdt = LocalDateTime.now();

            if (isCreate) {
                if (columns.containsKey("created_at") && !physicalValues.containsKey("created_at")) {
                    physicalValues.put("created_at", Timestamp.from(nowI));
                }
            }
            if (columns.containsKey("updated_at") && !physicalValues.containsKey("updated_at")) {
                physicalValues.put("updated_at", Timestamp.valueOf(nowLdt));
            }
            if (columns.containsKey("sort_order") && !physicalValues.containsKey("sort_order")) {
                physicalValues.put("sort_order", 0);
            }
        }

        String activeSelectClause() {
            if (softDeleteMode == SoftDeleteMode.ACTIVE_FLAG) {
                return backtick(softDeleteColumn) + " AS activo";
            }
            if (softDeleteMode == SoftDeleteMode.DELETED_FLAG) {
                return "(NOT " + backtick(softDeleteColumn) + ") AS activo";
            }
            return "TRUE AS activo";
        }

        String activeWhereClause(boolean wantActive) {
            if (softDeleteMode == SoftDeleteMode.ACTIVE_FLAG) {
                return backtick(softDeleteColumn) + " = " + (wantActive ? "1" : "0");
            }
            if (softDeleteMode == SoftDeleteMode.DELETED_FLAG) {
                return backtick(softDeleteColumn) + " = " + (wantActive ? "0" : "1");
            }
            return "1=1";
        }

        String activeUpdateClause(boolean setActive) {
            if (softDeleteMode == SoftDeleteMode.ACTIVE_FLAG) {
                return backtick(softDeleteColumn) + " = " + (setActive ? "1" : "0");
            }
            if (softDeleteMode == SoftDeleteMode.DELETED_FLAG) {
                return backtick(softDeleteColumn) + " = " + (setActive ? "0" : "1");
            }
            return "1=1";
        }

        String updatedAtClause() {
            if (columns.containsKey("updated_at")) {
                return ", " + backtick("updated_at") + " = CURRENT_TIMESTAMP";
            }
            return "";
        }

        boolean isAutoIncrement(String col) {
            ColumnInfo c = columns.get(col);
            return c != null && c.autoIncrement;
        }

        boolean isCreatedAt(String col) {
            return col != null && col.equalsIgnoreCase("created_at");
        }

        boolean shouldSkipColumn(String col) {
            if (col == null) return true;
            String lc = col.toLowerCase();
            if (lc.equals("created_at") || lc.equals("updated_at") || lc.equals("deleted_at")) return true;
            if (lc.startsWith("password") || lc.contains("token")) return true;
            return false;
        }

        String logicalToPhysical(String logicalKey) {
            if (logicalKey == null) return null;
            if (!relation) {
                if (logicalKey.equals("descripcion")) return descriptorColumn;
                if (logicalKey.equals("activo")) return softDeleteColumn;
            }
            return logicalKey;
        }

        ParamFieldDef fieldFromColumn(String col, boolean requiredOverride, boolean editable, Object defaultValue) {
            ColumnInfo c = columns.get(col);
            String type = "text";
            if (c != null) {
                String dt = c.dataType == null ? "" : c.dataType.toLowerCase();
                if (dt.equals("time")) type = "time";
                else if (isBooleanType(dt)) type = "boolean";
                else if (isNumberType(dt) && !dt.equals("tinyint")) type = "number";
            }
            boolean required = requiredOverride || (c != null && !c.nullable);
            return new ParamFieldDef(col, toLabelCol(col), type, required, editable, defaultValue);
        }

        String findColumnIgnoreCase(String name) {
            for (String c : columns.keySet()) {
                if (c.equalsIgnoreCase(name)) return c;
            }
            return null;
        }

        static String toLabelCol(String s) {
            if (s == null) return "";
            String lc = s.toLowerCase();
            if (lc.startsWith("id_")) {
                return "ID " + toTitle(lc.substring(3));
            }
            if (lc.endsWith("_id")) {
                return "ID " + toTitle(lc.substring(0, lc.length() - 3));
            }
            return toTitle(lc);
        }

        static String toLabelTable(String tableName) {
            if (tableName == null) return "";
            String t = tableName.toLowerCase();
            if (t.contains("_x_")) {
                String[] parts = t.split("_x_");
                if (parts.length == 2) {
                    return toTitle(parts[0]) + " × " + toTitle(parts[1]);
                }
            }
            return toTitle(t);
        }

        static String toTitle(String s) {
            if (s == null) return "";
            String[] parts = s.replace('-', '_').split("_");
            StringBuilder sb = new StringBuilder();
            for (String p : parts) {
                if (p.isBlank()) continue;
                if (sb.length() > 0) sb.append(" ");
                sb.append(p.substring(0, 1).toUpperCase()).append(p.substring(1));
            }
            return sb.toString();
        }

        static String toCategory(String tableName) {
            String t = tableName.toLowerCase();
            if (t.contains("dest") || t.contains("pais") || t.contains("ciudad") || t.contains("location")) return "Ubicaciones";
            if (t.contains("excurs") || t.contains("serv") || t.contains("prestador") || t.contains("aer") || t.contains("ferry") || t.contains("aloj")) return "Servicios";
            if (t.contains("status") || t.contains("estado") || t.contains("oper")) return "Operación";
            if (t.contains("pay") || t.contains("pago") || t.contains("payment")) return "Pagos";
            return "General";
        }
    }
}
