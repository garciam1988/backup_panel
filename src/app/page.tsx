"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { getToken, clearToken, getUsername } from "@/lib/auth";
import { Button } from "@/components/Button";
import { Modal } from "@/components/Modal";

type BackupRow = {
  fileName: string;
  filePath: string;
  sizeBytes: number;
  createdAt?: string;
  trigger?: string;
  status?: string;
  message?: string;
};

type RestoreRow = {
  startedAt?: string;
  finishedAt?: string;
  dumpFileName?: string;
  status?: string;
  message?: string;
  performedBy?: string;
};

function fmtTrigger(trigger?: string) {
  const t = (trigger || "").trim();
  if (!t) return "-";
  const u = t.toUpperCase();
  if (u.includes("AUTO") || u.includes("SCHED") || u.includes("CRON")) return "AUTOMATICO";
  if (u.includes("MANUAL") || u.includes("FORCE")) return "MANUAL";
  if (u === "AUTOMATICO") return "AUTOMATICO";
  return t;
}

function formatBytes(n: number) {
  if (!Number.isFinite(n)) return "-";
  if (n < 1024) return `${n} B`;
  const kb = n / 1024;
  if (kb < 1024) return `${kb.toFixed(1)} KB`;
  const mb = kb / 1024;
  if (mb < 1024) return `${mb.toFixed(1)} MB`;
  const gb = mb / 1024;
  return `${gb.toFixed(2)} GB`;
}

function fmtDate(s?: string) {
  if (!s) return "-";
  const d = new Date(s);
  if (Number.isNaN(d.getTime())) return s;
  return d.toLocaleString();
}

function normalizeTimeHHmm(v?: string) {
  const raw = (v || "").trim();
  if (!raw) return "02:00";
  const m = raw.match(/^(\d{1,2}):(\d{2})/);
  if (!m) return raw;
  const hh = String(Math.min(23, Math.max(0, parseInt(m[1], 10) || 0))).padStart(2, "0");
  const mm = String(Math.min(59, Math.max(0, parseInt(m[2], 10) || 0))).padStart(2, "0");
  return `${hh}:${mm}`;
}

