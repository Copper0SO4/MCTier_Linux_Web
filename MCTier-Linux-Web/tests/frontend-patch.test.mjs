import test from 'node:test';
import assert from 'node:assert/strict';
import {applyUnifiedPatch} from '../scripts/lib/frontend-patch.mjs';
test('upstream patch context is unique and cannot silently choose the nearest duplicate', () => {
  const patch='@@ -1,1 +1,1 @@\n-x\n+y\n';
  assert.equal(applyUnifiedPatch('x\n',patch,'fixture'), 'y\n');
  assert.throws(()=>applyUnifiedPatch('x\nx\n',patch,'fixture'), /Ambiguous/);
  assert.throws(()=>applyUnifiedPatch('other\n',patch,'fixture'), /no longer applies/);
  assert.equal(applyUnifiedPatch('', '@@ -0,0 +1,1 @@\n+new\n','fixture'),'new');
  assert.throws(()=>applyUnifiedPatch('existing','@@ -0,0 +1,1 @@\n+new\n','fixture'), /without context/);
});
