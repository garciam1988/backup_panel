package app.coincidir.api.botplatform.service;

import app.coincidir.api.botplatform.domain.ExcelCatalog;
import app.coincidir.api.botplatform.repository.ExcelCatalogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * DataSourceRefreshJob — cada hora revisa las fuentes de datos con
 * auto-refresh configurado y re-descarga las que tienen el TTL vencido.
 *
 * Condiciones para refrescar:
 *   - source_type = "url"
 *   - auto_refresh_hours no null ni 0
 *   - last_refreshed_at + auto_refresh_hours <= now()
 *   - active = true
 *
 * Si la descarga falla, se loguea pero no se pisa el contenido anterior
 * (el bot sigue sirviendo la última versión exitosa).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSourceRefreshJob {

    private final ExcelCatalogRepository catalogRepo;
    private final RemoteFileDownloader downloader;
    private final DataSourceIngestService ingestService;
    private final ExcelCatalogService catalogService;

    /** Corre al minuto 0 de cada hora. */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void refreshAll() {
        List<ExcelCatalog> all = catalogRepo.findAll();
        Instant now = Instant.now();
        int attempted = 0, refreshed = 0, failed = 0;

        for (ExcelCatalog cat : all) {
            if (!Boolean.TRUE.equals(cat.getActive())) continue;
            if (!"url".equals(cat.getSourceType())) continue;
            Integer hours = cat.getAutoRefreshHours();
            if (hours == null || hours <= 0) continue;
            if (cat.getOriginalUrl() == null) continue;

            Instant last = cat.getLastRefreshedAt() != null ? cat.getLastRefreshedAt() : cat.getCreatedAt();
            if (last == null) last = Instant.EPOCH;
            Instant due = last.plus(Duration.ofHours(hours));
            if (now.isBefore(due)) continue;

            attempted++;
            try {
                RemoteFileDownloader.DownloadResult dl = downloader.download(cat.getOriginalUrl());
                if (dl == null) {
                    failed++;
                    log.warn("[RefreshJob] fallo descarga: {} (url={})", cat.getName(), cat.getOriginalUrl());
                    continue;
                }
                String mime = DataSourceIngestService.normalizeMime(dl.mimeType, dl.filename);

                if (DataSourceIngestService.isTabular(mime)) {
                    // Delegamos a ExcelCatalogService — re-parsea las filas.
                    // Preservamos el branchId original del catálogo así el refresh
                    // no muta a qué sucursal pertenece (sería un bug grave).
                    MultipartFile mf = new ByteArrayMultipartFile(
                            "file", dl.filename, mime, dl.content);
                    catalogService.uploadCatalog(cat.getName(), cat.getDescription(),
                            cat.getBranchId(), mf, "refresh-job");
                    // Releer y re-setear metadata URL. Buscamos por (name, branchId)
                    // porque tras Bloque 1 dos sucursales pueden tener el mismo name.
                    ExcelCatalog updated = catalogRepo.findByNameAndBranchId(
                            cat.getName(), cat.getBranchId()).orElse(cat);
                    updated.setSourceType("url");
                    updated.setOriginalUrl(cat.getOriginalUrl());
                    updated.setAutoRefreshHours(hours);
                    updated.setMimeType(mime);
                    updated.setLastRefreshedAt(now);
                    catalogRepo.save(updated);
                } else {
                    // Extraer texto y actualizar
                    DataSourceIngestService.IngestResult ir = ingestService.extractText(dl.content, dl.filename, mime);
                    cat.setOriginalFilename(dl.filename);
                    cat.setSizeBytes((long) dl.content.length);
                    cat.setMimeType(mime);
                    cat.setLastRefreshedAt(now);
                    if (ir != null) {
                        cat.setExtractedText(ir.extractedText);
                        cat.setTokenCount(ir.tokenCount);
                    }
                    catalogRepo.save(cat);
                }
                refreshed++;
                log.info("[RefreshJob] OK {} (url={})", cat.getName(), cat.getOriginalUrl());
            } catch (Exception e) {
                failed++;
                log.warn("[RefreshJob] error en {}: {}", cat.getName(), e.getMessage());
            }
        }
        if (attempted > 0) {
            log.info("[RefreshJob] attempted={} refreshed={} failed={}", attempted, refreshed, failed);
        }
    }
}
