import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { root, readJson, log, difference } from './lib.mjs';

const stages = [
  { stage: 'sources', unit: 'S0.01', artifact: 'config/source-lock.json' },
  { stage: 'navigation', unit: 'S0.02', artifact: 'config/baseline/navigation.json' },
  { stage: 'entries', unit: 'S0.03', artifact: 'config/baseline/entries.json' },
  { stage: 'workbench', unit: 'S0.04', artifact: 'config/baseline/workbench.json' },
  { stage: 'domains', unit: 'S0.05', artifact: 'config/baseline/domains.json' },
  { stage: 'contributions', unit: 'S0.06', artifact: 'config/baseline/contributions.json' },
  { stage: 'content', unit: 'S0.07', artifact: 'config/baseline/content.json' },
  { stage: 'schema', unit: 'S0.08', artifact: 'config/baseline/schema.json' },
  { stage: 'classification', unit: 'S0.09', artifact: 'config/baseline/classification.json' },
  { stage: 'protocol', unit: 'S0.10', artifact: 'config/baseline/protocol.json' },
  { stage: 'design', unit: 'S0.11', artifact: 'config/design/tokens.baseline.json' },
  { stage: 'brand', unit: 'S0.12', artifact: 'config/design/brand.baseline.json' },
  { stage: 'adoption', unit: 'S0.13', artifact: 'config/operit-adoption.json' },
  { stage: 'assets', unit: 'S0.14', artifact: 'config/runtime-assets.json' },
  { stage: 'platform', unit: 'S0.15', artifact: 'config/baseline/platform.json' },
  { stage: 'legacy-export', unit: 'S0.16', artifact: 'config/baseline/legacy-export.json' },
  { stage: 'test-foundation', unit: 'S0.17', artifact: 'config/baseline/test-foundation.json' },
  { stage: 'sync-formats', unit: 'S0.18', artifact: 'config/baseline/sync-formats.json' },
  { stage: 'registry-contract', unit: 'S0.19', artifact: 'config/sync-registry-contract.json' },
  { stage: 'release-window', unit: 'S0.20', artifact: 'config/sync-release-window.json' },
  { stage: 'templates-assets', unit: 'S0.21', artifact: 'config/contracts/templates-assets.json' },
  { stage: 'media-time', unit: 'S0.22', artifact: 'config/contracts/media-time.json' },
  { stage: 'portable-config', unit: 'S0.23', artifact: 'config/contracts/portable-config.json' },
  { stage: 'plugin-data', unit: 'S0.24', artifact: 'config/contracts/plugin-data.json' },
  { stage: 'execution-plan', unit: 'S0.25', artifact: 'config/execution-plan.json' },
];
const started = performance.now();
try {
  for (const { stage, unit } of stages) {
    log('verification.unit.start', { unit, stage });
    const { run } = await import(`./${stage}.mjs`);
    await run('verify');
    log('verification.unit.pass', { unit, stage });
  }
  await (await import('./registry-audit.mjs')).run();
  await (await import('./release-audit.mjs')).run();
  await (await import('./asset-transport.mjs')).run('verify');
  await (await import('./chat-attachment.mjs')).run('verify');
  await (await import('./media-audit.mjs')).run('verify');
  await (await import('./config-audit.mjs')).run('verify');
  await (await import('./plugin-audit.mjs')).run('verify');
  const plan = readFileSync(resolve(root, 'docs/plans/2026-11-15-mobile-full-feature-refactor.md'), 'utf8');
  const units = new Set([...plan.matchAll(/^\| (S\d\.\d\d) \|/gm)].map(m => m[1]));
  assert.equal(units.size, 302);
  let assignments = 0;
  const walk = (node, path) => {
    if (node === null || typeof node !== 'object') return;
    for (const [key, value] of Object.entries(node)) {
      if (['deliveryUnit', 'adoptionUnit', 'androidAdoptionUnit'].includes(key)) {
        assert.ok(units.has(value), `${path}.${key}: unknown plan unit ${value}`);
        assignments++;
      }
      walk(value, `${path}.${key}`);
    }
  };
  for (const { stage, artifact: artifactPath } of stages.filter(entry => entry.unit !== 'S0.01')) walk(readJson(artifactPath), stage);
  const navigation = readJson('config/baseline/navigation.json');
  const workbench = readJson('config/baseline/workbench.json');
  const contributions = readJson('config/baseline/contributions.json');
  const domains = readJson('config/baseline/domains.json');
  const content = readJson('config/baseline/content.json');
  const schema = readJson('config/baseline/schema.json');
  const classification = readJson('config/baseline/classification.json');
  const design = readJson('config/design/tokens.baseline.json');
  const sources = readJson('config/source-lock.json');
  assert.equal(sources.repositories.find(entry => entry.id === 'desktop').commit,
    'e6b2657fd59014a73f5bbb1d14c2db732cf23e87');
  assert.equal(domains.plugins.length, 30);
  assert.equal(domains.adoption.upstreamMobileChain, 'not-adopted');
  assert.equal(domains.sourceIndex.length, domains.coverage.productionFiles);
  assert.equal(workbench.staticRegistry.count, 22);
  assert.equal(workbench.dynamicFamily.prefix, 'plugin-view.');
  assert.equal(workbench.dynamicFamily.staticGoldenMembership, false);
  assert.equal(contributions.settings.length, 573);
  assert.equal(contributions.slotTypes.length, 28);
  assert.equal(contributions.controls.length, 156);
  assert.equal(contributions.slotSurfaces.length, 11);
  for (const item of navigation.navigation) assert.ok(navigation.views.some(view => view.id === item.viewId));
  for (const app of workbench.apps) assert.ok(app.acceptance.length >= 2 && app.draft && app.close && app.suspend && app.snapshot);
  for (const cmd of domains.commands) assert.ok(cmd.module && cmd.behaviorCase.id && cmd.definition.args && cmd.definition.result);
  for (const setting of contributions.settings) assert.ok(['portable', 'local', 'secret'].includes(setting.scope) && setting.persistence && setting.android.route);
  for (const component of content.generativeTypes) assert.equal(component.vectors.length, 2);
  assert.equal(schema.migrations.length, 135);
  assert.equal(schema.historyFreeze.frozen.count, 134);
  assert.equal(schema.historyFreeze.appended.version, 20261115);
  assert.equal(schema.databases.find(entry => entry.id === 'vfs').version, 20261115);
  assert.equal(schema.registeredDatabases.length, 7);
  assert.equal(classification.registry.length, 124);
  assert.equal(classification.registry.filter(row => row.category === 'RowSync').length, 58);
  assert.ok(classification.gapTasks.length >= 5);
  assert.equal(classification.checks.unregistered.businessInSyncedDatabases, 0);
  assert.equal(design.unit, 'S0.11');
  assert.equal(design.palettes.presets.length, 9);
  assert.equal(design.palettes.all.length, 10);
  assert.equal(Object.keys(design.scenarios).length, 2);
  assert.equal(design.checks.paletteScenarios, 20);
  assert.equal(design.checks.paletteNeutralDiffering, 0);
  assert.equal(design.checks.customMatches, true);
  assert.equal(design.checks.buttons.primarySolid, true);
  assert.equal(design.themeVariants.slot, 'theme.variants');
  assert.equal(design.checks.provenance.unmatched, 0);
  assert.ok(design.checks.uniqueTokens >= 800);
  assert.ok(design.checks.domainCoverageCount >= 90);
  assert.ok(design.checks.shapes >= 18 && design.checks.typography >= 40 && design.checks.motion >= 80);
  for (const variant of Object.values(design.variants)) assert.ok(variant.note && variant.comparedTo && variant.state);
  const brand = readJson('config/design/brand.baseline.json');
  assert.equal(brand.unit, 'S0.12');
  assert.equal(brand.brand.mark.paths.length, 5);
  assert.equal(brand.brand.logo.paths.length, 31);
  assert.equal(brand.brand.logo.publicAsset.sameGeometry, true);
  assert.equal(brand.brand.mark.titleAttribute, 'DeepStudent');
  assert.equal(brand.brand.logo.titleAttribute, 'DeepStudent');
  for (const item of [brand.brand.mark, brand.brand.logo]) {
    const entry = item.mapping;
    assert.ok(entry.imageVector && entry.geometrySha256 && entry.paths.length > 0);
    assert.equal(new Set(entry.paths.map(path => path.token)).size, 2);
    assert.equal(entry.paths.length, item.paths.length);
    assert.ok(Math.abs(entry.defaultWidthDp / entry.defaultHeightDp - item.width / item.height) < 1e-12);
  }
  assert.equal(brand.icons.navigationIcons.length, 9);
  assert.equal(brand.icons.navigationIcons.filter(icon => icon.regular.paths > 0 && icon.bold.paths > 0).length, 9);
  for (const icon of brand.icons.navigationIcons) {
    assert.ok(icon.mapping.imageVector && icon.mapping.geometrySha256, `${icon.navigationId}: icon mapping`);
    assert.ok(icon.regular.geometrySha256 !== icon.bold.geometrySha256, `${icon.navigationId}: weights differ`);
  }
  assert.equal(brand.terminology.locale.files, 48);
  assert.equal(brand.terminology.locale.missing.length, 21);
  assert.equal(brand.terminology.sourcePair.counts.enMissing, 7);
  assert.equal(brand.terminology.sourcePair.counts.zhMissing, 14);
  assert.equal(brand.terminology.locale.pluralFamilies.length, 7);
  for (const family of brand.terminology.locale.pluralFamilies) {
    assert.equal(family.semanticResolvable, true);
    assert.deepEqual(family.resolvedCounts, ['one', 'other']);
    assert.ok(family.actualI18next.one && family.actualI18next.other);
  }
  assert.equal(brand.terminology.workshop.desktopOnlyRule.applyToAndroid, false);
  assert.equal(brand.terminology.locale.empty.length, 7);
  for (const entry of brand.terminology.locale.empty) assert.ok(entry.kotlin.resource && entry.reason);
  for (const list of [brand.terminology.pairs.views, brand.terminology.pairs.navigation, brand.terminology.pairs.settingsSections, brand.terminology.pairs.actions, brand.terminology.pairs.chat]) {
    for (const item of list) assert.ok(item.zh.trim() !== '' && item.en.trim() !== '', `${item.key}: pair`);
  }
  assert.equal(brand.terminology.pairs.views.length, 17);
  assert.equal(brand.terminology.pairs.navigation.length, 9);
  assert.equal(brand.terminology.pairs.settingsSections.length, 18);
  assert.equal(brand.terminology.commands.catalog.length + brand.terminology.commands.fallbacks.length, 96);
  for (const command of brand.terminology.commands.catalog) assert.ok(command.zh.trim() !== '' && command.en.trim() !== '');
  for (const command of brand.terminology.commands.fallbacks) assert.ok(command.getterKey && command.zh.trim() !== '' && command.en.trim() !== '' && /values\/strings\.xml/.test(command.kotlin));
  assert.equal(brand.keyPages.pages.length, 12);
  assert.equal(brand.keyPages.screenshots.length + brand.keyPages.missing.length, 96);
  assert.ok(brand.keyPages.missing.every(item => item.platform === 'linux' && item.scene === 'notes'));
  for (const shot of brand.keyPages.screenshots) {
    assert.equal(shot.signature, '89504e470d0a1a0a');
    assert.equal(shot.width, shot.viewport === 'desktop' ? 1440 : 390);
    assert.equal(shot.height, shot.viewport === 'desktop' ? 900 : 844);
    assert.ok(shot.sha256.length === 64 && shot.scene && shot.theme);
  }
  for (const page of brand.keyPages.pages) {
    assert.ok(page.zh && page.en && page.route && page.goldens.length > 0);
    assert.ok(page.workbenchApp.appId && page.workbenchApp.resourceTypes.length > 0 && page.workbenchApp.phone, `${page.scene}: workbench app`);
    if (page.scene.startsWith('shell-')) assert.equal(page.demoPack.file, null, `${page.scene}: whole-shell demo has no app pack`);
    else assert.ok(page.demoPack.file && page.demoPack.title, `${page.scene}: demo pack`);
  }
  assert.equal(brand.keyPages.pages.filter(page => page.goldens.length === 8).length, 11);
  assert.equal(brand.keyPages.pages.find(page => page.scene === 'notes').goldens.length, 4);
  assert.equal(brand.operitPages.length, 13);
  const nameSources = { 'operit-string': 8, 'dstu-naming': 3, 'desktop-locale': 2 };
  for (const page of brand.operitPages) {
    assert.ok(page.zh && page.en && page.route && page.module && page.deliveryUnit, `${page.id}: identity`);
    assert.ok(page.tokens.length >= 6 && page.tokens.every(token => token.light && token.dark && token.kotlin));
    assert.ok(page.source.file && page.stateMapping && page.acceptance);
    assert.ok(['operit-string', 'dstu-naming', 'desktop-locale'].includes(page.nameSource), `${page.id}: name source kind`);
    assert.ok(page.nameSourceRef, `${page.id}: name source reference`);
    assert.ok(page.kotlinName.resource && page.kotlinName.zh === page.zh && page.kotlinName.en === page.en);
  }
  for (const [source, count] of Object.entries(nameSources)) assert.equal(brand.operitPages.filter(page => page.nameSource === source).length, count, `${source} pages`);
  assert.equal(brand.terminology.missingEnglishReview.commandLabels.count, brand.terminology.commands.fallbacks.length);
  assert.equal(brand.terminology.missingEnglishReview.emptyLocaleValues.count, brand.terminology.locale.empty.length);
  assert.equal(brand.terminology.missingEnglishReview.emptyLocaleValues.enClassifierOmissions, 6);
  assert.deepEqual(brand.terminology.missingEnglishReview.emptyLocaleValues.byKind, { 'count-classifier': 5, 'weekday-prefix': 1, joiner: 1 });
  assert.deepEqual(brand.terminology.missingEnglishReview.emptyLocaleValues.withoutCurrentCaller, ['anki:error_count_unit', 'anki:taskDashboard.cardsUnit']);
  assert.equal(brand.terminology.locale.empty.filter(entry => entry.consumer !== null).length, 5);
  assert.equal(brand.terminology.missingEnglishReview.emptyLocaleValues.bothEmpty, 1);
  for (const entry of brand.terminology.locale.empty) assert.ok(entry.kotlin.unified && entry.kotlin.unified.rule, `${entry.key}: unified rule`);
  for (const item of brand.terminology.locale.hashes) assert.ok(item.locale && item.namespace && item.sha256.length === 64 && item.leaves > 0);
  assert.equal(brand.terminology.locale.hashes.length, 96);
  assert.equal(brand.checks.screenshotsCommitted, 92);
  assert.equal(brand.checks.operitTokenRefs, brand.operitPages.reduce((total, page) => total + page.tokens.length, 0));
  const adoption = readJson('config/operit-adoption.json');
  assert.equal(adoption.unit, 'S0.13');
  assert.equal(adoption.adopted.length, 21);
  assert.equal(adoption.adopted.filter(item => item.section === '3.1').length, 12);
  assert.equal(adoption.adopted.filter(item => item.section === '3.2').length, 9);
  assert.equal(adoption.checks.selfChecks.rejectsFakeSymbol, true);
  assert.equal(adoption.checks.selfChecks.rejectsWrongLine, true);
  assert.equal(adoption.checks.selfChecks.rejectsUnknownPackage, true);
  assert.equal(adoption.checks.selfChecks.rejectsUnmappedTerminalPackage, true);
  assert.equal(adoption.checks.selfChecks.rejectsBrokenChain, true);
  assert.equal(adoption.checks.brandPagesMapped, brand.operitPages.length);
  assert.equal(adoption.checks.operitCommit, brand.checks.operitCommit);
  assert.equal(adoption.checks.terminalCommit, readJson('config/source-lock.json').repositories.find(item => item.id === 'terminal').commit);
  assert.equal(adoption.checks.kernelModule, 'terminal');
  assert.equal(adoption.checks.externalPackManager, 'app/src/main/java/com/ai/assistance/operit/core/tools/system/OperitTerminalManager.kt');
  const kernel = adoption.adopted.find(item => item.id === 'terminal-kernel');
  assert.ok(kernel.evidence.every(step => step.file.startsWith('terminal/')), 'terminal kernel evidence must come from the terminal submodule');
  assert.ok(kernel.callChainSteps.some(step => step.file.includes('TerminalManager.kt')), 'terminal kernel chain must reach TerminalManager');
  assert.equal(adoption.rootfs.codename, 'noble');
  assert.equal(adoption.rootfs.consistent, true);
  assert.equal(adoption.rootfs.aptCodename, adoption.rootfs.installScriptCodename);
  assert.ok(adoption.rootfs.entries > 10000 && adoption.rootfs.sha256.length === 64);
  const dependencyRisk = adoption.adopted.find(item => item.id === 'dependency-mirror').risks.join(' ');
  assert.ok(dependencyRisk.includes('pnpm') && dependencyRisk.includes('noble'), 'dependency risks must record pnpm and rootfs findings');
  const shizukuRisk = adoption.adopted.find(item => item.id === 'shizuku').risks.join(' ');
  assert.ok(shizukuRisk.includes('stdout') && shizukuRisk.includes('stderr'), 'shizuku risk must record concurrent stream issue');
  const fgsRisk = adoption.adopted.find(item => item.id === 'foreground-service').risks.join(' ');
  assert.ok(fgsRisk.includes('overlay'), 'foreground risk must record transparent overlay as internal test');
  const brandPageIds = new Set(brand.operitPages.map(page => page.id));
  for (const item of adoption.adopted) {
    assert.ok(['direct-port', 'adapter', 'reimplement'].includes(item.method), `${item.id}: method`);
    assert.ok(item.evidence.length + item.callChainSteps.length >= 6, `${item.id}: evidence depth`);
    assert.ok(item.callChainSteps.every(step => step.text && step.sha256.length === 64), `${item.id}: evidence hash`);
    assert.ok(item.adapters.length > 0 && item.persistence && item.executionIdentity && item.owner && item.acceptance, `${item.id}: seam fields`);
    assert.ok(item.risks.length > 0 && item.dependencies.length > 0, `${item.id}: risk/dependency`);
    assert.ok(item.brandPages.every(page => brandPageIds.has(page)), `${item.id}: brand page`);
  }
  for (const item of adoption.modules) {
    assert.ok(['adopted', 'partial', 'replaced', 'rejected'].includes(item.decision), `${item.id}: decision`);
    if (['adopted', 'partial'].includes(item.decision)) assert.ok(item.owners.length > 0, `${item.id}: owners`);
    if (item.decision === 'replaced') assert.equal(item.owners.length, 0, `${item.id}: replaced has owners`);
  }
  assert.ok(adoption.modules.filter(item => item.decision === 'rejected').length >= 30);
  assert.ok(adoption.rejected.every(item => item.reason && item.scope === 'product-scope'));
  assert.equal(adoption.checks.packagesAdopted + adoption.checks.packagesPartial + adoption.checks.packagesReplaced + adoption.checks.packagesRejected, adoption.checks.appPackages);
  assert.equal(adoption.checks.terminalAdopted + adoption.checks.terminalPartial + adoption.checks.terminalReplaced + adoption.checks.terminalRejected, adoption.checks.terminalPackages);
  assert.equal(adoption.checks.operitPackages, adoption.checks.appPackages + adoption.checks.terminalPackages);
  const assets = readJson('config/runtime-assets.json');
  const notices = readJson('config/runtime-notices.json');
  assert.equal(assets.unit, 'S0.14');
  assert.equal(assets.evidence.adoption.unit, 'S0.13');
  assert.equal(assets.evidence.sourceLock.desktopCommit, readJson('config/source-lock.json').repositories.find(item => item.id === 'desktop').commit);
  assert.equal(assets.evidence.sourceLock.operitCommit, adoption.checks.operitCommit);
  assert.equal(assets.evidence.sourceLock.terminalCommit, adoption.checks.terminalCommit);
  assert.equal(assets.evidence.adoption.rootfsSha256, adoption.rootfs.sha256);
  assert.equal(assets.evidence.desktopLicense.desktopCommit, assets.evidence.sourceLock.desktopCommit);
  assert.equal(assets.evidence.desktopLicense.acknowledgments.usedAsAndroidNotice, false);
  assert.equal(assets.evidence.desktopLicense.acknowledgments.supersedesProjectNotice, false);
  assert.equal(assets.evidence.desktopLicense.frontendBundleInventory.vueIncluded, false);
  assert.equal(assets.evidence.desktopLicense.fonts.unchangedAtFrozenCommit, true);
  assert.equal(assets.evidence.desktopLicense.fonts.generationCommit, '145bb3879164c58d1cd5ff39daa94a3daa9fe874');
  assert.equal(assets.evidence.desktopLicense.adoptionBoundary.apkRelevant, false);
  assert.equal(notices.desktopEvidence.acknowledgments.usedAsAndroidNotice, false);
  assert.equal(assets.rootfs.sha256, adoption.rootfs.sha256);
  assert.equal(assets.rootfs.consistent, true);
  assert.equal(assets.rootfs.packages, 185);
  assert.equal(assets.rootfs.copyrightFiles.length, 181);
  assert.equal(assets.rootfs.unresolved.length, 1);
  assert.equal(assets.rootfs.unresolved[0].package, 'openssl');
  assert.equal(assets.checks.rootfsCopyrightFamiliesKnown + assets.checks.rootfsCopyrightFamiliesNone + assets.rootfs.unresolved.length, assets.rootfs.packages);
  assert.equal(assets.checks.rootfsCopyrightLicenseFields, assets.rootfs.copyrightFiles.filter(item => item.source === 'license-field').length);
  assert.ok(assets.rootfs.copyrightFiles.every(item => ['license-field', 'declared-unmapped', 'prose', 'none'].includes(item.source)));
  assert.ok(assets.rootfs.copyrightFiles.filter(item => item.source === 'declared-unmapped').every(item => item.declared.length > 0 && item.declared.every(name => (name.match(/GPL-|LGPL-|AGPL-|GFDL-|Apache-2\.0|MPL-|CC0-1\.0|BSD-[0-9]-Clause|Expat|ISC|MIT|Unlicense|public-domain/) ?? []).length === 0)));
  assert.ok(assets.rootfs.copyrightFiles.filter(item => item.source === 'license-field').every(item => item.families.length > 0));
  assert.ok(assets.rootfs.commonLicenses.every(item => item.id === item.path.split('/').at(-1) && item.sha256.length === 64));
  assert.equal(assets.rootfs.traversal.length, 0);
  assert.equal(assets.coverage.unclassified.length, 0);
  assert.equal(assets.checks.unclassifiedBinaryFiles, 0);
  assert.equal(assets.checks.hashChecksPassed, assets.checks.hashChecks);
  assert.equal(assets.checks.sensitiveExcludedFiles, 2);
  assert.equal(assets.checks.sensitiveExcludedScanned, 2);
  assert.deepEqual(assets.assets.find(asset => asset.id === 'subpack-keystores').files.filter(item => item.format === 'sensitive-excluded').map(item => item.path).sort(), ['app/src/main/assets/jks.jks', 'app/src/main/assets/pkcs12.keystore']);
  assert.equal(assets.assets.find(asset => asset.id === 'subpack-keystores').files.find(item => item.path.endsWith('KeyStoreHelper.kt')).format, 'other');
  assert.equal(assets.checks.elfChecked, assets.checks.elf16kPass + assets.checks.elf16kFail);
  assert.deepEqual(assets.page16k.fail.map(item => item.assetId).sort(), ['aapt2-arm64', 'aapt2-arm64', 'operit-shell-exec']);
  for (const asset of assets.assets) {
    assert.ok(asset.kind && asset.category && asset.deliveryUnit, `${asset.id}: identity`);
    assert.ok(asset.source && asset.build && asset.packaging && asset.license && asset.notice, `${asset.id}: sections`);
    assert.ok(asset.files.every(file => file.format === 'sensitive-excluded'
      ? file.sha256 === null && file.elf === null && file.acquisition === 'metadata-only; private material excluded'
      : file.sha256.length === 64), `${asset.id}: file hashes`);
    assert.ok(['resolved', 'declared-unverified', 'unknown', 'aggregate', 'mixed', 'project'].includes(asset.license.status), `${asset.id}: license status`);
    if (asset.license.status === 'unknown') assert.equal(asset.license.id, null, `${asset.id}: unknown license must not carry an id`);
  }
  assert.equal(assets.assets.find(asset => asset.id === 'terminal-libbash').elf[0].minLoadAlign, 16384);
  assert.equal(assets.assets.find(asset => asset.id === 'terminal-libsudo').files[0].format, 'short');
  assert.equal(assets.assets.find(asset => asset.id === 'terminal-libsudo').files[0].bytes, 2);
  assert.equal(assets.assets.find(asset => asset.id === 'terminal-liboperit-loader').license.status, 'unknown');
  assert.equal(assets.assets.find(asset => asset.id === 'quickjs-engine').build.pageSizeDeclared, 'max-only');
  assert.equal(assets.assets.find(asset => asset.id === 'quickjs-engine').source.ref, 'master');
  assert.equal(assets.assets.find(asset => asset.id === 'quickjs-engine').source.commit, readJson('config/source-lock.json').engines.find(engine => engine.id === 'quickjs').commit);
  assert.equal(assets.evidence.sttManifest.rows.length, 8);
  assert.equal(assets.evidence.sttManifest.rows.filter(row => row.licenseNote.includes('not declared')).length, 7);
  assert.equal(assets.groups.length, 12);
  assert.ok(assets.licenseTexts.every(text => text.sha256.length === 64));
  assert.equal(notices.unit, 'S0.14');
  assert.equal(notices.summary.components, notices.components.length);
  assert.equal(notices.summary.resolved + notices.summary.unresolved + notices.summary.notRequired, notices.components.length);
  assert.equal(notices.checks.unknownLicenseWithoutReview, 0);
  for (const component of notices.components) {
    assert.ok(component.textRefs.every(ref => ref.sha256 === null || ref.sha256.length === 64), `${component.id}: text hash`);
    if (component.status === 'resolved') assert.ok(component.license && component.textRefs.length > 0, `${component.id}: resolved needs text`);
  }
  assert.ok(notices.components.filter(component => component.status === 'unresolved').length >= 20);
  const platform = readJson('config/baseline/platform.json');
  const contract = readJson('config/release-contract.json');
  assert.equal(platform.unit, 'S0.15');
  assert.equal(contract.unit, 'S0.15');
  assert.equal(platform.version.initial.version, contract.version.initial.version);
  assert.equal(platform.version.initial.versionCode, contract.version.initial.versionCode);
  assert.equal(platform.version.mapping.formula, contract.version.mapping.formula);
  assert.equal(platform.legacy.certificate.value, contract.signing.releaseCertificateSha256);
  assert.equal(platform.packages.length, contract.packages.observed.length);
  for (const row of contract.packages.observed) {
    const pkg = platform.packages.find(entry => entry.path === row.path);
    assert.ok(pkg, `platform package ${row.path}`);
    assert.equal(pkg.badging.versionCode, row.versionCode);
    assert.equal(pkg.sha256, row.sha256);
    assert.equal(pkg.signature.signerSha256, row.certificateSha256);
  }
  assert.equal(platform.packages.filter(pkg => pkg.releaseCertificateMatch).length, 0);
  assert.equal(contract.releasePackage.observed, false);
  assert.equal(platform.platform.decision.minSdk, contract.platform.minSdk);
  assert.equal(platform.platform.decision.targetSdk, contract.platform.targetSdk);
  assert.deepEqual(platform.platform.decision.abi, contract.abi.shipped);
  assert.equal(platform.native16k.failureCount, contract.native16k.tasks.length);
  assert.ok(platform.native16k.tasks.every(task => difference(task.acceptance, contract.native16k.acceptance) === null), 'platform acceptance must equal the contract channels');
  assert.deepEqual(Object.keys(contract.native16k.acceptance).sort(), ['debug', 'deviceRuntime', 'hostStatic', 'release']);
  assert.ok(contract.native16k.acceptance.hostStatic.every(item => item.runsInThisUnit === false));
  assert.equal(contract.native16k.acceptance.deviceRuntime.executedInThisUnit, false);
  assert.deepEqual(contract.native16k.acceptance.release.requiredFields, ['nativeLibraryDir', 'PROOT_LOADER', 'LD_LIBRARY_PATH', 'cwd', 'uid']);
  assert.equal(platform.workspace.dstuAndroid.hasGradleProject, false);
  assert.equal(platform.checks.pass, platform.checks.cases);
  assert.ok(platform.checks.cases >= 25);
  assert.equal(platform.version.initial.versionCode, 2100000);
  assert.ok(platform.version.initial.versionCode > platform.version.observedPackage.versionCode);
  const unresolved = design.scenarios['light/default'].tokens;
  for (const [name, token] of Object.entries(unresolved)) {
    if (token.res === 'resolved') assert.ok(!/var\(/.test(token.computed), `${name}: unresolved var marked resolved`);
    if (token.res === 'contextual') assert.ok(token.expression && token.expression !== token.computed || typeof token.expression === 'string', `${name}: contextual token missing expression`);
  }
  const legacy = readJson('config/baseline/legacy-export.json');
  const legacyContract = readJson('config/legacy-import-contract.json');
  assert.equal(legacy.unit, 'S0.16');
  assert.equal(legacyContract.unit, 'S0.16');
  assert.equal(legacy.delivery.deliveryUnit, 'S2.12');
  assert.equal(legacyContract.delivery.importerUnit, 'S2.12');
  assert.equal(legacy.checks.samples, 2);
  assert.equal(legacy.checks.failures, 0);
  assert.equal(legacy.checks.fingerprintsMatchS008, true);
  assert.equal(legacy.checks.slotRestorePassed, true);
  assert.deepEqual(legacy.checks.identityModes, ['standard', 'continuity']);
  assert.equal(legacy.checks.duplicateTakeoverRejected, true);
  assert.deepEqual(Object.keys(legacy.samples).sort(), ['continuity', 'standard']);
  for (const sample of Object.values(legacy.samples)) {
    assert.equal(sample.manifest.key_policy, 'not_present');
    assert.equal(sample.manifest.snapshot_kind, 'full');
    assert.equal(sample.slotRestore.ok, true);
    assert.equal(sample.checks.failures, 0);
    for (const id of ['vfs', 'llm_usage', 'chat_v2', 'mistakes']) {
      const baseline = schema.databases.find(database => database.id === id);
      assert.equal(sample.databases[id].version, baseline.version, `S0.16 ${id}: version`);
      assert.equal(sample.databases[id].history, baseline.history.length, `S0.16 ${id}: refinery rows`);
      assert.equal(sample.fingerprints[id].fingerprint, baseline.fingerprint, `S0.16 ${id}: fingerprint`);
    }
  }
  assert.equal(legacy.samples.continuity.sidecar.status, 'new-contract-in-this-unit');
  assert.equal(legacy.identity.continuity.status, 'new-contract-in-this-unit');
  assert.equal(legacy.channelFacts.observedReturn, null);
  assert.equal(legacy.channelFacts.predictionOnly, true);
  assert.ok(['share', 'save-picker', 'download', 'unavailable'].includes(legacy.channelFacts.predictedMethod));
  assert.equal(legacy.channelFacts.transferChecks.cases, 5);
  assert.equal(legacy.channelFacts.transferChecks.capacitorNativePromiseRoundTrip, true);
  assert.equal(legacy.identity.continuity.negatives.length, 11);
  assert.equal(legacy.channelFacts.saveCapabilities.hasAnchorDownload, true);
  assert.equal(legacy.channelFacts.androidReality.setsDownloadListener, false);
  assert.equal(legacy.checks.liveSlotMatchesArchive, true);
  assert.equal(legacy.checks.oneShotProbe, true);
  assert.equal(legacy.identity.continuity.diskReplay.matches, true);
  assert.equal(legacy.identity.continuity.duplicate.accepted, false);
  assert.equal(legacy.identity.continuity.tamperedSnapshot.accepted, false);
  assert.equal(legacyContract.validation.order.length, 10);
  assert.equal(legacyContract.keyPolicy.manifest, 'not_present');
  assert.ok(legacy.userSteps.steps.length >= 6);
  assert.equal(difference({ sql: { hash: 'a' } }, { sql: { hash: 'b' } }).path, '$.sql.hash');
  const execution = readJson('config/execution-plan.json');
  assert.equal(execution.units.length, 302);
  assert.equal(execution.stageExits.length, 10);
  assert.equal(execution.coverage.parameterized.unresolvedMembers, 0);
  assert.equal(execution.unresolved.length, 0);
  assert.equal(execution.policy.hashPolicy.currentHashState, 'frozen');
  assert.equal(execution.m0.readyForFinalSignoff, true);
  log('verification.gate.start', { unit: 'S0.G' });
  const gate = await (await import('./stage-gate.mjs')).run('verify');
  assert.equal(gate.signed, true, 'S0.G requires a valid main-thread review receipt');
  assert.equal(gate.mainReview.m0Complete, true);
  log('verification.complete', { units: stages.length, gate: 'S0.G', m0Complete: true,
    planUnits: units.size, validatedAssignments: assignments, elapsedMs: Math.round(performance.now() - started) });
} catch (error) {
  log('verification.failed', { message: error.message, stack: error.stack });
  process.exitCode = 1;
}
