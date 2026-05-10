# 🛑 STRICT CONTRIBUTING GUIDELINES 🛑

Welcome to the Shizuku-modern repository. 
We operate under a **ZERO-TOLERANCE POLICY** for low-effort contributions. This document outlines the absolute requirements for participating in this project. Read it carefully. Ignorance is not an excuse.

## 1. Issue Reporting (Bug Reports & Feature Requests)
Do not treat the issue tracker as a tech-support forum.

- **Use the Templates:** You MUST use the provided GitHub Issue templates. If you delete the template text or fail to fill it out completely, your issue will be closed automatically.
- **Search First:** Duplicates will be closed. We have an automated bot that flags similar issues. Check open and closed issues before posting.
- **No Screenshots of Text:** If you have an error, you must paste the actual log text inside Markdown code blocks (```log). Screenshots of logcats are an instant rejection.
- **Reproducibility is Mandatory:** If you report a bug, you must provide EXACT steps to reproduce it. If we cannot reproduce it, the issue will be closed.

## 2. Pull Request (PR) Requirements
We welcome code contributions, but they must meet our rigorous standards.

- **One PR = One Purpose:** Do not bundle unrelated changes (e.g., fixing a bug and reformatting an unrelated file) into a single PR.
- **Compile & Test:** Your code MUST compile locally via `./gradlew assembleRelease` without any new warnings. 
- **Architectural Alignment:** Follow the existing architecture. This fork heavily uses Jetpack Compose, Material 3 Expressive, and strictly adheres to the Android 16/17 target SDKs. Do not introduce legacy Android UI concepts or bypass Edge-to-Edge inset handling.
- **UI Changes Require Proof:** If your PR alters the UI, you MUST attach Before & After screenshots (or a video) in the PR description.
- **Clean Git History:** Squash your commits into logical, working units before opening a PR. Use meaningful commit messages.

## 3. Code Style & Standards
- **Kotlin:** Follow standard Kotlin idioms. No trailing spaces.
- **Compose:** Avoid deep nesting. Prefer state hoisting. Use `ShizukuExpressiveTheme` for all new UI components.
- **Linting:** Ensure your code passes all internal linters before submitting.

## 4. Conduct
We are direct and expect technical competence. Be precise, factual, and strictly focused on the codebase.

By contributing to this repository, you agree to follow these rules without exception.
