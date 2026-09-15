# XML Layout Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Execute the migration as one cohesive task, with independent verification alongside it.

**Goal:** Move fixed settings, AI configuration and course editor UI into XML without changing data or behavior.

**Architecture:** Inflate native Android layouts and bind controls in existing Java controllers. Reuse XML styles for static appearance; keep generated week/color cells and their state logic in Java.

**Tech Stack:** Java 17, native Android Views, XML, Gradle, existing isolated instrumentation runner.

## Task 1: Baseline and XML contract tests

- [x] Run `tests\run-pure-java.cmd` and save clean baseline; check `git status --short`.
- [x] Add `tests/test_xml_layouts.py` asserting required layouts exist, contain real inputs/buttons (not only placeholders), declare masking/saveEnabled for AI key, and contain unique ids. Run `python -m unittest discover -s tests -p test_xml_layouts.py`; expect missing-layout assertions before implementation.
- [x] Start existing Small_Phone emulator without touching formal app data; use `tests/reviewfix.init.gradle` QA applicationId only. Capture baseline via test instrumentation where available.

## Task 2: Three-page migration (one implementer)

Files: create `res/layout/page_settings.xml`, `item_setting.xml`, `item_setting_toggle.xml`, `page_ai_settings.xml`, `dialog_course_editor.xml`; resources `values/strings.xml`, `colors.xml`, `dimens.xml`, shared XML styles and drawable backgrounds. Modify MainActivity.java, ImportController.java and CourseEditor.java only where required to inflate/bind migrated pages; small Ui binding helpers allowed.

- [x] Settings: inflate page into existing screen root; bind XML include rows by root id and find nested title/detail ids. Preserve every existing navigation action, dynamic counts and the two preference keys/defaults. Binding pattern: `View row = page.findViewById(R.id.settings_terms); row.setOnClickListener(v -> showTerms());`.
- [x] AI: inflate page into existing subScreen root; bind endpoint/model/key/status and actions; populate defaults before adding TextWatcher. Keep revision/cancellation, key saveEnabled(false), hints, and all existing save/test/model-list logic unchanged. Pattern: `EditText endpoint = page.findViewById(R.id.ai_endpoint); endpoint.setText(AiConfig.url(a));`.
- [x] Editor: inflate dialog root and bind static text fields, spinners, close/save/custom-color buttons. Set initial Course values and spinner adapters with existing item order and selection clamping; keep original Saved callback and validation. Week/color containers are XML, generated cells stay Java, as allowed by the design.
- [x] Preserve original dp/sp/padding/background/font rules from Ui helpers. Do not introduce new libraries, schema, package/signing changes, redesign or migrate other pages.
- [x] Run XML contracts, compile `:app:assembleDebug`, inspect diff for accidental business changes, self-review.

## Task 3: Independent verification and review

- [x] Extend isolated instrumentation to exercise settings switch persistence and navigation, AI ids/masking/save/test, editor close/no save, add/edit/invalid input. Use fake courses/local HTTP only. Existing 10 review regression cases remain.
- [x] Run QA build/install/instrument; verify explicit PASS/FAIL totals, not adb exit code alone. Compare before/after screenshots for migrated pages including editor scrolling.
- [x] Independent spec review, then code-quality review. Resolve concrete findings and rerun tests.
- [x] Run pure Java and all Python tests; run production `:app:assembleDebug :app:lintDebug` without QA init; verify package remains com.kejian.app. Do not install formal package over user data.
- [x] Document Java/XML mappings and evidence in `docs/xml-layout-migration.md`, including any untested device scenarios. Keep version1.2.1 unchanged for this refactor; deliver separately named APK to avoid replacing old packages, and state it is debug-signed.
- [x] Leave changes locally for review; do not push or publish a Release. Original ba9195f and prior APKs remain available.

## Self-review

Scope matches approved 2026-09-13 spec. No new visual choice is required. All database/API rules remain outside migration. This session executes continuously; only meaningful blockers require another user decision.
