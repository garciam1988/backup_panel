"use client";

import { getToken, clearToken } from "@/lib/auth";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080";

export type ApiError = { status: number; message: string };

async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const token = getToken();
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
  };
  if (token) headers["Authorization"] = `Bearer ${token}`;

  const res = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: { ...headers, ...(init?.headers as any) },
    cache: "no-store",
  });

  if (res.status === 401) {
    clearToken();
  }

  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw { status: res.status, message: text || res.statusText } as ApiError;
  }
  return (await res.json()) as T;
}

export const api = {
  login: (email: string, password: string) =>
    apiFetch<{ accessToken: string; tokenType: string }>("/api/admin/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password }),
    }),

  getSettings: () => apiFetch<any>("/api/admin/backups/settings"),
  updateSettings: (payload: { enabled?: boolean; dailyTime?: string; dumpPath?: string }) =>
    apiFetch<any>("/api/admin/backups/settings", {
      method: "PUT",
      body: JSON.stringify(payload),
    }),

  listBackups: () => apiFetch<any[]>("/api/admin/backups"),
  forceBackup: () => apiFetch<any>("/api/admin/backups/force", { method: "POST" }),
  restore: (dumpFileName: string, adminCode: string) =>
    apiFetch<any>(`/api/admin/backups/restore/${encodeURIComponent(dumpFileName)}`, {
      method: "POST",
      body: JSON.stringify({ adminCode }),
    }),
  deleteDump: (dumpFileName: string, adminCode: string) =>
    apiFetch<any>(`/api/admin/backups/delete/${encodeURIComponent(dumpFileName)}`, {
      method: "POST",
      body: JSON.stringify({ adminCode }),
    }),
  restoreHistory: (limit = 200) =>
    apiFetch<any[]>(`/api/admin/backups/restore-history?limit=${limit}`),
};