export default function HomePage() {
  const router = useRouter();
  const [tab, setTab] = React.useState<"backups" | "restores">("backups");

  // Evitar mismatch de hidratación: el username viene de localStorage (solo cliente)
  const [username, setUsername] = React.useState("");

  const [settings, setSettings] = React.useState<{
    enabled: boolean;
    dailyTime: string;
    dumpPath?: string;
  } | null>(null);
  const [supportsDumpPath, setSupportsDumpPath] = React.useState(false);
  const [settingsSaving, setSettingsSaving] = React.useState(false);

  const [backups, setBackups] = React.useState<BackupRow[]>([]);
  const [loadingBackups, setLoadingBackups] = React.useState(false);

  const [restores, setRestores] = React.useState<RestoreRow[]>([]);
  const [loadingRestores, setLoadingRestores] = React.useState(false);

  const [msg, setMsg] = React.useState<{ type: "ok" | "err"; text: string } | null>(null);

  const [restoreModalOpen, setRestoreModalOpen] = React.useState(false);
  const [restoreTarget, setRestoreTarget] = React.useState<BackupRow | null>(null);
  const [adminCode, setAdminCode] = React.useState("");
  const [restoring, setRestoring] = React.useState(false);

  const [deleteModalOpen, setDeleteModalOpen] = React.useState(false);
  const [deleteTarget, setDeleteTarget] = React.useState<BackupRow | null>(null);
  const [deleteAdminCode, setDeleteAdminCode] = React.useState("");
  const [deleting, setDeleting] = React.useState(false);

  React.useEffect(() => {
    if (!getToken()) router.replace("/login");
  }, [router]);

  React.useEffect(() => {
    setUsername(getUsername() || "");
  }, []);

  async function loadAll() {
    setMsg(null);
    await Promise.all([loadSettings(), loadBackups(), loadRestores(200)]);
  }

  async function loadSettings() {
    try {
      const s = await api.getSettings();
      // Compat: backend puede devolver dumpPath o dumpsDir (histórico)
      const rawDumpPath =
        (typeof s?.dumpPath === "string" ? String(s.dumpPath) : "") ||
        (typeof s?.dumpsDir === "string" ? String(s.dumpsDir) : "");

      // Siempre habilitar edición: si el backend no soporta, fallará al guardar.
      setSupportsDumpPath(true);
      setSettings({
        enabled: !!s.enabled,
        dailyTime: s.dailyTime || "02:00",
        dumpPath: rawDumpPath,
      });
    } catch (e: any) {
      if (e?.status === 401) {
        clearToken();
        router.replace("/login");
        return;
      }
      setMsg({ type: "err", text: "No se pudo cargar la configuración" });
    }
  }

  async function loadBackups() {
    setLoadingBackups(true);
    try {
      const rows = await api.listBackups();
      setBackups(rows);
    } catch (e: any) {
      if (e?.status === 401) {
        clearToken();
        router.replace("/login");
        return;
      }
      setMsg({ type: "err", text: "No se pudo cargar la lista de backups" });
    } finally {
      setLoadingBackups(false);
    }
  }

  async function loadRestores(limit = 200) {
    setLoadingRestores(true);
    try {
      const rows = await api.restoreHistory(limit);
      setRestores(rows);
    } catch (e: any) {
      if (e?.status === 401) {
        clearToken();
        router.replace("/login");
        return;
      }
      setMsg({ type: "err", text: "No se pudo cargar el histórico de restores" });
    } finally {
      setLoadingRestores(false);
    }
  }

  React.useEffect(() => {
    loadAll();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function saveSettings() {
    if (!settings) return;
    setSettingsSaving(true);
    setMsg(null);
    try {
      const dailyTime = normalizeTimeHHmm(settings.dailyTime);
      const payload: any = { enabled: settings.enabled, dailyTime };

      // Compat: enviar dumpsDir (backend) y dumpPath (por si el backend expone ese nombre)
      if (typeof settings.dumpPath === "string") {
        const v = settings.dumpPath.trim();
        if (v) {
          payload.dumpsDir = v;
          payload.dumpPath = v;
        }
      }
      const res = await api.updateSettings(payload);
      const nextDumpPath =
        (typeof res?.dumpPath === "string" ? String(res.dumpPath) : "") ||
        (typeof res?.dumpsDir === "string" ? String(res.dumpsDir) : "") ||
        settings.dumpPath;
      setSettings({
        enabled: !!res.enabled,
        dailyTime: res.dailyTime || settings.dailyTime,
        dumpPath: nextDumpPath,
      });
      setMsg({ type: "ok", text: "Configuración guardada" });
    } catch (e: any) {
      setMsg({ type: "err", text: e?.status === 403 ? "No autorizado" : "No se pudo guardar" });
    } finally {
      setSettingsSaving(false);
    }
  }

  async function forceBackup() {
    setMsg(null);
    try {
      const res = await api.forceBackup();
      if (res && res.ok === false) {
        setMsg({ type: "err", text: res.message || "No se pudo generar el backup" });
        await loadBackups();
        return;
      }
      setMsg({ type: "ok", text: "Backup generado" });
      await loadBackups();
    } catch (e: any) {
      setMsg({ type: "err", text: e?.message || "No se pudo generar el backup" });
    }
  }

  function openRestore(row: BackupRow) {
    setRestoreTarget(row);
    setAdminCode("");
    setRestoreModalOpen(true);
  }

  function openDelete(row: BackupRow) {
    setDeleteTarget(row);
    setDeleteAdminCode("");
    setDeleteModalOpen(true);
  }

  async function confirmRestore() {
    if (!restoreTarget) return;
    setRestoring(true);
    setMsg(null);
    try {
      await api.restore(restoreTarget.fileName, adminCode);
      setRestoreModalOpen(false);
      setMsg({ type: "ok", text: "Restore ejecutado con éxito" });
      await loadRestores(200);
    } catch (e: any) {
      const txt = e?.status === 403 ? "Código inválido" : "Restore falló";
      setMsg({ type: "err", text: txt });
    } finally {
      setRestoring(false);
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) return;
    setDeleting(true);
    setMsg(null);
    try {
      const res = await api.deleteDump(deleteTarget.fileName, deleteAdminCode);
      if (res && res.ok === false) {
        setMsg({ type: "err", text: res.message || "No se pudo eliminar el dump" });
      } else {
        setDeleteModalOpen(false);
        setMsg({ type: "ok", text: "Dump eliminado" });
        await loadBackups();
      }
    } catch (e: any) {
      const txt = e?.status === 403 ? "Código inválido" : (e?.message || "No se pudo eliminar el dump");
      setMsg({ type: "err", text: txt });
    } finally {
      setDeleting(false);
    }
  }

  function logout() {
    clearToken();
    router.replace("/login");
  }

  return (
    <div className="min-h-screen">
      <div className="sticky top-0 z-10 border-b border-slate-200 bg-white/80 backdrop-blur">
        <div className="mx-auto max-w-6xl px-6 py-4 flex items-center justify-between">
          <div>
            <div className="text-lg font-semibold">Backups & Restores</div>
            <div className="text-xs text-slate-600">Coincidir · Admin</div>
          </div>
          <div className="flex items-center gap-3">
            <div className="text-sm text-slate-700">{username}</div>
            <Button variant="secondary" onClick={logout}>Salir</Button>
          </div>
        </div>
        <div className="mx-auto max-w-6xl px-6 pb-3">
          <div className="inline-flex rounded-lg border border-slate-200 bg-white overflow-hidden">
            <button
              className={`px-4 py-2 text-sm ${tab === "backups" ? "bg-slate-900 text-white" : "hover:bg-slate-50"}`}
              onClick={() => setTab("backups")}
            >
              Backups
            </button>
            <button
              className={`px-4 py-2 text-sm ${tab === "restores" ? "bg-slate-900 text-white" : "hover:bg-slate-50"}`}
              onClick={() => setTab("restores")}
            >
              Restores
            </button>
          </div>
        </div>
      </div>

      <div className="mx-auto max-w-6xl px-6 py-6">
        {msg && (
          <div
            className={`mb-4 rounded-md border px-3 py-2 text-sm ${
              msg.type === "ok"
                ? "bg-emerald-50 border-emerald-200 text-emerald-800"
                : "bg-red-50 border-red-200 text-red-800"
            }`}
          >
            {msg.text}
          </div>
        )}

        {tab === "backups" && (
          <div className="space-y-4">
            <div className="rounded-xl border border-slate-200 bg-white p-4">
              <div className="flex flex-col gap-3 md:flex-row md:items-end md:justify-between">
                <div className="flex flex-col gap-3 md:flex-row md:items-end">
                  <div>
                    <div className="text-sm font-medium text-slate-700">Ejecución diaria</div>
                    <div className="mt-1 flex items-center gap-2">
                      <input
                        type="checkbox"
                        checked={!!settings?.enabled}
                        onChange={(e) => setSettings((s) => (s ? { ...s, enabled: e.target.checked } : s))}
                      />
                      <span className="text-sm text-slate-700">Habilitado</span>
                    </div>
                  </div>

                  <div>
                    <div className="text-sm font-medium text-slate-700">Horario</div>
                    <input
                      type="time"
                      className="mt-1 rounded-md border border-slate-200 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-slate-300"
                      value={settings?.dailyTime || "02:00"}
                      onChange={(e) =>
                        setSettings((s) => (s ? { ...s, dailyTime: normalizeTimeHHmm(e.target.value) } : s))
                      }
                    />
                  </div>

                  <div className="min-w-[320px]">
                    <div className="text-sm font-medium text-slate-700">Ruta dumps</div>
                    <input
                      type="text"
                      className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-slate-300 disabled:bg-slate-50"
                      value={settings?.dumpPath || ""}
                      onChange={(e) => setSettings((s) => (s ? { ...s, dumpPath: e.target.value } : s))}
                      placeholder="C:\\Coincidir\\Coincidir Backups Panel\\coincidir-backups-panel\\dumps"
                      disabled={!supportsDumpPath}
                    />
                  </div>

                  <Button onClick={saveSettings} disabled={!settings || settingsSaving}>
                    {settingsSaving ? "Guardando..." : "Guardar"}
                  </Button>
                </div>

                <div className="flex items-center gap-2">
                  <Button variant="secondary" onClick={loadBackups} disabled={loadingBackups}>
                    {loadingBackups ? "Actualizando..." : "Actualizar"}
                  </Button>
                  <Button onClick={forceBackup}>Force Backup</Button>
                </div>
              </div>
            </div>

            <div className="rounded-xl border border-slate-200 bg-white overflow-hidden">
              <div className="px-4 py-3 border-b border-slate-200 flex items-center justify-between">
                <div className="text-sm font-semibold">Backups generados</div>
                <div className="text-xs text-slate-500">
                  Ruta configurada: {settings?.dumpPath ? settings.dumpPath : "dumps"}
                </div>
              </div>
              <div className="overflow-x-auto">
                <table className="min-w-full text-sm">
                  <thead className="bg-slate-50 text-slate-700">
                    <tr>
                      <th className="text-left font-medium px-4 py-3">Fecha</th>
                      <th className="text-left font-medium px-4 py-3">Archivo</th>
                      <th className="text-left font-medium px-4 py-3">Tamaño</th>
                      <th className="text-left font-medium px-4 py-3">Tipo</th>
                      <th className="text-left font-medium px-4 py-3">Estado</th>
                      <th className="text-right font-medium px-4 py-3">Acciones</th>
                    </tr>
                  </thead>
                  <tbody>
                    {backups.length === 0 && (
                      <tr>
                        <td colSpan={6} className="px-4 py-6 text-center text-slate-500">
                          {loadingBackups ? "Cargando..." : "Sin backups"}
                        </td>
                      </tr>
                    )}
                    {backups.map((b) => (
                      <tr key={b.fileName} className="border-t border-slate-100">
                        <td className="px-4 py-3 whitespace-nowrap">{fmtDate(b.createdAt)}</td>
                        <td className="px-4 py-3 font-mono text-xs whitespace-nowrap">{b.fileName}</td>
                        <td className="px-4 py-3 whitespace-nowrap">{formatBytes(b.sizeBytes)}</td>
                        <td className="px-4 py-3 whitespace-nowrap">{fmtTrigger(b.trigger)}</td>
                        <td className="px-4 py-3 whitespace-nowrap">
                          <span
                            className={`inline-flex rounded-full px-2 py-1 text-xs font-medium ${
                              ["SUCCESS", "OK"].includes((b.status || "").toUpperCase())
                                ? "bg-emerald-50 text-emerald-700"
                                : (b.status || "").toUpperCase() === "FAILED"
                                ? "bg-red-50 text-red-700"
                                : "bg-slate-100 text-slate-700"
                            }`}
                          >
                            {b.status || "-"}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-right whitespace-nowrap">
                          <div className="inline-flex items-center justify-end gap-2">
                            <Button
                              variant="danger"
                              onClick={() => openRestore(b)}
                              disabled={!(["SUCCESS", "OK"].includes((b.status || "").toUpperCase()))}
                            >
                              Restore
                            </Button>
                            <Button
                              variant="secondary"
                              className="text-red-700 border-red-200 hover:bg-red-50"
                              onClick={() => openDelete(b)}
                            >
                              Eliminar
                            </Button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        )}

        {tab === "restores" && (
          <div className="rounded-xl border border-slate-200 bg-white overflow-hidden">
            <div className="px-4 py-3 border-b border-slate-200 flex items-center justify-between">
              <div className="text-sm font-semibold">Histórico de restores</div>
              <div className="flex items-center gap-2">
                <Button variant="secondary" onClick={() => loadRestores(200)} disabled={loadingRestores}>
                  {loadingRestores ? "Actualizando..." : "Actualizar"}
                </Button>
              </div>
            </div>
            <div className="overflow-x-auto">
              <table className="min-w-full text-sm">
                <thead className="bg-slate-50 text-slate-700">
                  <tr>
                    <th className="text-left font-medium px-4 py-3">Inicio</th>
                    <th className="text-left font-medium px-4 py-3">Fin</th>
                    <th className="text-left font-medium px-4 py-3">Dump</th>
                    <th className="text-left font-medium px-4 py-3">Usuario</th>
                    <th className="text-left font-medium px-4 py-3">Estado</th>
                  </tr>
                </thead>
                <tbody>
                  {restores.length === 0 && (
                    <tr>
                      <td colSpan={5} className="px-4 py-6 text-center text-slate-500">
                        {loadingRestores ? "Cargando..." : "Sin restores"}
                      </td>
                    </tr>
                  )}
                  {restores.map((r, idx) => (
                    <tr key={idx} className="border-t border-slate-100">
                      <td className="px-4 py-3 whitespace-nowrap">{fmtDate(r.startedAt)}</td>
                      <td className="px-4 py-3 whitespace-nowrap">{fmtDate(r.finishedAt)}</td>
                      <td className="px-4 py-3 font-mono text-xs whitespace-nowrap">{r.dumpFileName || "-"}</td>
                      <td className="px-4 py-3 whitespace-nowrap">{r.performedBy || "-"}</td>
                      <td className="px-4 py-3 whitespace-nowrap">
                        <span
                          className={`inline-flex rounded-full px-2 py-1 text-xs font-medium ${
                            (r.status || "").toUpperCase() === "SUCCESS"
                              ? "bg-emerald-50 text-emerald-700"
                              : (r.status || "").toUpperCase() === "FAILED"
                              ? "bg-red-50 text-red-700"
                              : "bg-slate-100 text-slate-700"
                          }`}
                        >
                          {r.status || "-"}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>

      <Modal
        open={restoreModalOpen}
        title="Confirmar Restore"
        onClose={() => (!restoring ? setRestoreModalOpen(false) : null)}
      >
        <div className="space-y-3">
          <div className="text-sm text-slate-700">
            Esta acción hará <span className="font-semibold">DROP</span> de la base <span className="font-mono">coincidir</span> y luego importará el dump seleccionado.
          </div>
          <div className="rounded-md bg-amber-50 border border-amber-200 px-3 py-2 text-sm text-amber-900">
            Dump: <span className="font-mono text-xs">{restoreTarget?.fileName}</span>
          </div>
          <div>
            <label className="block text-sm font-medium text-slate-700">Código de autorización (ADMIN)</label>
            <input
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-slate-300"
              value={adminCode}
              onChange={(e) => setAdminCode(e.target.value)}
              autoComplete="off"
            />
          </div>
          <div className="flex items-center justify-end gap-2">
            <Button variant="secondary" onClick={() => setRestoreModalOpen(false)} disabled={restoring}>
              Cancelar
            </Button>
            <Button variant="danger" onClick={confirmRestore} disabled={restoring || !adminCode.trim()}>
              {restoring ? "Restaurando..." : "Confirmar Restore"}
            </Button>
          </div>
        </div>
      </Modal>

      <Modal
        open={deleteModalOpen}
        title="Eliminar dump"
        onClose={() => (!deleting ? setDeleteModalOpen(false) : null)}
      >
        <div className="space-y-3">
          <div className="text-sm text-slate-700">
            Esta acción eliminará el archivo del dump en el servidor.
          </div>
          <div className="rounded-md bg-red-50 border border-red-200 px-3 py-2 text-sm text-red-900">
            Dump: <span className="font-mono text-xs">{deleteTarget?.fileName}</span>
          </div>
          <div>
            <label className="block text-sm font-medium text-slate-700">Código de autorización (ADMIN)</label>
            <input
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-slate-300"
              value={deleteAdminCode}
              onChange={(e) => setDeleteAdminCode(e.target.value)}
              autoComplete="off"
            />
          </div>
          <div className="flex items-center justify-end gap-2">
            <Button variant="secondary" onClick={() => setDeleteModalOpen(false)} disabled={deleting}>
              Cancelar
            </Button>
            <Button variant="danger" onClick={confirmDelete} disabled={deleting || !deleteAdminCode.trim()}>
              {deleting ? "Eliminando..." : "Eliminar"}
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
