import { expect, it } from 'vitest';
import { renderWebMcp } from './webmcp';

// 2.13.0 (slice F1): the remote MCP tools gained optional expand and fields;
// the browser tools did not, and the page says so.
it('says that expand and fields exist only on the remote server', () => {
  const container = document.createElement('main');
  renderWebMcp(container, 'unavailable');
  const text = container.textContent ?? '';
  expect(text).toContain(
    'Names and required arguments match the remote MCP tools; the optional expand and fields arguments exist only on the remote server.',
  );
  expect(text).not.toContain('Names and arguments match the remote MCP tools.');
});
