package io.forgetdm.discovery;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveryFrontendBehaviorTest {

    @Test
    void zeroTableCompletedPayloadIsRenderedAsRejectedNotCompleted() throws Exception {
        String output = runNode("""
                const fs = require('fs');
                const vm = require('vm');
                const ts = require('./frontend/node_modules/typescript');
                const source = fs.readFileSync('frontend/src/features/pii-discovery/components.tsx', 'utf8');
                const compiled = ts.transpileModule(source, {
                  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX }
                }).outputText;
                const exportsObject = {};
                const context = {
                  exports: exportsObject,
                  module: { exports: exportsObject },
                  require: name => name.endsWith('./utils')
                    ? { discoveryJobLive: status => ['PENDING', 'RUNNING'].includes(status) }
                    : {},
                  console
                };
                vm.runInNewContext(compiled, context, { filename: 'frontend/src/features/pii-discovery/components.tsx' });
                const present = context.module.exports.liveScanPresentation;
                const invalid = present({ status: 'COMPLETED', totalTables: 0, percent: 100, tables: [] });
                if (!invalid.invalidZeroTableCompletion || invalid.displayStatus !== 'FAILED' || invalid.percent !== 0) {
                  throw new Error('zero-table completed payload was not converted to a rejected presentation');
                }
                const valid = present({ status: 'COMPLETED', totalTables: 1, percent: 100, tables: [{ tableName: 'customers' }] });
                if (valid.invalidZeroTableCompletion || valid.displayStatus !== 'COMPLETED' || valid.percent !== 100) {
                  throw new Error('valid completion presentation regressed');
                }
                console.log('DISC006_FRONTEND_ZERO_TABLE_GUARD_PASS');
                """);

        assertTrue(output.contains("DISC006_FRONTEND_ZERO_TABLE_GUARD_PASS"), output);
    }

    private static String runNode(String script) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("node", "-e", script)
                .directory(Path.of("").toAbsolutePath().toFile())
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(60, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new AssertionError("Node DISC-006 behavior probe timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        return output;
    }
}
