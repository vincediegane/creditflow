import axios, { AxiosError } from 'axios';

export const TOKEN_KEY = 'creditflow.token';
export const USER_KEY = 'creditflow.user';

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL ?? '/api',
  headers: { 'Content-Type': 'application/json' },
});

api.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY);
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    const status = error.response?.status;
    const onLoginPage = window.location.pathname === '/login';
    if (status === 401 && !onLoginPage) {
      localStorage.removeItem(TOKEN_KEY);
      localStorage.removeItem(USER_KEY);
      window.location.replace('/login');
    }
    return Promise.reject(error);
  },
);

interface ApiErrorBody {
  message?: string;
  violations?: { field: string; message: string }[];
}

/** Extrait un message lisible par le commercant a partir d'une erreur API. */
export function errorMessage(error: unknown, fallback = 'Une erreur est survenue'): string {
  if (axios.isAxiosError(error)) {
    const body = error.response?.data as ApiErrorBody | undefined;
    if (body?.violations?.length) {
      return body.violations.map((v) => v.message).join(' • ');
    }
    if (body?.message) {
      return body.message;
    }
    if (!error.response) {
      return 'Serveur injoignable. Verifiez que le backend est demarre.';
    }
  }
  return fallback;
}
