// Process sandbox integration fixture.
//
// Emscripten evaluates every --js-library file in Node while linking, so the top-level code
// below runs with exactly the access the sandbox grants to em++. It probes the escapes the
// sandbox must block and then fails the link with one summary line that
// IntegrationTest.sandboxConfinesWebLinkStep asserts on:
//
//   SANDBOX_PROBE fs_write_outside=EACCES results=EACCES tmp=EACCES sdk_write=EACCES
//                 net_local=EAFNOSUPPORT net_metadata=EAFNOSUPPORT decoy=absent home=job detached=spawned
//
// Without the sandbox the same line reads ok/present/host/connected.
(function () {
  var results = [];

  function builtin(name) {
    if (typeof process.getBuiltinModule === 'function') {
      return process.getBuiltinModule(name);
    }
    if (typeof require === 'function') {
      return require(name);
    }
    throw new Error('no module loader');
  }

  function record(name, fn) {
    try {
      results.push(name + '=' + fn());
    } catch (e) {
      results.push(name + '=' + (e && e.code ? e.code : 'error:' + (e && e.message)));
    }
  }

  var fs, cp;
  try {
    fs = builtin('fs');
    cp = builtin('child_process');
  } catch (e) {
    throw new Error('SANDBOX_PROBE modules=unavailable ' + e.message);
  }

  var cwd = process.cwd();

  record('fs_write_outside', function () {
    fs.writeFileSync('/opt/extender_sandbox_probe', 'x');
    fs.unlinkSync('/opt/extender_sandbox_probe');
    return 'ok';
  });
  record('results', function () {
    return fs.readdirSync('/var/extender/results').length >= 0 ? 'ok' : 'ok';
  });
  record('tmp', function () {
    return fs.readdirSync('/tmp').length >= 0 ? 'ok' : 'ok';
  });
  record('sdk_write', function () {
    var probe = process.env.DYNAMO_HOME + '/extender_sandbox_probe';
    fs.writeFileSync(probe, 'x');
    fs.unlinkSync(probe);
    return 'ok';
  });

  // Network is asynchronous in Node; a child process reports the result synchronously.
  function connectResult(host, port) {
    var script =
      "var s=require('net').connect(" + port + ",'" + host + "');" +
      "var done=function(m){console.log(m);process.exit(0);};" +
      "s.on('error',function(e){done(e.code||'error');});" +
      "s.on('connect',function(){done('connected');});" +
      "setTimeout(function(){done('timeout');},3000);";
    var r = cp.spawnSync(process.execPath, ['-e', script], { encoding: 'utf8', timeout: 10000 });
    if (r.error) {
      return r.error.code || 'spawn-error';
    }
    return String(r.stdout || '').trim() || ('exit:' + r.status);
  }
  // the builder's own HTTP port: reachable from any unsandboxed process in the container
  record('net_local', function () { return connectResult('127.0.0.1', 9000); });
  // cloud metadata service
  record('net_metadata', function () { return connectResult('169.254.169.254', 80); });

  record('decoy', function () {
    return process.env.EXTENDER_TEST_DECOY_TOKEN === undefined ? 'absent' : 'present';
  });
  record('home', function () {
    return (process.env.HOME || '').indexOf(cwd) === 0 ? 'job' : 'host';
  });

  // A daemonised process must not outlive the link command (the test checks the container).
  record('detached', function () {
    var child = cp.spawn('sleep', ['600'], { detached: true, stdio: 'ignore' });
    child.unref();
    return 'spawned';
  });

  throw new Error('SANDBOX_PROBE ' + results.join(' '));
})();
