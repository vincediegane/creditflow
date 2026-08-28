export function toAuthenticatedFetchPath(apiUrl: string): string {
  return apiUrl.startsWith('/api/') ? apiUrl.slice('/api'.length) : apiUrl;
}
