# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 0. Spec-Driven Development

**명세서가 진실의 원천(source of truth)이다.**

- 명세서·설계 문서는 `docs/` 디렉토리에 phase별로 정리되어 있다 (`docs/phase{N}_document/`).
- 코드 작성/수정 전에 해당 phase의 명세서를 먼저 확인한다.
- 명세서에 정의된 요청/응답 스키마, 처리 흐름, 에러 코드를 따른다.
- 명세서와 코드가 충돌하면 먼저 보고하고, 어느 쪽을 기준으로 할지 확인받는다.
- 코드 변경 후 명세서도 함께 갱신해야 하는지 항상 확인한다.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

## 5. Concise Output

**짧게 말해. 단, 파일 작성은 예외.**

대화 응답:
- 결론부터. 이유는 필요할 때만.
- 한 문장으로 될 걸 세 문장으로 쓰지 마.
- 코드 변경 후 요약 반복 금지 — diff가 말해줌.
- 테이블·목록은 좋지만 장황한 설명은 금지.

파일 작성(명세서, summary.md 등)은 이 규칙에서 제외 — 문서는 완전한 문장과 충분한 설명을 유지.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.