/**
 * Same-origin paths the backend answers, not the SPA router: the API, the MCP
 * endpoint, the OpenAPI contract and the crawler files. The click interceptor in
 * main.ts must let a link to one of them reach the server; otherwise the SPA
 * pushes the URL and renders the home page instead (seen with the /api/stats
 * link on /privacy in 2.6.0).
 */
const SERVER_PATHS = [
  /^\/api(?:[/?#]|$)/,
  /^\/mcp(?:[/?#]|$)/,
  /^\/openapi\.json(?:[?#]|$)/,
  /^\/robots\.txt(?:[?#]|$)/,
  /^\/sitemap\.xml(?:[?#]|$)/,
  /^\/llms\.txt(?:[?#]|$)/,
  // 2.14.0 (slice G1): /llms-full.txt and the Markdown copies (path plus .md).
  /^\/llms-full\.txt(?:[?#]|$)/,
  /\.md(?:[?#]|$)/,
];

export function isServerPath(href: string): boolean {
  return SERVER_PATHS.some((pattern) => pattern.test(href));
}
