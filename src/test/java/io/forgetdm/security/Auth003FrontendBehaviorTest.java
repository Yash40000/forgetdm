package io.forgetdm.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Auth003FrontendBehaviorTest {

    @Test
    void fiveConcurrent401ResponsesStartExactlyOneSameOriginLoginNavigation() throws Exception {
        String output = runNode("""
                const fs = require('fs');
                const vm = require('vm');
                const ts = require('./frontend/node_modules/typescript');
                const source = fs.readFileSync('frontend/src/lib/api.ts', 'utf8');
                const compiled = ts.transpileModule(source, {
                  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
                }).outputText;

                function loadApi(pathname, search) {
                  const assignments = [];
                  const exportsObject = {};
                  const context = {
                    exports: exportsObject,
                    module: { exports: exportsObject },
                    FormData: class FormData {},
                    fetch: async () => ({
                      ok: false,
                      status: 401,
                      statusText: 'Unauthorized',
                      text: async () => JSON.stringify({ error: 'Login required' })
                    }),
                    window: { location: { pathname, search, assign: value => assignments.push(value) } },
                    encodeURIComponent,
                    JSON
                  };
                  vm.runInNewContext(compiled, context, { filename: 'frontend/src/lib/api.ts' });
                  return { api: context.module.exports, assignments, context };
                }

                (async () => {
                  const concurrent = loadApi('/datascope', '?view=map&table=customer');
                  await Promise.allSettled(Array.from({ length: 5 }, () => concurrent.api.apiFetch('/api/policies')));
                  if (concurrent.assignments.length !== 1) {
                    throw new Error(`expected one redirect, got ${concurrent.assignments.length}`);
                  }
                  const expected = '/login?next=' + encodeURIComponent('/datascope?view=map&table=customer');
                  if (concurrent.assignments[0] !== expected) {
                    throw new Error(`wrong return path: ${concurrent.assignments[0]}`);
                  }

                  const authRequest = loadApi('/datascope', '');
                  await Promise.allSettled([authRequest.api.apiFetch('/api/auth/login')]);
                  if (authRequest.assignments.length !== 0) throw new Error('auth endpoint caused a redirect');

                  const loginPage = loadApi('/login', '?next=%2Fdatascope');
                  await Promise.allSettled([loginPage.api.apiFetch('/api/policies')]);
                  if (loginPage.assignments.length !== 0) throw new Error('401 on login page caused a redirect loop');
                  console.log('AUTH003_API_FETCH_PASS');
                })().catch(error => { console.error(error.stack || error); process.exitCode = 1; });
                """);

        assertTrue(output.contains("AUTH003_API_FETCH_PASS"), output);
    }

    @Test
    void returnPathValidatorAcceptsLocalQueryAndRejectsExternalVectors() throws Exception {
        String output = runNode("""
                const fs = require('fs');
                const vm = require('vm');
                const ts = require('./frontend/node_modules/typescript');
                const page = fs.readFileSync('frontend/src/app/login/page.tsx', 'utf8');
                const start = page.indexOf('function safeNextPath');
                if (start < 0) throw new Error('safeNextPath not found');
                let depth = 0;
                let bodyStarted = false;
                let end = -1;
                for (let index = start; index < page.length; index++) {
                  if (page[index] === '{') { depth++; bodyStarted = true; }
                  if (page[index] === '}') {
                    depth--;
                    if (bodyStarted && depth === 0) { end = index + 1; break; }
                  }
                }
                if (end < 0) throw new Error('safeNextPath body not found');
                const functionSource = page.slice(start, end);
                const compiled = ts.transpileModule(functionSource + '\\nmodule.exports.safeNextPath = safeNextPath;', {
                  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
                }).outputText;
                const exportsObject = {};
                const context = {
                  exports: exportsObject,
                  module: { exports: exportsObject },
                  window: { location: { origin: 'https://forgetdm.test' } },
                  URL
                };
                vm.runInNewContext(compiled, context, { filename: 'frontend/src/app/login/page.tsx#safeNextPath' });
                const safeNextPath = context.module.exports.safeNextPath;
                const local = '/datascope?view=map&table=customer';
                if (safeNextPath(local) !== local) throw new Error('same-origin path and query were not preserved');

                const queryValue = encoded => new URLSearchParams(encoded).get('next');
                const unsafe = [
                  null,
                  'https://evil.test/steal',
                  '//evil.test/steal',
                  queryValue('next=https%3A%2F%2Fevil.test%2Fsteal'),
                  queryValue('next=%2F%2Fevil.test%2Fsteal'),
                  queryValue('next=%2F%5Cevil.test%2Fsteal'),
                  'javascript:alert(1)'
                ];
                for (const candidate of unsafe) {
                  const actual = safeNextPath(candidate);
                  if (actual !== '/datascope') throw new Error(`unsafe next accepted: ${candidate} -> ${actual}`);
                }
                if (!page.includes('router.replace(nextPath);')) throw new Error('successful login does not replace with validated next path');
                console.log('AUTH003_SAFE_NEXT_PASS');
                """);

        assertTrue(output.contains("AUTH003_SAFE_NEXT_PASS"), output);
    }

    @Test
    void unsavedGuardInstallsPromptAndIsWiredToBothDraftWorkspaces() throws Exception {
        String output = runNode("""
                const fs = require('fs');
                const vm = require('vm');
                const ts = require('./frontend/node_modules/typescript');
                const source = fs.readFileSync('frontend/src/lib/use-unsaved-guard.ts', 'utf8');
                const compiled = ts.transpileModule(source, {
                  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
                }).outputText;
                let installed = null;
                let removed = null;
                let cleanup = null;
                const exportsObject = {};
                const context = {
                  exports: exportsObject,
                  module: { exports: exportsObject },
                  require: name => {
                    if (name !== 'react') throw new Error(`unexpected import ${name}`);
                    return { useEffect: callback => { cleanup = callback(); } };
                  },
                  window: {
                    addEventListener: (name, handler) => { installed = { name, handler }; },
                    removeEventListener: (name, handler) => { removed = { name, handler }; }
                  }
                };
                vm.runInNewContext(compiled, context, { filename: 'frontend/src/lib/use-unsaved-guard.ts' });
                context.module.exports.useUnsavedGuard(true);
                if (!installed || installed.name !== 'beforeunload') throw new Error('dirty draft did not install beforeunload');
                let prevented = 0;
                const event = { preventDefault: () => prevented++, returnValue: null };
                installed.handler(event);
                if (prevented !== 1 || event.returnValue !== '') throw new Error('beforeunload did not request a native warning');
                if (typeof cleanup !== 'function') throw new Error('hook did not provide listener cleanup');
                cleanup();
                if (!removed || removed.handler !== installed.handler) throw new Error('hook did not remove its listener');

                const dataScope = fs.readFileSync('frontend/src/features/datascope/data-scope-page.tsx', 'utf8');
                const synthetic = fs.readFileSync('frontend/src/features/synthetic/components/synthetic-designer.tsx', 'utf8');
                const tableMap = fs.readFileSync('frontend/src/features/datascope/components/table-map-workspace.tsx', 'utf8');
                if (!dataScope.includes('useUnsavedGuard(workspaceDirty);')) throw new Error('DataScope dirty state is not guarded');
                if (!synthetic.includes('useUnsavedGuard(fingerprint !== initialFingerprint.current);')) throw new Error('Synthetic dirty state is not guarded');
                if ((tableMap.match(/if \\(dirty\\) return;/g) || []).length < 2) throw new Error('DataScope background refetch lacks dirty-state guards');
                console.log('AUTH003_UNSAVED_GUARD_PASS');
                """);

        assertTrue(output.contains("AUTH003_UNSAVED_GUARD_PASS"), output);
    }

    private static String runNode(String script) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("node", "-e", script)
                .directory(Path.of("").toAbsolutePath().toFile())
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(60, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new AssertionError("Node AUTH-003 behavior probe timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        return output;
    }
}
