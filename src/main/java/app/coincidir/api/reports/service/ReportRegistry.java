package app.coincidir.api.reports.service;

import org.springframework.stereotype.Component;

import java.util.*;

import static app.coincidir.api.reports.service.ReportAgg.*;
import static app.coincidir.api.reports.service.ReportFieldType.*;

@Component
public class ReportRegistry {

    private final List<ReportDataSource> dataSources;
    private final Map<String, ReportDataSource> byId;

    public ReportRegistry() {
        List<ReportDataSource> list = new ArrayList<>();

        list.add(build(
                "groups",
                "Grupos",
                "Vista de grupos (para BI).",
                "vw_report_groups",
                List.of(
                        field("group_id", "ID Grupo", NUMBER, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("destination", "Destino", STRING, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("departure_month", "Mes salida", STRING, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("departure_year", "Anio salida", NUMBER, EnumSet.of(COUNT, COUNT_DISTINCT, MIN, MAX, AVG)),
                        field("group_status", "Estado", STRING, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("group_created_at", "Creado", DATETIME, EnumSet.of(COUNT, MIN, MAX)),
                        field("travel_start_date", "Inicio viaje", DATE, EnumSet.of(COUNT, MIN, MAX)),
                        field("travel_end_date", "Fin viaje", DATE, EnumSet.of(COUNT, MIN, MAX)),
                        field("auto_search_enabled", "Auto search", BOOLEAN, EnumSet.of(COUNT)),
                        field("auto_search_added", "Auto search agregados", NUMBER, EnumSet.of(SUM, AVG, MIN, MAX)),
                        field("member_count", "Cantidad miembros", NUMBER, EnumSet.of(SUM, AVG, MIN, MAX)),
                        field("travelers_total", "Total viajeros", NUMBER, EnumSet.of(SUM, AVG, MIN, MAX)),
                        field("operation_confirmed", "Operacion confirmada", BOOLEAN, EnumSet.of(COUNT))
                )
        ));

        list.add(build(
                "group_members",
                "Miembros de grupos",
                "Miembros (requests) asociados a un grupo.",
                "vw_report_group_members",
                List.of(
                        field("group_id", "ID Grupo", NUMBER, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("group_status", "Estado grupo", STRING, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("destination", "Destino", STRING, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("departure_month", "Mes salida", STRING, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("departure_year", "Anio salida", NUMBER, EnumSet.of(COUNT, COUNT_DISTINCT, MIN, MAX, AVG)),
                        field("member_id", "ID Miembro", NUMBER, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("member_status", "Estado request", STRING, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("member_name", "Nombre", STRING, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("member_email", "Email", STRING, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("gender", "Genero", STRING, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("created_at", "Creado", DATETIME, EnumSet.of(COUNT, MIN, MAX)),
                        field("travelers_total", "Viajeros", NUMBER, EnumSet.of(SUM, AVG, MIN, MAX)),
                        field("deposit_amount", "Senia monto", NUMBER, EnumSet.of(SUM, AVG, MIN, MAX)),
                        field("deposit_payment_method", "Senia metodo", STRING, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("deposit_date", "Senia fecha", DATE, EnumSet.of(COUNT, MIN, MAX))
                )
        ));

        list.add(build(
                "payment_records",
                "Pagos (registros)",
                "Pagos cargados por miembro.",
                "vw_report_member_payment_records",
                List.of(
                        field("payment_record_id", "ID Pago", NUMBER, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("group_id", "ID Grupo", NUMBER, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("destination", "Destino", STRING, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("group_status", "Estado grupo", STRING, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("member_id", "ID Miembro", NUMBER, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("member_email", "Email", STRING, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("member_name", "Nombre", STRING, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("payment_date", "Fecha pago", DATE, EnumSet.of(COUNT, MIN, MAX)),
                        field("created_at", "Creado", DATETIME, EnumSet.of(COUNT, MIN, MAX)),
                        field("amount", "Importe", NUMBER, EnumSet.of(SUM, AVG, MIN, MAX)),
                        field("currency", "Moneda", STRING, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("receipt_last4", "Comprobante ult 4", STRING, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("installment_number", "Nro cuota", NUMBER, EnumSet.of(COUNT, COUNT_DISTINCT, MIN, MAX, AVG)),
                        field("plan_type", "Tipo plan", STRING, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("one_time_method", "Metodo (si 1 pago)", STRING, EnumSet.of(COUNT, COUNT_DISTINCT)),
                        field("plan_total_amount", "Total plan", NUMBER, EnumSet.of(SUM, AVG, MIN, MAX)),
                        field("plan_currency", "Moneda plan", STRING, EnumSet.of(COUNT, COUNT_DISTINCT))
                )
        ));

        this.dataSources = Collections.unmodifiableList(list);
        Map<String, ReportDataSource> m = new LinkedHashMap<>();
        for (ReportDataSource ds : list) m.put(ds.id(), ds);
        this.byId = Collections.unmodifiableMap(m);
    }

    public List<ReportDataSource> getDataSources() {
        return dataSources;
    }

    public ReportDataSource getOrThrow(String id) {
        ReportDataSource ds = byId.get(id);
        if (ds == null) throw new app.coincidir.api.common.exception.BadRequestException("Unknown dataSourceId: " + id);
        return ds;
    }

    private static ReportDataSource build(String id, String label, String description, String fromSql, List<ReportField> fields) {
        Map<String, ReportField> byKey = new LinkedHashMap<>();
        for (ReportField f : fields) byKey.put(f.key(), f);
        return new ReportDataSource(id, label, description, fromSql, fields, byKey);
    }

    private static ReportField field(String key, String label, ReportFieldType type, EnumSet<ReportAgg> aggs) {
        return ReportField.of(key, label, type, aggs);
    }
}
