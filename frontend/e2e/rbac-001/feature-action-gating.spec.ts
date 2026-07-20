import { expect, test, type Page, type Route } from '@playwright/test';

test.describe('RBAC-001 feature action gating', () => {
  test('direct protected URLs fail closed when the authenticated account lacks route permission', async ({ page }) => {
    const writes = await installApiContract(page, []);
    await page.goto('/synthetic');
    await expect(page.getByRole('heading', { name: 'Access denied' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Synthetic Data Generation' })).toHaveCount(0);

    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Access denied' })).toBeVisible();
    expect(writes).toEqual([]);
  });

  test('Auto Provision does not admit a user whose partial permission set cannot load a workspace slice', async ({ page }) => {
    await installApiContract(page, ['provision.read']);
    await page.goto('/auto-provision');
    await expect(page.getByRole('heading', { name: 'Access denied' })).toBeVisible();
  });

  test('read-only synthetic access preserves inspection and disables generation mutations', async ({ page }) => {
    const writes = await installApiContract(page, ['synthetic.read']);
    await page.goto('/synthetic');
    await expect(page.getByRole('heading', { name: 'Synthetic Data Generation' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Generate', exact: true })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Save job' })).toBeDisabled();
    expect(writes).toEqual([]);
  });

  test('read-only DataScope access keeps the inventory but removes blueprint mutation entry points', async ({ page }) => {
    const writes = await installApiContract(page, ['datascope.read']);
    await page.goto('/datascope');
    await expect(page.getByRole('heading', { name: 'DataScope', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'New blueprint' })).toHaveCount(0);
    expect(writes).toEqual([]);
  });

  test('read-only business-entity access keeps the workspace and removes creation', async ({ page }) => {
    const writes = await installApiContract(page, ['datascope.read']);
    await page.goto('/business-entities');
    await expect(page.getByRole('heading', { name: 'Business Entities', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: /^New$/ })).toHaveCount(0);
    expect(writes).toEqual([]);
  });

  test('mapping reader can validate and preview but cannot persist, restore, upload, or delete', async ({ page }) => {
    const writes = await installApiContract(page, ['mapping.read', 'datasource.read', 'policy.read']);
    await page.goto('/mapping-designer');
    await expect(page.getByRole('heading', { name: 'Mapping Designer' })).toBeVisible();
    await expect(page.getByText('Read-only', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Save version' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Validate', exact: true })).toBeVisible();
    expect(writes).toEqual([]);
  });

  test('Auto Provision activates only the mapping slice available to an auditor', async ({ page }) => {
    const writes = await installApiContract(page, ['provision.read', 'mapping.read']);
    await page.goto('/auto-provision');
    await expect(page.getByRole('heading', { name: 'Auto Provision' })).toBeVisible();
    await expect(page.getByRole('tab', { name: 'Story to Data' })).toHaveCount(0);
    await expect(page.getByRole('tab', { name: 'Provision from mapping' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Run permission required' })).toBeDisabled();
    expect(writes).toEqual([]);
  });

  test('self-service reader cannot see approval or catalog administration controls', async ({ page }) => {
    const writes = await installApiContract(page, ['provision.read']);
    await page.goto('/self-service');
    await expect(page.getByRole('heading', { name: 'Self-Service', exact: true })).toBeVisible();
    await expect(page.getByRole('tab', { name: /Approvals/ })).toHaveCount(0);
    await expect(page.getByRole('tab', { name: /Manage catalog/ })).toHaveCount(0);
    expect(writes).toEqual([]);
  });

  test('unstructured reader keeps preview/history but cannot run, save, cancel, or delete', async ({ page }) => {
    const writes = await installApiContract(page, ['unstructured.read']);
    await page.goto('/unstructured-masking');
    await expect(page.getByRole('heading', { name: 'Unstructured Masking' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Document or data file' })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Start governed masking' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Save profile/ })).toHaveCount(0);
    expect(writes).toEqual([]);
  });

  test('mainframe reader can inspect registries while every server action is withheld', async ({ page }) => {
    const writes = await installApiContract(page, ['mainframe.read', 'policy.read', 'synthetic.read']);
    await page.goto('/copybook-studio');
    await expect(page.getByRole('heading', { name: 'Copybook Studio' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Parse copybook' })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Decode record' })).toBeDisabled();

    await page.goto('/mainframe-files');
    await expect(page.getByRole('heading', { name: 'Mainframe Files' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Add connection' })).toBeDisabled();
    await page.getByRole('tab', { name: 'Copybook maps' }).click();
    await expect(page.getByRole('button', { name: 'Save copybook' })).toBeDisabled();

    await page.goto('/mf-file-generator');
    await expect(page.getByRole('heading', { name: 'MF File Generator' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Generate file' })).toBeDisabled();
    expect(writes).toEqual([]);
  });

  test('Forge Data Store reader receives grounding evidence without stewardship actions', async ({ page }) => {
    const writes = await installApiContract(page, ['assistant.use']);
    await page.goto('/intelligence-store');
    await expect(page.getByRole('heading', { name: 'Forge Data Store' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Add knowledge' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Synchronize' })).toHaveCount(0);
    expect(writes).toEqual([]);
  });

  test('discovery reader can inspect findings and patterns without scan or stewardship actions', async ({ page }) => {
    const writes = await installApiContract(page, ['discovery.read', 'datasource.read', 'policy.read'], 'rbac-catalog');
    await page.goto('/pii-discovery');
    await expect(page.getByRole('heading', { name: 'PII Discovery' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Start scan' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Create policy' })).toHaveCount(0);

    await page.goto('/pii-discovery/patterns');
    await expect(page.getByRole('heading', { name: 'Detection Patterns' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Add pattern' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Delete.*RBAC_ACCOUNT_ID/i })).toHaveCount(0);
    expect(writes).toEqual([]);
  });

  test('data-source reader can browse inventory but cannot test or edit connections', async ({ page }) => {
    const writes = await installApiContract(page, ['datasource.read'], 'rbac-catalog');
    await page.goto('/datasources');
    await expect(page.getByRole('heading', { name: 'Data Sources' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Add connection' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Test RBAC Acceptance Source' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Browse schemas for RBAC Acceptance Source' })).toBeVisible();
    expect(writes).toEqual([]);
  });

  test('policy reader sees rules as read-only and cannot create or delete policy content', async ({ page }) => {
    const writes = await installApiContract(page, ['policy.read', 'datasource.read', 'discovery.read', 'synthetic.read'], 'rbac-catalog');
    await page.goto('/masking-policies');
    await expect(page.getByRole('heading', { name: 'Masking Policies' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'New policy' })).toHaveCount(0);
    await page.getByRole('button', { name: 'View', exact: true }).click();
    await expect(page.getByLabel('Param 1')).toHaveAttribute('readonly', '');
    await expect(page.getByLabel('Param 2')).toHaveAttribute('readonly', '');
    await expect(page.getByText('Delete policy', { exact: true })).toHaveCount(0);
    expect(writes).toEqual([]);
  });

  test('masking-script reader can preview a saved script without stewardship actions', async ({ page }) => {
    const writes = await installApiContract(page, ['policy.read'], 'rbac-catalog');
    await page.goto('/masking-scripts');
    await expect(page.getByRole('heading', { name: 'Masking Scripts' })).toBeVisible();
    await page.getByRole('button', { name: 'Browse existing' }).click();
    await page.getByRole('button', { name: 'View rbac_acceptance_script' }).click();
    await expect(page.getByLabel('Name')).toHaveAttribute('readonly', '');
    await expect(page.getByRole('button', { name: 'Save script' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Test saved' })).toBeVisible();
    expect(writes).toEqual([]);
  });

  test('virtualization reader cannot capture, provision, cancel, or delete resources', async ({ page }) => {
    const writes = await installApiContract(page, ['virtualization.read', 'datasource.read'], 'rbac-catalog');
    await page.goto('/virtualization');
    await expect(page.getByRole('heading', { name: 'Data Virtualization' })).toBeVisible();
    await expect(page.getByRole('button', { name: /Capture snapshot/i })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Add environment/i })).toHaveCount(0);
    await expect(page.getByLabel(/Delete environment/i)).toHaveCount(0);
    expect(writes).toEqual([]);
  });

  test('validation reader sees failed evidence without run, diagnose, or apply-fix controls', async ({ page }) => {
    const writes = await installApiContract(page, ['validation.read', 'datasource.read', 'policy.read'], 'rbac-catalog');
    await page.goto('/validation');
    await expect(page.getByRole('heading', { name: 'Masking Validation' })).toBeVisible();
    await expect(page.getByText('Run validation', { exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Diagnose with AI' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Apply fix' })).toHaveCount(0);
    expect(writes).toEqual([]);
  });

  test('authorized mapping manager and runner receive their exact controls', async ({ page }) => {
    await installApiContract(page, ['mapping.read', 'mapping.manage', 'mapping.run', 'datasource.read', 'policy.read', 'provision.read']);
    await page.goto('/mapping-designer');
    await expect(page.getByRole('button', { name: 'Save version' })).toBeVisible();
    await expect(page.getByText('Read-only', { exact: true })).toHaveCount(0);

    await page.goto('/auto-provision');
    await expect(page.getByRole('button', { name: 'Launch governed run' })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Run permission required' })).toHaveCount(0);
  });

  test('built-in Auditor can inspect Automation and Audit but cannot administer either area', async ({ page }) => {
    const writes = await installApiContract(page, AUDITOR_PERMISSIONS, 'empty', ['AUDITOR']);
    await page.goto('/automation');
    await expect(page.getByRole('heading', { name: 'Automation', exact: true })).toBeVisible();
    await expect(page.getByRole('tab', { name: 'Integrations' })).toBeVisible();
    await page.getByRole('tab', { name: 'Integrations' }).click();
    await expect(page.getByText('Read only', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Add endpoint' })).toHaveCount(0);
    await expect(page.getByRole('tab', { name: 'Delivery activity' })).toBeVisible();

    await page.goto('/audit');
    await expect(page.getByRole('heading', { name: 'Audit Trail' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Export CSV' })).toBeVisible();

    await page.goto('/access-control');
    await expect(page.getByRole('heading', { name: 'Access denied' })).toBeVisible();
    expect(writes).toEqual([]);
  });

  test('synthetic cancel permission exposes partition cancel without granting retry', async ({ page }) => {
    const writes = await installApiContract(page, ['synthetic.read', 'synthetic.cancel'], 'synthetic-partitions');
    await page.goto('/synthetic');
    await page.getByRole('tab', { name: 'Run history' }).click();
    await page.getByRole('button', { name: 'Open full match centre' }).click();
    await page.locator('details.syn-partition-table-card summary').click();
    await expect(page.getByLabel('Cancel accounts 1')).toBeVisible();
    await expect(page.getByLabel('Retry accounts 2')).toHaveCount(0);
    expect(writes).toEqual([]);
  });

  test('synthetic run permission exposes partition retry without granting cancel', async ({ page }) => {
    const writes = await installApiContract(page, ['synthetic.read', 'synthetic.run'], 'synthetic-partitions');
    await page.goto('/synthetic');
    await page.getByRole('tab', { name: 'Run history' }).click();
    await page.getByRole('button', { name: 'Open full match centre' }).click();
    await page.locator('details.syn-partition-table-card summary').click();
    await expect(page.getByLabel('Cancel accounts 1')).toHaveCount(0);
    await expect(page.getByLabel('Retry accounts 2')).toBeVisible();
    expect(writes).toEqual([]);
  });
});

const AUDITOR_PERMISSIONS = [
  'dashboard.read', 'datasource.read', 'discovery.read', 'policy.read', 'datascope.read',
  'provision.read', 'synthetic.read', 'mapping.read', 'unstructured.read', 'ri.read',
  'validation.read', 'reservation.read', 'virtualization.read', 'mainframe.read', 'audit.read',
  'integration.read'
];

type FixtureMode = 'empty' | 'rbac-catalog' | 'synthetic-partitions';

async function installApiContract(
  page: Page,
  permissions: string[],
  fixtureMode: FixtureMode = 'empty',
  roles: string[] = ['ACCEPTANCE_READER']
) {
  const writes: string[] = [];
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;

    if (path === '/api/auth/me') {
      await json(route, 200, {
        authenticated: true,
        user: {
          userId: 9001,
          username: 'rbac-browser-user',
          displayName: 'RBAC Browser User',
          roles,
          permissions,
          groups: []
        }
      });
      return;
    }
    if (path === '/api/auth/logout') {
      await json(route, 200, { authenticated: false });
      return;
    }

    if (request.method() !== 'GET') {
      writes.push(`${request.method()} ${path}`);
      await json(route, 403, { error: 'Acceptance contract blocked an unexpected write' });
      return;
    }

    if (fixtureMode === 'synthetic-partitions' && path === '/api/synthetic/jobs') {
      await json(route, 200, [{
        id: 'rbac-partition-run',
        status: 'RUNNING',
        dataset: 'RBAC partition contract',
        receiver: 'DB',
        executionMode: 'LOCAL_PARTITIONED',
        tableCount: 1,
        plannedRows: 200,
        percent: 50,
        stage: 'Partition load',
        message: 'One partition is running and one can be retried',
        currentTable: 'accounts',
        rowsDone: 100,
        rowsTotal: 200,
        tableRowsDone: 100,
        tableRowsTotal: 200,
        partitions: [
          { id: 'partition-running', number: 1, table: 'accounts', plannedRows: 100, rowsCompleted: 50, status: 'RUNNING' },
          { id: 'partition-failed', number: 2, table: 'accounts', plannedRows: 100, rowsCompleted: 50, status: 'FAILED', error: 'Acceptance fixture' }
        ]
      }]);
      return;
    }

    if (fixtureMode === 'rbac-catalog') {
      if (path === '/api/datasources') {
        await json(route, 200, [{ id: 1, version: 0, name: 'RBAC Acceptance Source', kind: 'POSTGRES', role: 'BOTH', jdbcUrl: 'jdbc:postgresql://localhost/acceptance' }]);
        return;
      }
      if (path === '/api/discovery/patterns') {
        await json(route, 200, [{ id: 1, piiType: 'RBAC_ACCOUNT_ID', kind: 'NAME', regex: 'account_id', suggestedFunction: 'HASH_SHA256', visibility: 'GLOBAL' }]);
        return;
      }
      if (path === '/api/discovery/patterns/my-groups') {
        await json(route, 200, []);
        return;
      }
      if (path === '/api/policies') {
        await json(route, 200, [{ id: 1, name: 'RBAC Acceptance Policy', description: 'Acceptance fixture', dataSourceId: 1, schemaName: 'public', status: 'ACTIVE' }]);
        return;
      }
      if (path === '/api/policies/1/rules') {
        await json(route, 200, [{ id: 1, policyId: 1, schemaName: 'public', tableName: 'customers', columnName: 'account_id', function: 'HASH_SHA256', param1: 'salt', param2: '16' }]);
        return;
      }
      if (path === '/api/policies/functions') {
        await json(route, 200, ['HASH_SHA256']);
        return;
      }
      if (path === '/api/policies/scripts') {
        await json(route, 200, [{ id: 1, name: 'rbac_acceptance_script', description: 'Acceptance fixture', luaSource: 'return value', visibility: 'GLOBAL' }]);
        return;
      }
      if (path === '/api/policies/lookup-references' || path === '/api/synthetic/value-lists') {
        await json(route, 200, []);
        return;
      }
      if (path === '/api/validation/reports') {
        await json(route, 200, [{ id: 1, dataSourceId: 1, policyId: 1, result: 'FAIL', findingsJson: '[{"severity":"FAIL","check":"LEAK","table":"customers","column":"account_id","detail":"Acceptance leak"}]' }]);
        return;
      }
      if (path === '/api/virtualization/pool' || path === '/api/virtualization/zfs' || path === '/api/virtualization/docker') {
        await json(route, 200, {});
        return;
      }
    }

    if (path === '/api/self-service/v2/metrics') {
      await json(route, 200, { visibleRequests: 0, statusCounts: {}, averageFulfillmentSeconds: 0, scope: 'acceptance' });
      return;
    }
    if (path === '/api/unstructured/capabilities') {
      await json(route, 200, { nativePreserving: [], safeTextRebuild: [], blockedWithoutExtractor: [], guarantee: 'Acceptance fixture' });
      return;
    }
    if (path === '/api/agent/data-store/status') {
      await json(route, 200, { documents: 0, types: [], stale: false, warnings: [], privacyBoundary: 'Acceptance fixture', latestSync: null, lastSyncedAt: null });
      return;
    }
    if (path === '/api/ai/status') {
      await json(route, 200, { enabled: true, provider: 'PRIVATE' });
      return;
    }

    await json(route, 200, []);
  });
  return writes;
}

async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({
    status,
    contentType: 'application/json',
    headers: { 'cache-control': 'no-store' },
    body: JSON.stringify(body)
  });
}
