#!/usr/bin/env python3
"""Name the build stage that failed, from a captured Gradle log.

A Gradle failure summary only says "Execution failed for task ':mobile:packageRelease'", which
does not tell a maintainer whether the network dropped a dependency, the NDK refused a flag, or
Kotlin rejected a type. This script maps the log to one of the stages the workflow documents and
prints the evidence, so the annotation in the Actions UI is actionable.

Usage: build_stage_report.py <gradle.log>   (exit code 1 so it can be the failing step)
"""
from __future__ import annotations

import re
import sys

# Ordered: the first stage with evidence wins, because a dependency failure also stops Kotlin.
STAGES: list[tuple[str, list[str]]] = [
    (
        "DEPENDENCY RESOLUTION",
        [
            r"Could not resolve all",
            r"Could not resolve ",
            r"Could not find .*\.(jar|aar|pom)",
            r"Could not download",
            r"Could not GET '",
            r"No cached version of .* available for offline mode",
            r"Plugin \[id: '.*'\] was not found",
        ],
    ),
    (
        "NDK BUILD",
        [
            r"configureNdkBuild",
            r"buildNdkBuild",
            r"externalNativeBuild",
            r"ndk-build",
            r"CMake Error",
            r"ninja: build stopped",
            r"error: (?:unknown argument|use of undeclared identifier)",
        ],
    ),
    (
        "KOTLIN COMPILE",
        [r"compile\w*Kotlin", r"^e: file:", r"Compilation error\.", r"Kotlin compiler"],
    ),
    (
        "RESOURCES / PACKAGING",
        [
            r"process\w*Resources",
            r"AAPT2? (?:error|failed)",
            r"package\w+",
            r"merge\w*JniLibs",
            r"jarsigner",
            r"apksigner",
            r"SigningConfig",
        ],
    ),
    ("LINT", [r"lintVital", r"Lint found .* errors?"]),
]

TASK_FAILURE = re.compile(r"^> Task (:\S+) FAILED", re.MULTILINE)
WHAT_WENT_WRONG = re.compile(r"^\* What went wrong:\s*\n(.+)$", re.MULTILINE)


def read(path: str) -> str:
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except OSError as failure:
        print(f"::warning::could not read the build log: {failure}")
        return ""


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: build_stage_report.py <gradle.log>")
        return 2
    log = read(sys.argv[1])
    if not log:
        print("::error::Build failed and no Gradle log was captured")
        return 1

    failed_tasks = TASK_FAILURE.findall(log)
    reason = WHAT_WENT_WRONG.search(log)
    if reason:
        print(f"Gradle reason: {reason.group(1).strip()}")

    for stage, patterns in STAGES:
        evidence = []
        for pattern in patterns:
            match = re.search(pattern, log, re.MULTILINE)
            if match:
                line = log[match.start():].splitlines()[0][:300]
                evidence.append(line)
        if evidence:
            print(f"::error title=Build failed in {stage}::"
                  f"stage={stage}; tasks={', '.join(failed_tasks) or 'none'}; evidence={evidence[0]}")
            print(f"FAILED STAGE: {stage}")
            for item in evidence[:5]:
                print(f"  evidence: {item}")
            return 1

    print(f"::error title=Build failed in an unidentified stage::tasks={', '.join(failed_tasks) or 'none'}")
    print("FAILED STAGE: UNKNOWN (no dependency, NDK, Kotlin or packaging marker in the log)")
    return 1


if __name__ == "__main__":
    sys.exit(main())
