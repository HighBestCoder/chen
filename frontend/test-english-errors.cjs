const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const load = locale => {
  const source = fs.readFileSync(path.join(__dirname,'src/i18n/lang',locale+'.js'),'utf8')
    .replace(/^import el[^\n]*\n/, 'const el = {};\n').replace('export default', 'return');
  return new Function(source)();
};
const en = load('en-US');
const keys = {
  instance:['empty','no_account','instance_org_required','instance_login_required','instance_denied','instance_failed','instance_popup'],
  message:['command_required','command_sensitive'],
  msg:['copy_not_allowed','paste_not_allowed']
};
for(const locale of ['en-US','zh-CN','zh-Hant','ja-JP']) {
  const messages=load(locale);
  for(const [group,fields] of Object.entries(keys))for(const field of fields) {
    assert.equal(messages[group][field],en[group][field],locale+': '+group+'.'+field);
    assert(!/\p{Script=Han}/u.test(messages[group][field]));
  }
}
assert.equal(load('zh-CN').button.run,'运行');
console.log('PASS frontend errors are English across four locales; normal UI labels retain localization');
