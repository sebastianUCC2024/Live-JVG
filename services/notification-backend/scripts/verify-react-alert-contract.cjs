// Offline: validates real MVC output with the unchanged React TypeScript guards.
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const vm = require('node:vm')
const nodeModules = process.env.JVG_FRONTEND_NODE_MODULES
if (!nodeModules || !path.isAbsolute(nodeModules)) throw new Error('Explicit existing frontend node_modules required; no installation is performed')
const ts = require(path.join(nodeModules, 'typescript'))
const frontend = path.resolve(__dirname, '../../dashboard-frontend/src/types')
function load(name, dependencies) {
  const source = fs.readFileSync(path.join(frontend, name + '.ts'), 'utf8')
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const module = { exports: {} }
  const context = vm.createContext({ exports: module.exports, module, require: key => {
    if (!(key in dependencies)) throw new Error('Unexpected TypeScript dependency: ' + key)
    return dependencies[key]
  } })
  new vm.Script(compiled, { filename: name + '.ts' }).runInContext(context)
  return module.exports
}
const match = load('match', {})
const guards = load('alert', { './match': match })
const result = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../target/live-alert-rest-fixture.json'), 'utf8'))
assert.equal(guards.isAlertPage(result, 'match'), true)
assert.equal(guards.isLiveAlert(result.items[0], 'match'), true)
assert.equal(result.items[0].home_score, 1)
assert.equal(result.items[0].var_status, 'IN_PROGRESS')
assert.equal(result.items[0].source_event_id, 'goal')
assert.equal(guards.isAlertPage(result, 'different-match'), false)
console.log('PASS: actual Notification REST page satisfies unchanged React guards and snapshot values; no services contacted')
