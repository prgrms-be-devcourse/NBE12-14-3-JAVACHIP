/** Keep post-authentication navigation on this application. */
export function getNextPath(next: string | null): string {
  if (!next?.startsWith("/") || next.startsWith("//")) return "/rooms";
  const origin = "https://budzet.local";
  try {
    const url = new URL(next, origin);
    return url.origin === origin ? url.pathname + url.search + url.hash : "/rooms";
  } catch {
    return "/rooms";
  }
}
