import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { resolve } from 'node:path';

/**
 * Q-2 — the end-to-end journey the product exists for: point Service Atlas at a folder of
 * repositories, scan it, look at the diagram, and export something you can share.
 *
 * Asserted against the real fixture repositories under `e2e/fixtures/`, through the packaged jar.
 */

const FIXTURES = resolve(__dirname, '../fixtures');

test.describe('scan the fixture estate and export it', () => {
  /**
   * Each test starts from the first-run state. Deleting through the API rather than resetting the
   * database keeps the tests honest about the delete endpoint, and makes them independent of the
   * order they run in.
   */
  test.beforeEach(async ({ request }) => {
    const workspaces = await (await request.get('/api/v1/workspaces')).json();
    for (const workspace of workspaces) {
      await request.delete(`/api/v1/workspaces/${workspace.id}`);
    }
  });

  test('create workspace → scan → explore → export .lucid', async ({ page }, testInfo) => {
    // ---------------------------------------------------------------- create + scan
    await page.goto('/');

    await expect(page.getByRole('heading', { name: 'Map your services' })).toBeVisible();

    await page.fill('#rootPath', FIXTURES);
    await page.getByRole('button', { name: 'Check' }).click();

    const preview = page.locator('.preview');
    await expect(preview).toContainText('9 repositories');

    await page.getByRole('button', { name: /Create workspace and scan/ }).click();

    // ---------------------------------------------------------------- progress (FR-7.1)
    await expect(page.locator('svg.canvas')).toBeVisible({ timeout: 90_000 });
    await page.waitForTimeout(2_500);

    // ---------------------------------------------------------------- the graph (FR-2, FR-3)
    const nodes = page.locator('g.node');
    const edges = page.locator('g.edge');

    // Nine repositories, one deployable sub-module, four topics, three external services and
    // eight datastores.
    await expect(nodes).toHaveCount(26);
    await expect(edges).toHaveCount(35);

    await expect(page.locator('.toolbar-stats')).toContainText('26 nodes');
    await expect(page.locator('.toolbar-stats')).toContainText('35 dependencies');

    // Every signal source in FR-3 is represented on the canvas.
    const graph = await page.evaluate(async () => {
      const workspaces = await (await fetch('/api/v1/workspaces')).json();
      return (await fetch(`/api/v1/workspaces/${workspaces[0].id}/graph`)).json();
    });
    const edgeTypes = new Set(graph.graph.edges.map((edge: { type: string }) => edge.type));
    expect([...edgeTypes].sort()).toEqual(['ARTIFACT', 'HTTP', 'MESSAGING', 'PERSISTENCE']);
    expect(graph.graph.nodes.some((node: { type: string }) => node.type === 'TOPIC')).toBe(true);
    expect(graph.graph.nodes.some((node: { type: string }) => node.type === 'EXTERNAL')).toBe(true);
    expect(graph.graph.nodes.some((node: { type: string }) => node.type === 'SUB_MODULE')).toBe(true);
    expect(graph.graph.nodes.some((node: { type: string }) => node.type === 'DATASTORE')).toBe(true);

    // A shared datastore is drawn once, with an edge from each service that uses it (FR-3.9).
    const cache = graph.graph.nodes.find(
      (node: { type: string; displayName: string }) =>
        node.type === 'DATASTORE' && node.displayName.startsWith('pricing-cache'),
    );
    expect(cache).toBeTruthy();
    expect(
      graph.graph.edges.filter((edge: { targetKey: string }) => edge.targetKey === cache.key),
    ).toHaveLength(2);

    // ---------------------------------------------------------------- inspector (FR-5.3, FR-5.4)
    await page.locator('g.node', { hasText: 'log-order-svc' }).first().click();
    const inspector = page.locator('sa-inspector-panel');
    await expect(inspector).toContainText('log-order-svc');
    await expect(inspector).toContainText('Play Framework');
    await expect(inspector).toContainText('Exposed endpoints');
    await expect(inspector).toContainText('/orders');

    // Evidence is the point: an edge must say which file and line produced it.
    await page.locator('sa-inspector-panel .relation').first().click();
    await expect(inspector).toContainText('Evidence');
    await expect(inspector.locator('.evidence-head .mono').first()).toContainText(/:\d+$/);

    // ---------------------------------------------------------------- filters (FR-5.5)
    await page.getByRole('button', { name: 'Filters' }).click();
    await page.locator('.chip', { hasText: 'HIGH' }).click();
    await page.waitForTimeout(1_200);
    const highOnly = await edges.count();
    expect(highOnly).toBeLessThan(35);
    await page.getByRole('button', { name: 'Reset filters' }).click();
    await page.waitForTimeout(1_200);
    await expect(edges).toHaveCount(35);

    // Datastores can be taken off the diagram without touching anything else (FR-5.5).
    const datastores = page.locator('g.node.datastore');
    const datastoreCount = await datastores.count();
    expect(datastoreCount).toBe(8);
    await page.locator('.filter-group', { hasText: 'Datastores' }).getByRole('button').click();
    await page.waitForTimeout(1_200);
    await expect(datastores).toHaveCount(0);
    await expect(nodes).toHaveCount(26 - datastoreCount);
    await page.getByRole('button', { name: 'Reset filters' }).click();
    await page.waitForTimeout(1_200);
    await expect(nodes).toHaveCount(26);

    // ---------------------------------------------------------------- export (FR-6.1)
    await page.getByRole('button', { name: 'Export' }).click();
    await expect(page.locator('.dialog')).toBeVisible();
    await page.locator('input[value="lucid"]').check();

    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.locator('.dialog .btn-primary').click(),
    ]);

    expect(download.suggestedFilename()).toMatch(/\.lucid$/);
    const lucidPath = join(tmpdir(), `service-atlas-${Date.now()}.lucid`);
    await download.saveAs(lucidPath);

    // UX-5: tell the user what to do with the file they just downloaded.
    await expect(page.locator('.import-hint')).toContainText('Standard Import');

    // ---------------------------------------------------------------- the .lucid archive
    const listing = execFileSync('unzip', ['-Z1', lucidPath], { encoding: 'utf8' }).trim();
    expect(listing.split('\n')).toEqual(['document.json']);

    const documentJson = execFileSync('unzip', ['-p', lucidPath, 'document.json'], {
      encoding: 'utf8',
      maxBuffer: 32 * 1024 * 1024,
    });
    const document = JSON.parse(documentJson);

    expect(document.version).toBe(1);
    expect(document.pages).toHaveLength(1);

    const page1 = document.pages[0];
    expect(page1.shapes.length).toBeGreaterThanOrEqual(26);
    expect(page1.lines).toHaveLength(35);

    // Shapes carry position, style and text; lines attach to real shapes at both ends.
    const shapeIds = new Set(page1.shapes.map((shape: { id: string }) => shape.id));
    for (const shape of page1.shapes) {
      expect(shape.boundingBox).toEqual(
        expect.objectContaining({
          x: expect.any(Number),
          y: expect.any(Number),
          w: expect.any(Number),
          h: expect.any(Number),
        }),
      );
      expect(shape.style.fill.type).toBe('color');
    }
    for (const line of page1.lines) {
      expect(line.endpoint1.type).toBe('shapeEndpoint');
      expect(shapeIds.has(line.endpoint1.shapeId)).toBe(true);
      expect(shapeIds.has(line.endpoint2.shapeId)).toBe(true);
      expect(line.endpoint2.style).toBe('arrow');
    }

    // The estate's services are actually in the document, not just the right number of boxes.
    const allText = page1.shapes.map((shape: { text?: string }) => shape.text ?? '').join('|');
    for (const service of [
      'log-order-svc',
      'log-quote-svc',
      'order-events',
      'api.stripe.com',
      'orders',
      'order-events-v2',
    ]) {
      expect(allText).toContain(service);
    }

    await testInfo.attach('document.json', { body: documentJson, contentType: 'application/json' });
  });

  test('a manual edit survives a re-scan (FR-4.4)', async ({ page, request }) => {
    await page.goto('/');
    await page.fill('#rootPath', FIXTURES);
    await page.getByRole('button', { name: 'Check' }).click();
    await expect(page.locator('.preview')).toContainText('repositories');
    await page.getByRole('button', { name: /Create workspace and scan/ }).click();

    await expect(page.locator('svg.canvas')).toBeVisible({ timeout: 90_000 });
    await page.waitForTimeout(2_000);

    await page.locator('g.node', { hasText: 'log-order-svc' }).first().click();
    await page.locator('.node-actions').getByRole('button', { name: 'Edit' }).click();
    await page.fill('#note', 'Owned by the orders team');
    await page.getByRole('button', { name: 'Save note' }).click();
    await expect(page.locator('sa-inspector-panel')).toContainText('Owned by the orders team');

    await page.getByRole('button', { name: 'Re-scan' }).click();

    // Wait on the scan itself rather than on a button label that changes twice while it runs.
    const [workspace] = await (await request.get('/api/v1/workspaces')).json();
    await expect
      .poll(
        async () => {
          const scans = await (await request.get(`/api/v1/workspaces/${workspace.id}/scans`)).json();
          return scans.length >= 2 && scans[0].status === 'COMPLETED';
        },
        { timeout: 90_000 },
      )
      .toBe(true);

    await page.reload();
    await expect(page.locator('svg.canvas')).toBeVisible({ timeout: 60_000 });
    await page.waitForTimeout(2_000);
    await page.locator('g.node', { hasText: 'log-order-svc' }).first().click();
    await expect(page.locator('sa-inspector-panel')).toContainText('Owned by the orders team');
  });
});
