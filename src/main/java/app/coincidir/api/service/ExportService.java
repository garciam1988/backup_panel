package app.coincidir.api.service;

import app.coincidir.api.domain.TravelRequest;
import app.coincidir.api.repository.TravelRequestRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ExportService {

    private final TravelRequestRepository repo;

    public byte[] export(String format) throws Exception {
        List<TravelRequest> all = repo.findAll();
        return "xlsx".equalsIgnoreCase(format) ? exportXlsx(all) : exportCsv(all);
    }

    private byte[] exportCsv(List<TravelRequest> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("ID,Destino,Fechas,Preferencia,Edad Min,Edad Max,Nombre,Email,Telefono,Ciudad,Fecha Alta\n");
        list.forEach(r -> sb.append(String.format("%d,%s,%s,%s,%d,%d,%s,%s,%s,%s,%s\n",
                r.getId(), r.getDestination(), r.getDatePresetId(), r.getCompanionPreference(),
                r.getAgeMin(), r.getAgeMax(), r.getName(), r.getEmail(), r.getPhone(), r.getCity(), r.getCreatedAt()
        )));

        long total = list.size();
        double avgMin = list.stream().mapToInt(TravelRequest::getAgeMin).average().orElse(0);
        double avgMax = list.stream().mapToInt(TravelRequest::getAgeMax).average().orElse(0);
        sb.append(String.format("\nTOTAL SOLICITUDES: %d | PROM. EDAD MIN: %.1f | PROM. EDAD MAX: %.1f\n", total, avgMin, avgMax));

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] exportXlsx(List<TravelRequest> list) throws Exception {
        Workbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("Solicitudes");
        Row header = sheet.createRow(0);
        String[] headers = {"ID", "Destino", "Fechas", "Preferencia", "Edad Min", "Edad Max", "Nombre", "Email", "Telefono", "Ciudad", "Fecha Alta"};
        for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);

        int rowIdx = 1;
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        for (TravelRequest r : list) {
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(r.getId());
            row.createCell(1).setCellValue(r.getDestination());
            row.createCell(2).setCellValue(r.getDatePresetId());
            row.createCell(3).setCellValue(r.getCompanionPreference());
            row.createCell(4).setCellValue(r.getAgeMin());
            row.createCell(5).setCellValue(r.getAgeMax());
            row.createCell(6).setCellValue(r.getName());
            row.createCell(7).setCellValue(r.getEmail());
            row.createCell(8).setCellValue(r.getPhone());
            row.createCell(9).setCellValue(r.getCity());
            row.createCell(10).setCellValue(r.getCreatedAt().format(fmt));
        }

        Row summary = sheet.createRow(rowIdx + 1);
        long total = list.size();
        double avgMin = list.stream().mapToInt(TravelRequest::getAgeMin).average().orElse(0);
        double avgMax = list.stream().mapToInt(TravelRequest::getAgeMax).average().orElse(0);
        summary.createCell(0).setCellValue("TOTAL SOLICITUDES: " + total);
        summary.createCell(2).setCellValue("PROM. EDAD MIN: " + avgMin);
        summary.createCell(4).setCellValue("PROM. EDAD MAX: " + avgMax);

        for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        wb.write(bos);
        wb.close();
        return bos.toByteArray();
    }
}
