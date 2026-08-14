import fs from 'fs';
import path from 'path';

const root = 'src';
const files = [];
function walk(dir) {
  for (const f of fs.readdirSync(dir)) {
    const p = path.join(dir, f);
    if (fs.statSync(p).isDirectory()) walk(p);
    else if (p.endsWith('.tsx') && !p.endsWith('.test.tsx')) files.push(p);
  }
}
walk(root);

const results = [];
for (const f of files) {
  const src = fs.readFileSync(f, 'utf8');
  const hasI18n = /useI18n/.test(src);
  // 匹配 JSX 文本节点或引号属性中的英文（含空格，至少 5 字符，含小写字母）
  const regex = /(?:>|["'])([A-Za-z][A-Za-z0-9 ,.?!'&/()-]{5,})(?:<|["'])/g;
  const found = [];
  let m;
  while ((m = regex.exec(src)) !== null) {
    const t = m[1];
    if (!/[a-z]/.test(t)) continue; // 全大写缩写（如 URL、API）跳过
    if (t.includes('{') || t.includes('}')) continue;
    found.push(t);
  }
  // 去重
  const unique = [...new Set(found)];
  results.push({ file: f, hasI18n, texts: unique });
}

results.sort((a, b) => b.texts.length - a.texts.length);
for (const r of results) {
  console.log(`\n${r.hasI18n ? '[有i18n]' : '[无i18n]'} ${r.texts.length} ${r.file}`);
  for (const t of r.texts.slice(0, 25)) console.log('   - ' + t);
}
