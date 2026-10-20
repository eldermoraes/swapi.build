/**
 * The header menu item (by href) that is active for a route page, or null when
 * the page has no menu item.
 */
export function activeNavHref(page: string): string | null {
  switch (page) {
    case 'home':
      return '/';
    case 'docs':
    case 'webmcp':
      return '/docs';
    case 'stats':
      return '/stats';
    case 'mcp':
      return '/docs/mcp';
    case 'about':
      return '/about';
    default:
      return null;
  }
}
