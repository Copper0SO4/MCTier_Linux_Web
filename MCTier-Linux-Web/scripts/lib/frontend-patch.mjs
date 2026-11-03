function patchRow(row) {
  return row.endsWith('\r') ? row.slice(0, -1) : row;
}
export function applyUnifiedPatch(source, patch, label) {
  const lines = source.split('\n');
  const records = patch.split('\n');
  let at = 0;
  while (at < records.length && !patchRow(records[at]).startsWith('@@ ')) at++;
  while (at < records.length) {
    const header = patchRow(records[at++]);
    const match = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/.exec(header);
    if (!match) throw new Error(`Invalid patch header for ${label}: ${header}`);
    const oldCount = match[2] === undefined ? 1 : Number(match[2]);
    const newCount = match[4] === undefined ? 1 : Number(match[4]);
    if (!Number.isInteger(oldCount) || !Number.isInteger(newCount) || oldCount < 0 || newCount < 0) {
      throw new Error(`Invalid patch hunk length for ${label}: ${header}`);
    }
    const before = [], after = [];
    while (at < records.length && !patchRow(records[at]).startsWith('@@ ')) {
      const row = patchRow(records[at++]);
      if (row.startsWith('\\')) continue;
      if (row.startsWith(' ')) { before.push(row.slice(1)); after.push(row.slice(1)); }
      else if (row.startsWith('-')) before.push(row.slice(1));
      else if (row.startsWith('+')) after.push(row.slice(1));
      else if (row !== '') throw new Error(`Invalid patch line for ${label}`);
    }
    if (before.length !== oldCount || after.length !== newCount) {
      throw new Error(`Patch hunk length does not match its header for ${label}`);
    }
    if (before.length === 0) {
      if (source !== '' || Number(match[1]) !== 0) throw new Error(`Insertion without context refused for ${label}`);
      lines.splice(0, lines.length, ...after);
      continue;
    }
    const candidates = [];
    for (let i = 0; i <= lines.length - before.length; i++) {
      if (before.every((line, j) => lines[i + j] === line)) candidates.push(i);
    }
    if (candidates.length > 1) throw new Error(`Ambiguous Linux patch context for ${label}; review upstream before building.`);
    const pos = candidates[0];
    if (pos === undefined) throw new Error(`Linux patch no longer applies to upstream ${label}; resolve this file's patch before building.`);
    lines.splice(pos, before.length, ...after);
  }
  return lines.join('\n');
}
